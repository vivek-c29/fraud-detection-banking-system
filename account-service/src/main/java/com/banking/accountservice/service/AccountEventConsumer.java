package com.banking.accountservice.service;

import com.banking.accountservice.entity.ProcessedEvent;
import com.banking.accountservice.repository.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class AccountEventConsumer {

    private final AccountService accountService;
    private final ProcessedEventRepository processedEventRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    private static final String TRANSACTION_REFUNDED_TOPIC = "transaction.refunded";
    private static final String TRANSACTION_FAILED_TOPIC = "transaction.failed";

    /**
     * Consume transaction completed event from kafka
     * Credits receiver amount idempotently.
     * If crediting fails (e.g. receiver account blocked/closed), initiates compensatory refund to sender.
     * @param payload
     */
    @KafkaListener(topics = "transaction.completed")
    @Transactional
    public void consumeTransactionCompleted(
            @Payload Map<String, Object> payload
    ) {
        String transactionId = (String) payload.get("transactionId");
        String eventId = "transaction.completed:" + transactionId;

        if (processedEventRepository.existsById(eventId)) {
            log.warn("Event {} already processed. Skipping duplicate execution.", eventId);
            return;
        }

        String senderAccount = (String) payload.get("senderAccountNumber");
        String receiverAccount = (String) payload.get("receiverAccountNumber");
        BigDecimal amount = new BigDecimal(payload.get("amount").toString());

        try {
            log.info("Crediting account: {} with amount: {} for event {}", receiverAccount, amount, eventId);
            accountService.creditBalance(receiverAccount, amount);

            processedEventRepository.save(new ProcessedEvent(eventId));
            log.info("Event {} processed successfully and recorded.", eventId);
        } catch (Exception e) {
            log.error("Error while crediting receiver account {} for event {}: {}. Initiating SAGA compensation refund to sender {}",
                    receiverAccount, eventId, e.getMessage(), senderAccount);

            try {
                // Compensatory Action: Refund sender
                accountService.creditBalance(senderAccount, amount);
                log.info("Compensatory refund of ₹{} credited back to sender {}", amount, senderAccount);

                // Publish refund notification event
                Map<String, Object> refundEvent = new HashMap<>();
                refundEvent.put("transactionId", transactionId);
                refundEvent.put("senderAccountNumber", senderAccount);
                refundEvent.put("receiverAccountNumber", receiverAccount);
                refundEvent.put("amount", amount);
                refundEvent.put("reason", "Receiver account could not be credited (" + e.getMessage() + "). Amount refunded to your account.");
                kafkaTemplate.send(TRANSACTION_REFUNDED_TOPIC, transactionId, refundEvent);

                // Publish transaction.failed to update transaction-service status
                Map<String, Object> failEvent = new HashMap<>();
                failEvent.put("transactionId", transactionId);
                failEvent.put("reason", "Receiver credit failed: " + e.getMessage() + ". Sender refunded.");
                kafkaTemplate.send(TRANSACTION_FAILED_TOPIC, transactionId, failEvent);

                // Mark processed so we don't refund twice
                processedEventRepository.save(new ProcessedEvent(eventId));
            } catch (Exception compensationError) {
                log.error("CRITICAL: Failed to process compensatory refund for sender {}: {}", senderAccount, compensationError.getMessage());
                throw new RuntimeException("Compensation failed: " + compensationError.getMessage(), compensationError);
            }
        }
    }

    /**
     * Consume payment completed event from payment-service (Razorpay)
     * Credits account balance idempotently
     * @param payload
     */
    @KafkaListener(topics = "payment.completed")
    @Transactional
    public void consumePaymentCompleted(
            @Payload Map<String, Object> payload
    ) {
        String paymentId = (String) payload.get("paymentId");
        String eventId = "payment.completed:" + paymentId;

        if (processedEventRepository.existsById(eventId)) {
            log.warn("Event {} already processed. Skipping duplicate deposit credit.", eventId);
            return;
        }

        try {
            String accountNumber = (String) payload.get("accountNumber");
            BigDecimal amount = new BigDecimal(payload.get("amount").toString());
            log.info("Crediting payment deposit of ₹{} to account: {} for event {}", amount, accountNumber, eventId);
            accountService.creditBalance(accountNumber, amount);

            processedEventRepository.save(new ProcessedEvent(eventId));
            log.info("Payment event {} credited and recorded successfully.", eventId);
        } catch (Exception e) {
            log.error("Error while crediting deposit for event {}: {}", eventId, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /**
     * Consume fraud detected event from kafka
     * Blocks the account idempotently
     * @param payload
     */
    @KafkaListener(topics = "fraud.detected")
    @Transactional
    public void consumeFraudDetected(
            @Payload Map<String, Object> payload
    ) {
        String transactionId = (String) payload.get("transactionId");
        String accountNumber = (String) payload.get("accountNumber");
        String eventId = "fraud.detected:" + (transactionId != null ? transactionId : accountNumber);

        if (processedEventRepository.existsById(eventId)) {
            log.warn("Event {} already processed. Skipping duplicate block.", eventId);
            return;
        }

        try {
            log.info("Blocking account: {} due to fraud detected for event {}", accountNumber, eventId);
            accountService.blockAccount(accountNumber);

            processedEventRepository.save(new ProcessedEvent(eventId));
            log.info("Fraud event {} processed successfully and recorded.", eventId);
        } catch (Exception e) {
            log.error("Error while blocking account for event {}: {}", eventId, e.getMessage());
            throw new RuntimeException(e);
        }
    }

}
