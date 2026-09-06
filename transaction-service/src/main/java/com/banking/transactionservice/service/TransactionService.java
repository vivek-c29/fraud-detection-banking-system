package com.banking.transactionservice.service;

import com.banking.transactionservice.client.AccountServiceClient;
import com.banking.transactionservice.dto.AccountResponse;
import com.banking.transactionservice.dto.TransactionResponse;
import com.banking.transactionservice.dto.TransferRequest;
import com.banking.transactionservice.entity.Transaction;
import com.banking.transactionservice.entity.TransactionStatus;
import com.banking.transactionservice.entity.TransactionType;
import com.banking.transactionservice.event.TransactionCompletedEvent;
import com.banking.transactionservice.event.TransactionInitiatedEvent;
import com.banking.transactionservice.repository.TransactionRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountServiceClient accountServiceClient;

    private final KafkaTemplate<String, Object> template;
    private final RedisTemplate<String, String> redisTemplate;

    private static final String TRANSACTION_INITIATED_TOPIC = "transaction.initiated";
    private static final String TRANSACTION_COMPLETED_TOPIC = "transaction.completed";
    private static final String TRANSACTION_REFUNDED_TOPIC = "transaction.refunded";
    private static final String FRAUD_DETECTED_TOPIC = "fraud.detected";

    /**
     * SAGA Step 1 - Initiate transfer
     * Validates sender & receiver account status and sender balance synchronously.
     * Saves transaction as PROCESSING without permanent upfront debit.
     * Publishes event to Kafka for fraud check.
     * @param request
     * @return
     */
    public TransactionResponse transfer(@Valid TransferRequest request) {
        log.info("SAGA Start - Transfer: {} -> {} amount: {}",
                request.getSenderAccountNumber(), request.getReceiverAccountNumber(), request.getAmount());

        if (request.getSenderAccountNumber().equals(request.getReceiverAccountNumber())) {
            throw new RuntimeException("Sender and receiver account cannot be the same");
        }

        // 1. Validate Sender Account
        AccountResponse senderAccount;
        try {
            senderAccount = accountServiceClient.getAccount(request.getSenderAccountNumber());
        } catch (Exception e) {
            log.error("Sender account lookup failed: {}", e.getMessage());
            throw new RuntimeException("Sender account not found: " + request.getSenderAccountNumber(), e);
        }

        if (!"ACTIVE".equalsIgnoreCase(senderAccount.getStatus())) {
            log.warn("Sender account {} is not active (status: {})", request.getSenderAccountNumber(), senderAccount.getStatus());
            throw new RuntimeException("Sender account is " + senderAccount.getStatus() + ". Cannot initiate transfer.");
        }

        // 2. Validate Receiver Account
        AccountResponse receiverAccount;
        try {
            receiverAccount = accountServiceClient.getAccount(request.getReceiverAccountNumber());
        } catch (Exception e) {
            log.error("Receiver account lookup failed: {}", e.getMessage());
            throw new RuntimeException("Receiver account not found: " + request.getReceiverAccountNumber(), e);
        }

        if (!"ACTIVE".equalsIgnoreCase(receiverAccount.getStatus())) {
            log.warn("Receiver account {} is not active (status: {})", request.getReceiverAccountNumber(), receiverAccount.getStatus());
            throw new RuntimeException("Receiver account is " + receiverAccount.getStatus() + ". Cannot initiate transfer.");
        }

        // 3. Validate Sender Balance
        if (senderAccount.getBalance() != null && senderAccount.getBalance().compareTo(request.getAmount()) < 0) {
            log.warn("Insufficient balance for sender account {}. Available: {}, Requested: {}",
                    request.getSenderAccountNumber(), senderAccount.getBalance(), request.getAmount());

            Transaction failedTx = new Transaction();
            failedTx.setSenderAccountNumber(request.getSenderAccountNumber());
            failedTx.setReceiverAccountNumber(request.getReceiverAccountNumber());
            failedTx.setAmount(request.getAmount());
            failedTx.setType(TransactionType.TRANSFER);
            failedTx.setStatus(TransactionStatus.FAILED);
            failedTx.setFailureReason("Insufficient balance. Available: ₹" + senderAccount.getBalance() + ", Requested: ₹" + request.getAmount());
            failedTx.setDescription(request.getDescription());
            failedTx.setReferenceNumber(request.getReceiverAccountNumber());
            transactionRepository.save(failedTx);

            throw new RuntimeException("Insufficient balance. Available: ₹" + senderAccount.getBalance() + ", Requested: ₹" + request.getAmount());
        }

        Transaction transaction = new Transaction();
        transaction.setSenderAccountNumber(request.getSenderAccountNumber());
        transaction.setReceiverAccountNumber(request.getReceiverAccountNumber());
        transaction.setAmount(request.getAmount());
        transaction.setType(TransactionType.TRANSFER);
        transaction.setStatus(TransactionStatus.PROCESSING);
        transaction.setDescription(request.getDescription());
        transaction.setReferenceNumber(request.getReceiverAccountNumber());

        Transaction savedTransaction = transactionRepository.save(transaction);
        log.info("Transaction saved as PROCESSING: {}", savedTransaction.getId());

        // Publish Event for Fraud Check
        TransactionInitiatedEvent event = new TransactionInitiatedEvent(
                savedTransaction.getId(),
                savedTransaction.getSenderAccountNumber(),
                savedTransaction.getReceiverAccountNumber(),
                savedTransaction.getAmount(),
                savedTransaction.getDescription()
        );

        template.send(TRANSACTION_INITIATED_TOPIC, savedTransaction.getId(), event);
        log.info("Transaction Initiated Event published for fraud check: {}", savedTransaction.getId());

        return mapToResponse(savedTransaction);
    }

    private TransactionResponse mapToResponse(Transaction transaction){
        TransactionResponse response = new TransactionResponse();
        response.setAmount(transaction.getAmount());
        response.setId(transaction.getId());
        response.setDescription(transaction.getDescription());
        response.setFailureReason(transaction.getFailureReason());
        response.setStatus(transaction.getStatus());
        response.setReferenceNumber(transaction.getReferenceNumber());
        response.setSenderAccountNumber(transaction.getSenderAccountNumber());
        response.setReceiverAccountNumber(transaction.getReceiverAccountNumber());
        response.setType(transaction.getType());
        response.setCreatedAt(transaction.getCreatedAt());
        response.setCompletedAt(transaction.getCompletedAt());

        return response;
    }

    public TransactionResponse getTransaction(String transactionId) {
        return mapToResponse(transactionRepository.
                findById(transactionId).orElseThrow(() -> new RuntimeException(
                        "Transaction not found: " + transactionId
                )));
    }

    public List<TransactionResponse> getTransactionHistory(String accountNumber) {
        return transactionRepository.findBySenderAccountNumberOrderByCreatedAtDesc(accountNumber)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    /**
    * SAGA Step: OTP Verification for Suspicious Transactions
    * Updated to support max 3 attempts, prevent account blocking on wrong OTP, and handle OTP expiry.
    */
    public TransactionResponse verifyOtp(String transactionId, String otp) {
        log.info("OTP Verification for transaction: {}", transactionId);
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new RuntimeException("Transaction not found: " + transactionId));

        if (transaction.getStatus() != TransactionStatus.PENDING_VERIFICATION) {
            log.warn("Transaction {} is not in PENDING_VERIFICATION (status: {})", transactionId, transaction.getStatus());
            return mapToResponse(transaction);
        }

        String otpKey = "verification:otp:" + transactionId;
        String attemptsKey = "verification:otp_attempts:" + transactionId;
        // Retrieve stored OTP; fallback to legacy key without colon for backward compatibility
        String storedOtp = redisTemplate.opsForValue().get(otpKey);
        if (storedOtp == null) {
            // Try legacy key format
            String legacyOtpKey = "verification:otp" + transactionId;
            storedOtp = redisTemplate.opsForValue().get(legacyOtpKey);
            if (storedOtp != null) {
                // Migrate legacy key to new format for future lookups
                redisTemplate.opsForValue().set(otpKey, storedOtp, redisTemplate.getExpire(legacyOtpKey));
                redisTemplate.delete(legacyOtpKey);
                log.info("Migrated OTP from legacy key {} to new key {}", legacyOtpKey, otpKey);
            }
        }

        // OTP Expired handling
        if (storedOtp == null) {
            log.warn("OTP Expired - Reject transaction without debit (set FAILED)");
            transaction.setStatus(TransactionStatus.FAILED);
            transaction.setFailureReason("OTP expired - transaction cancelled (no debit performed)");
            transactionRepository.save(transaction);
            // Cleanup attempts key
            redisTemplate.delete(attemptsKey);
            return mapToResponse(transaction);
        }

        // Retrieve current attempt count
        Integer attempts = null;
        String attemptsStr = redisTemplate.opsForValue().get(attemptsKey);
        if (attemptsStr != null) {
            try {
                attempts = Integer.valueOf(attemptsStr);
            } catch (NumberFormatException e) {
                attempts = 0;
            }
        } else {
            attempts = 0;
        }

        final int MAX_ATTEMPTS = 3;
        if (!storedOtp.equals(otp)) {
            // Increment attempt counter atomically
            Long newAttempts = redisTemplate.opsForValue().increment(attemptsKey);
            // Ensure TTL matches OTP key
            Long otpTtl = redisTemplate.getExpire(otpKey);
            if (otpTtl != null && otpTtl > 0) {
                redisTemplate.expire(attemptsKey, java.time.Duration.ofSeconds(otpTtl));
            }

            log.warn("Wrong OTP entered for transaction: {}. Attempt {}/{}.", transactionId, newAttempts, MAX_ATTEMPTS);
            if (newAttempts != null && newAttempts >= MAX_ATTEMPTS) {
                // Max attempts exceeded - flag transaction, cleanup keys
                redisTemplate.delete(otpKey);
                redisTemplate.delete(attemptsKey);
                transaction.setStatus(TransactionStatus.FAILED);
                transaction.setFailureReason("Maximum OTP verification attempts exceeded - transaction cancelled");
                transactionRepository.save(transaction);
                // Publish fraud detection event if needed (optional, but do not block account)
                Map<String, Object> fraudEvent = new HashMap<>();
                fraudEvent.put("transactionId", transaction.getId());
                fraudEvent.put("accountNumber", transaction.getSenderAccountNumber());
                fraudEvent.put("reason", "Maximum OTP attempts exceeded");
                template.send(FRAUD_DETECTED_TOPIC, transaction.getSenderAccountNumber(), fraudEvent);
                log.warn("fraud.detected published for account: {} due to max OTP attempts", transaction.getSenderAccountNumber());
                return mapToResponse(transaction);
            }
            // Do not block account, keep status as PENDING_VERIFICATION
            return mapToResponse(transaction);
        }

        // Correct OTP - proceed to debit, clean attempts
        log.info("OTP Verified for transaction: {}. Proceeding to debit sender and complete transfer.", transactionId);
        redisTemplate.delete(otpKey);
        redisTemplate.delete(attemptsKey);
        try {
            // Step 1: Debit sender
            accountServiceClient.deductBalance(transaction.getSenderAccountNumber(), transaction.getAmount());
            // Step 2: Complete transaction & credit receiver via Kafka
            completeTransaction(transaction);
            return mapToResponse(transaction);
        } catch (Exception e) {
            log.error("Failed to debit sender after OTP verification: {}", e.getMessage());
            transaction.setStatus(TransactionStatus.FAILED);
            transaction.setFailureReason("Debit failed after OTP verification: " + e.getMessage());
            transactionRepository.save(transaction);
            throw new RuntimeException("Debit failed: " + e.getMessage(), e);
        }
    }

    /**
     * SAGA Step: Complete transaction after debit is confirmed
     * Marks transaction status as COMPLETED and publishes transaction.completed to Kafka to credit receiver
     */
    private void completeTransaction(Transaction transaction) {
        transaction.setStatus(TransactionStatus.COMPLETED);
        transaction.setCompletedAt(LocalDateTime.now());
        transactionRepository.save(transaction);

        TransactionCompletedEvent completedEvent = new TransactionCompletedEvent(
                transaction.getId(),
                transaction.getSenderAccountNumber(),
                transaction.getReceiverAccountNumber(),
                transaction.getAmount(),
                transaction.getDescription()
        );

        template.send(TRANSACTION_COMPLETED_TOPIC, transaction.getId(), completedEvent);
        log.info("SAGA Completed - Transaction {} marked COMPLETED and published to Kafka", transaction.getId());
    }

    /**
     * SAGA Step: Process clean fraud check result
     * Debits sender account, marks transaction as COMPLETED, and publishes transaction.completed
     */
    public void processCleanResult(String transactionId) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new RuntimeException("Transaction not found: " + transactionId));

        if (transaction.getStatus() != TransactionStatus.PROCESSING) {
            log.warn("Transaction {} not in PROCESSING state (status: {}) - skipping clean processing", transactionId, transaction.getStatus());
            return;
        }

        try {
            // Step 1: Debit sender account
            accountServiceClient.deductBalance(transaction.getSenderAccountNumber(), transaction.getAmount());

            // Step 2: Complete transaction and trigger receiver credit via Kafka
            completeTransaction(transaction);
        } catch (Exception e) {
            log.error("Failed to debit sender for clean transaction {}: {}", transactionId, e.getMessage());
            transaction.setStatus(TransactionStatus.FAILED);
            transaction.setFailureReason("Debit failed: " + e.getMessage());
            transactionRepository.save(transaction);
        }
    }
}
