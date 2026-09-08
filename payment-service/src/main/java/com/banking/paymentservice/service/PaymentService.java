package com.banking.paymentservice.service;

import com.banking.paymentservice.dto.CreatePaymentRequest;
import com.banking.paymentservice.dto.PaymentOrderResponse;
import com.banking.paymentservice.dto.VerifyPaymentRequest;
import com.banking.paymentservice.entity.Payment;
import com.banking.paymentservice.entity.PaymentStatus;
import com.banking.paymentservice.repository.PaymentRepository;
import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.razorpay.Utils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${razorpay.key-id}")
    private String keyId;
    @Value("${razorpay.key-secret}")
    private String keySecret;
    @Value("${razorpay.webhook-secret:}")
    private String webhookSecret;

    private static final String PAYMENT_COMPLETED_TOPIC = "payment.completed";
    private static final String PAYMENT_FAILED_TOPIC = "payment.failed";

    /**
     * Create Razorpay Order
     */
    public PaymentOrderResponse createOrder(CreatePaymentRequest request) throws RazorpayException {
        log.info("Creating payment order for account: {} amount: {}",
                request.getAccountNumber(), request.getAmount());

        RazorpayClient razorpay = new RazorpayClient(keyId, keySecret);

        int amountInPaise = request.getAmount()
                .multiply(BigDecimal.valueOf(100))
                .intValue();

        JSONObject orderRequest = new JSONObject();
        orderRequest.put("amount", amountInPaise);
        orderRequest.put("currency", "INR");
        orderRequest.put("receipt", "rcpt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 30));

        Order razorpayOrder = razorpay.orders.create(orderRequest);
        log.info("Razorpay order created: {}", razorpayOrder.get("id").toString());

        // Save payment record
        Payment payment = new Payment();
        payment.setRazorpayOrderId(razorpayOrder.get("id").toString());
        payment.setAccountNumber(request.getAccountNumber());
        payment.setAmount(request.getAmount());
        payment.setCurrency("INR");
        payment.setStatus(PaymentStatus.CREATED);
        payment.setDescription(request.getDescription());

        Payment saved = paymentRepository.save(payment);

        return new PaymentOrderResponse(
                saved.getId(),
                razorpayOrder.get("id").toString(),
                request.getAmount(),
                "INR",
                "CREATED",
                keyId
        );
    }

    /**
     * Verifies the signature returned by Checkout before publishing the deposit event.
     * This supports local/test checkout where Razorpay cannot call a localhost webhook.
     */
    @Transactional
    public PaymentOrderResponse verifyPayment(VerifyPaymentRequest request) throws RazorpayException {
        Payment payment = paymentRepository.findByRazorpayOrderId(request.getRazorpayOrderId())
                .orElseThrow(() -> new RuntimeException("Payment not found for order: " + request.getRazorpayOrderId()));

        if (payment.getStatus() == PaymentStatus.COMPLETED) {
            return mapToResponse(payment);
        }
        if (payment.getStatus() != PaymentStatus.CREATED && payment.getStatus() != PaymentStatus.PENDING) {
            throw new RuntimeException("Payment cannot be verified in status: " + payment.getStatus());
        }

        JSONObject attributes = new JSONObject();
        attributes.put("razorpay_order_id", request.getRazorpayOrderId());
        attributes.put("razorpay_payment_id", request.getRazorpayPaymentId());
        attributes.put("razorpay_signature", request.getRazorpaySignature());
        if (!Utils.verifyPaymentSignature(attributes, keySecret)) {
            throw new SecurityException("Invalid Razorpay payment signature");
        }

        completePayment(payment, request.getRazorpayPaymentId());
        return mapToResponse(payment);
    }

    private PaymentOrderResponse mapToResponse(Payment payment) {
        return new PaymentOrderResponse(payment.getId(), payment.getRazorpayOrderId(), payment.getAmount(),
                payment.getCurrency(), payment.getStatus().name(), keyId);
    }

    private void completePayment(Payment payment, String razorpayPaymentId) {
        if (payment.getStatus() == PaymentStatus.COMPLETED) {
            return;
        }
        payment.setRazorpayPaymentId(razorpayPaymentId);
        payment.setStatus(PaymentStatus.COMPLETED);
        paymentRepository.save(payment);

        Map<String, Object> event = new HashMap<>();
        event.put("paymentId", payment.getId());
        event.put("accountNumber", payment.getAccountNumber());
        event.put("amount", payment.getAmount());
        event.put("razorpayPaymentId", razorpayPaymentId);
        kafkaTemplate.send(PAYMENT_COMPLETED_TOPIC, payment.getId(), event);
        log.info("Payment completed and published to Kafka: paymentId={}, orderId={}", payment.getId(), payment.getRazorpayOrderId());
    }

    /**
     * Process Webhook with HMAC-SHA256 signature verification and idempotency check
     */
    public void processWebhook(String rawPayload, String signature) {
        // Step 1: Mandatory webhook secret check
        if (webhookSecret == null || webhookSecret.trim().isEmpty()) {
            log.error("CRITICAL: Razorpay webhook-secret is not configured. Webhooks are rejected for safety.");
            throw new SecurityException("Webhook processing is disabled: missing configured webhook secret");
        }

        // Step 2: Mandatory signature presence check
        if (signature == null || signature.trim().isEmpty()) {
            log.error("Missing X-Razorpay-Signature header in webhook request");
            throw new SecurityException("Missing Razorpay webhook signature header");
        }

        // Step 3: Verify signature using Razorpay SDK
        try {
            boolean isValid = Utils.verifyWebhookSignature(rawPayload, signature, webhookSecret);
            if (!isValid) {
                log.error("Invalid Razorpay webhook signature");
                throw new SecurityException("Invalid Razorpay webhook signature");
            }
        } catch (RazorpayException e) {
            log.error("Error during webhook signature verification: {}", e.getMessage());
            throw new SecurityException("Webhook signature verification failed: " + e.getMessage(), e);
        }

        // Step 2: Parse payload JSON
        JSONObject payloadJson = new JSONObject(rawPayload);
        String event = payloadJson.optString("event");
        log.info("Processing verified Razorpay Webhook event: {}", event);

        if ("payment.captured".equals(event)) {
            handlePaymentSuccess(payloadJson);
        } else if ("payment.failed".equals(event)) {
            handlePaymentFailure(payloadJson);
        } else {
            log.info("Unhandled Razorpay webhook event: {}", event);
        }
    }

    @Transactional
    public void handlePaymentSuccess(JSONObject payloadJson) {
        try {
            JSONObject paymentEntity = payloadJson.getJSONObject("payload")
                    .getJSONObject("payment")
                    .getJSONObject("entity");

            String orderId = paymentEntity.getString("order_id");
            String paymentId = paymentEntity.getString("id");

            Payment payment = paymentRepository.findByRazorpayOrderId(orderId)
                    .orElseThrow(() -> new RuntimeException("Payment not found for order: " + orderId));

            // IDEMPOTENCY CHECK: If already completed, skip duplicate processing
            if (payment.getStatus() == PaymentStatus.COMPLETED) {
                log.warn("Payment for order {} already COMPLETED. Skipping duplicate webhook execution.", orderId);
                return;
            }

            completePayment(payment, paymentId);

        } catch (Exception e) {
            log.error("Error while handling payment success webhook: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    @Transactional
    public void handlePaymentFailure(JSONObject payloadJson) {
        try {
            JSONObject paymentEntity = payloadJson.getJSONObject("payload")
                    .getJSONObject("payment")
                    .getJSONObject("entity");

            String orderId = paymentEntity.getString("order_id");
            String paymentId = paymentEntity.optString("id", null);

            Payment payment = paymentRepository.findByRazorpayOrderId(orderId)
                    .orElseThrow(() -> new RuntimeException("Payment not found for order: " + orderId));

            // IDEMPOTENCY CHECK: If already failed or completed, skip
            if (payment.getStatus() == PaymentStatus.FAILED || payment.getStatus() == PaymentStatus.COMPLETED) {
                log.warn("Payment for order {} already in terminal state ({}). Skipping duplicate webhook execution.", orderId, payment.getStatus());
                return;
            }

            payment.setRazorpayPaymentId(paymentId);
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason("Payment failed via Razorpay API");
            paymentRepository.save(payment);

            // Publish event to Kafka
            Map<String, Object> event = new HashMap<>();
            event.put("paymentId", payment.getId());
            event.put("accountNumber", payment.getAccountNumber());
            event.put("amount", payment.getAmount());
            event.put("reason", "Payment failed via Razorpay API");

            kafkaTemplate.send(PAYMENT_FAILED_TOPIC, payment.getId(), event);
            log.warn("Payment failed and published to Kafka: paymentId={}, orderId={}", payment.getId(), orderId);
        } catch (Exception e) {
            log.error("Error while handling payment failure webhook: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }
}
