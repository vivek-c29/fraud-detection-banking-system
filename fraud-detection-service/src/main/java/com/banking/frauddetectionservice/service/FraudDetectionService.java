package com.banking.frauddetectionservice.service;

import com.banking.frauddetectionservice.client.AccountServiceClient;
import com.banking.frauddetectionservice.model.FraudCheckResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class FraudDetectionService {

    private final AccountServiceClient accountServiceClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final RedisTemplate<String, String> redisTemplate;

    private static final String VERIFICATION_REQUIRED_TOPIC = "verification.required";
    private static final String FRAUD_CHECK_CLEAN_RESULT = "fraud.check.clean";

    @Value("${fraud.max-transaction-per-minute}")
    private int maxTransactionsPerMinute;
    @Value("${fraud.suspicious-amount-multipler}")
    private double suspiciousAmountMultiplier;
    @Value("${fraud.max-balance-percentage}")
    private double maxBalancePercentage;

    public void checkTransaction(Map<String, Object> payload) {
        String transactionId = (String)  payload.get("transactionId");
        String accountNumber = (String) payload.get("senderAccountNumber");
        BigDecimal amount = new BigDecimal(payload.get("amount").toString());

      //  Fetch Real balance from Account Service
        BigDecimal senderBalance = accountServiceClient.getBalance(accountNumber);
        log.info("Checking transaction: {} amount:{} balance:{}", transactionId, amount, senderBalance);

        FraudCheckResult result = performFraudChecks(accountNumber, amount, senderBalance);

        if(result.isFraud()){
            log.info("Suspicious activity detected - account: {} "+
                    "reason: {} - requestion Otp Verification", accountNumber, result.getReason());

            Map<String, Object> verificationEvent = new HashMap<>();
            verificationEvent.put("transactionId", transactionId);
            verificationEvent.put("accountNumber", accountNumber);
            verificationEvent.put("amount", amount);
            verificationEvent.put("reason", result.getReason());

            kafkaTemplate.send(VERIFICATION_REQUIRED_TOPIC, transactionId,verificationEvent);

        }
        else{
            // Transaction is safe(clean).
            Map<String, Object> transactionCleanEvent = new HashMap<>();
            transactionCleanEvent.put("transactionId", transactionId);
            transactionCleanEvent.put("isFraud", false);
            transactionCleanEvent.put("reason", null);
            kafkaTemplate.send(FRAUD_CHECK_CLEAN_RESULT, transactionId, transactionCleanEvent);
        }
    }

    private FraudCheckResult performFraudChecks(String accountNumber, BigDecimal amount, BigDecimal senderBalance) {
        // Pattern 1. Velocity Check
        if(isVelocityExceeded(accountNumber)) {
            return new FraudCheckResult(
                    true, "Too many transaction in 60 seconds" +
                    " - velocity limit exceeded");
        }

        // Pattern 2. Amount check
        if(isAmountSuspicious(accountNumber, amount)){
            return new FraudCheckResult(
                    true, "Unusual transaction amount" +
                    " - exceeds 3x your average");
        }

        // Pattern 3. Balance check
        if(senderBalance.compareTo(BigDecimal.ZERO) >0 && isBalanceCheckFailed(senderBalance, amount)){
            return new FraudCheckResult(
                    true, "Transaction exceed 90% of account balance"
            );
        }

        return new FraudCheckResult(
                false, null
        );

    }

    private boolean isVelocityExceeded(String accountNumber) {

        String key = "fraud:velocity:" + accountNumber;
        long now = System.currentTimeMillis();
        long windowStart = now - 60_000;

        // Remove transactions older than 60 seconds
        redisTemplate.opsForZSet().removeRangeByScore(key, 0, windowStart);

        // Add current transaction with timestamp as score
        redisTemplate.opsForZSet().add(key, UUID.randomUUID().toString(), now);

        // Count transactions in the last 60 seconds
        Long count = redisTemplate.opsForZSet().count(key, windowStart, now);

        // Keep Redis key only as long as needed
        redisTemplate.expire(key, Duration.ofSeconds(60));

        log.info("Velocity check - account: {} count: {}/{}", accountNumber, count, maxTransactionsPerMinute);

        return count != null && count > maxTransactionsPerMinute;
    }

    private boolean isAmountSuspicious(String accountNumber, BigDecimal amount) {
        String avgKey = "fraud:avg_amount"+accountNumber;
        String avgStr = redisTemplate.opsForValue().get(avgKey);

        if(avgStr == null){
            redisTemplate.opsForValue().set(avgKey, amount.toString());
            return false;
        }

        BigDecimal avgAmount = new BigDecimal(avgStr);
        BigDecimal threshold = avgAmount.multiply(BigDecimal.valueOf(suspiciousAmountMultiplier));

        BigDecimal newAvg = avgAmount.add(amount)
                .divide(BigDecimal.valueOf(2), 2,  RoundingMode.HALF_UP);

        // Update Running average
        redisTemplate.opsForValue().set(avgKey, newAvg.toString());

        log.info("Amount check - amount: {} threshold: {} suspicious: {}",
                amount, threshold, amount.compareTo(threshold) > 0);

        return amount.compareTo(threshold) > 0;
    }

    private boolean isBalanceCheckFailed(BigDecimal senderBalance, BigDecimal amount) {
        BigDecimal maxAllowed = senderBalance.multiply(
                BigDecimal.valueOf(maxBalancePercentage));


        log.info("Balance check - amount: {} maxAllowed: {} suspicious: {}",
            amount, maxAllowed, amount.compareTo(maxAllowed) > 0);

        return amount.compareTo(maxAllowed) > 0;
    }
}
