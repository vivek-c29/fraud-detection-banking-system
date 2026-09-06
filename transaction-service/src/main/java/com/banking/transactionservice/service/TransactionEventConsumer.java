package com.banking.transactionservice.service;

import com.banking.transactionservice.entity.ProcessedEvent;
import com.banking.transactionservice.entity.Transaction;
import com.banking.transactionservice.entity.TransactionStatus;
import com.banking.transactionservice.repository.ProcessedEventRepository;
import com.banking.transactionservice.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class TransactionEventConsumer {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final RedisTemplate<String, String> redisTemplate;
    private final TransactionRepository transactionRepository;
    private final TransactionService transactionService;
    private final ProcessedEventRepository processedEventRepository;

    private static final long OTP_EXPIRY_MINUTES = 5;
    private static final String TRANSACTION_OTP_GENERATED_TOPIC = "transaction.otp.generated";

    @KafkaListener(topics = "verification.required")
    @Transactional
    public void consumeVerificationRequired(
            @Payload Map<String, Object> payload
    ) {
        String transactionId = (String) payload.get("transactionId");
        String eventId = "verification.required:" + transactionId;

        if (processedEventRepository.existsById(eventId)) {
            log.warn("Event {} already processed. Skipping duplicate OTP generation.", eventId);
            return;
        }

        try {
            String accountNumber = (String) payload.get("accountNumber");
            String reason = (String) payload.get("reason");

            log.info("Verification required - transaction: {} reason: {}", transactionId, reason);

            Transaction transaction = transactionRepository.findById(transactionId)
                    .orElseThrow(() -> new RuntimeException("Transaction not found - transactionId: " + transactionId));

            if (transaction.getStatus() != TransactionStatus.PROCESSING) {
                log.warn("Transaction {} not in PROCESSING state (status: {}) - skipping", transactionId, transaction.getStatus());
                return;
            }

            // Generate 6-digit OTP
            String otp = String.format("%06d", (int) (Math.random() * 900000) + 100000);

            // Store OTP in Redis with explicit 5-minute TTL
            String otpKey = "verification:otp:" + transactionId;
            redisTemplate.opsForValue().set(otpKey, otp, Duration.ofMinutes(OTP_EXPIRY_MINUTES));

            // Update status
            transaction.setStatus(TransactionStatus.PENDING_VERIFICATION);
            transactionRepository.save(transaction);

            // Record event processing
            processedEventRepository.save(new ProcessedEvent(eventId));

            log.info("OTP generated for transaction: {} expires in {} min", transactionId, OTP_EXPIRY_MINUTES);

            // Notify User via Kafka
            Map<String, Object> otpEvent = new HashMap<>();
            otpEvent.put("transactionId", transactionId);
            otpEvent.put("accountNumber", accountNumber);
            otpEvent.put("reason", reason);
            otpEvent.put("otp", otp);
            otpEvent.put("amount", payload.get("amount"));

            kafkaTemplate.send(TRANSACTION_OTP_GENERATED_TOPIC, otpEvent);

        } catch (Exception e) {
            log.error("Error while handling verification required for transaction {}: {}", transactionId, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    @KafkaListener(topics = "fraud.check.clean")
    @Transactional
    public void consumeFraudCheckCleanResult(
            @Payload Map<String, Object> payload
    ) {
        String transactionId = (String) payload.get("transactionId");
        String eventId = "fraud.check.clean:" + transactionId;

        if (processedEventRepository.existsById(eventId)) {
            log.warn("Event {} already processed. Skipping duplicate clean result.", eventId);
            return;
        }

        try {
            transactionService.processCleanResult(transactionId);
            processedEventRepository.save(new ProcessedEvent(eventId));
            log.info("Fraud check clean result for transaction {} processed and recorded.", transactionId);
        } catch (Exception e) {
            log.error("Error while processing fraud clean check for transaction {}: {}", transactionId, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    @KafkaListener(topics = "transaction.failed")
    @Transactional
    public void consumeTransactionFailed(
            @Payload Map<String, Object> payload
    ) {
        String transactionId = (String) payload.get("transactionId");
        String eventId = "transaction.failed:" + transactionId;

        if (processedEventRepository.existsById(eventId)) {
            log.warn("Event {} already processed. Skipping duplicate fail record.", eventId);
            return;
        }

        try {
            String reason = (String) payload.get("reason");
            log.warn("Transaction {} failed downstream: {}", transactionId, reason);

            transactionRepository.findById(transactionId).ifPresent(tx -> {
                tx.setStatus(TransactionStatus.FAILED);
                tx.setFailureReason(reason);
                transactionRepository.save(tx);
            });

            processedEventRepository.save(new ProcessedEvent(eventId));
            log.info("Transaction {} marked as FAILED in database.", transactionId);
        } catch (Exception e) {
            log.error("Error processing transaction.failed event for {}: {}", transactionId, e.getMessage());
            throw new RuntimeException(e);
        }
    }
}

