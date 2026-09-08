package com.banking.paymentservice.controller;

import com.banking.paymentservice.dto.CreatePaymentRequest;
import com.banking.paymentservice.dto.PaymentOrderResponse;
import com.banking.paymentservice.dto.VerifyPaymentRequest;
import com.banking.paymentservice.service.PaymentService;
import com.razorpay.RazorpayException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments")
@Slf4j
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/create-order")
    public ResponseEntity<PaymentOrderResponse> createPaymentOrder(
            @Valid @RequestBody CreatePaymentRequest request
    ) throws RazorpayException {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(paymentService.createOrder(request));
    }

    @PostMapping("/verify")
    public ResponseEntity<PaymentOrderResponse> verifyPayment(
            @Valid @RequestBody VerifyPaymentRequest request
    ) throws RazorpayException {
        return ResponseEntity.ok(paymentService.verifyPayment(request));
    }

    /**
     * Razorpay Webhook Handler
     * Receives raw payload String and X-Razorpay-Signature header for secure HMAC-SHA256 validation
     */
    @PostMapping("/webhook")
    public ResponseEntity<String> handleWebhook(
            @RequestBody String rawPayload,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature
    ) {
        log.info("Received Webhook callback from Razorpay");
        paymentService.processWebhook(rawPayload, signature);
        return ResponseEntity.status(HttpStatus.OK).body("Webhook processed successfully.");
    }
}
