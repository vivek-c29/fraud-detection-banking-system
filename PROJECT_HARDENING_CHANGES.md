# PROJECT_HARDENING_CHANGES.md — Banking Fraud Detection System

## 1. Summary

This document details the hardening and reliability fixes implemented across the **Banking Fraud Detection System** microservices codebase. All changes follow the principle of **minimum appropriate modifications** while strictly preserving the existing SAGA business flow, endpoints, and architectural integrity.

---

## 2. Changes by Issue

### Issue 1: Payment → Account Balance Credit
* **Problem**: When a deposit succeeded via Razorpay, `PaymentService` published `payment.completed` to Kafka. However, `AccountService` had no consumer for this topic, so user balances were never credited.
* **Root cause**: Missing Kafka consumer in `AccountEventConsumer`.
* **Existing behavior**: Payment status was set to `COMPLETED` and `NotificationService` logged a message, but user balance remained unchanged.
* **New behavior**: `AccountEventConsumer` listens to `payment.completed`, reads `accountNumber` and `amount`, calls `accountService.creditBalance()`, and atomically tracks the event in the database for idempotency.
* **Files changed**:
  - `account-service/.../service/AccountEventConsumer.java`
* **Why this approach**: Direct integration using the existing Kafka event contract (`paymentId`, `accountNumber`, `amount`) and existing `creditBalance()` service method without introducing extra services or changing event structures.

---

### Issue 2: Concurrent Balance Update / Lost Update
* **Problem**: Concurrent debit or credit requests on the same account could read the same initial balance, perform calculations, and overwrite each other's changes (lost update problem).
* **Root cause**: Absence of concurrency control on the `Account` database entity.
* **Existing behavior**: `accountRepository.save(account)` simply updated the row without version checking.
* **New behavior**: Added JPA `@Version private Long version;` to `Account` entity. Spring Data JPA / Hibernate automatically performs optimistic locking checks on every update (`UPDATE accounts SET balance = ?, version = version + 1 WHERE id = ? AND version = ?`). If a concurrent modification occurs, an `OptimisticLockingFailureException` is thrown, rejected cleanly, and translated to an HTTP 409 Conflict response.
* **Files changed**:
  - `account-service/.../entity/Account.java`
  - `account-service/.../service/AccountService.java` (`@Transactional` added to `deductBalance` & `creditBalance`)
  - `account-service/.../exception/GlobalExceptionHandler.java` (Catches `OptimisticLockingFailureException` → HTTP 409)
* **Why this approach**: Optimistic locking avoids database-level deadlocks and blocking overhead associated with pessimistic locking while preventing financial state corruption. Explicit no-auto-retry ensures money operations are never double-applied.

---

### Issue 3: Kafka Consumer Idempotency (Database-Backed)
* **Problem**: Kafka guarantees at-least-once delivery. If a consumer crashes before committing its offset or if network partitions occur, duplicate events (`transaction.completed`, `payment.completed`, `verification.required`, `fraud.detected`) would cause duplicate credits or duplicate actions.
* **Root cause**: No persistent record of processed Kafka event IDs.
* **Existing behavior**: Consumers processed incoming payloads directly without checking if the event was already executed.
* **New behavior**:
  - Created `ProcessedEvent` entity and `ProcessedEventRepository` in both `AccountService` and `TransactionService`.
  - Composite Event IDs format: `<topic_name>:<business_id>` (e.g., `transaction.completed:TX123`, `payment.completed:PAY456`, `verification.required:TX123`).
  - Wrapped each listener in `@Transactional`: Checks `processedEventRepository.existsById(eventId)`. If present, logs a warning and returns immediately. If absent, performs the state change and persists `ProcessedEvent(eventId)` in the same database transaction.
* **Files changed**:
  - `account-service/.../entity/ProcessedEvent.java` [NEW]
  - `account-service/.../repository/ProcessedEventRepository.java` [NEW]
  - `account-service/.../service/AccountEventConsumer.java`
  - `transaction-service/.../entity/ProcessedEvent.java` [NEW]
  - `transaction-service/.../repository/ProcessedEventRepository.java` [NEW]
  - `transaction-service/.../service/TransactionEventConsumer.java`
* **Why this approach**: Database-backed idempotency guarantees ACID transactional consistency. If the balance update fails, the `ProcessedEvent` insert rolls back atomically, ensuring future retries can still proceed.

---

### Issue 4: Razorpay Webhook Security & Idempotency
* **Problem**: `/api/v1/payments/webhook` accepted unverified JSON payloads, allowing unauthorized actors to forge `payment.captured` events and trigger fraudulent credits.
* **Root cause**: No signature verification using the Razorpay HMAC-SHA256 signature header (`X-Razorpay-Signature`).
* **Existing behavior**: Unprotected `Map<String, Object>` parsed and processed directly.
* **New behavior**:
  - Modified `PaymentController` to accept raw request body `String` and `@RequestHeader(value = "X-Razorpay-Signature", required = false) String signature`.
  - In `PaymentService`, uses Razorpay SDK `Utils.verifyWebhookSignature(rawPayload, signature, webhookSecret)` when `razorpay.webhook-secret` is configured.
  - Added idempotency check in `handlePaymentSuccess()`: If payment status is already `COMPLETED`, duplicate webhooks are safely skipped.
  - Security exceptions are caught by `GlobalExceptionHandler` and returned as clean HTTP 401 Unauthorized.
* **Files changed**:
  - `payment-service/.../controller/PaymentController.java`
  - `payment-service/.../service/PaymentService.java`
  - `payment-service/.../exception/GlobalExceptionHandler.java` [NEW]
* **Why this approach**: Standard industry implementation for payment gateway webhooks ensuring cryptographic integrity without rewriting payment business logic.

---

### Issue 5: Fraud Service Communication Review
* **Analysis**: `FraudDetectionService` currently fetches `senderBalance` via Feign client `accountServiceClient.getBalance(accountNumber)`.
* **Evaluation**: In the SAGA flow, before `transaction.initiated` is published, `accountServiceClient.deductBalance()` has already executed.
* **Decision**: **Preserve the synchronous Feign call with documented architectural reasoning (Correctness > Premature Optimization)**:
  1. The balance needed for the fraud check is the *real-time balance at the moment of evaluation*, not a static snapshot.
  2. The Feign client in `fraud-detection-service` is already configured with resilience and OpenFeign dependency management.
  3. Preserving this avoids mutating the event schema across multiple services while ensuring absolute calculation correctness.

---

### Issue 6: OTP Redis Expiration & Spring Data Redis API Fix
* **Problem**: `TransactionEventConsumer` called `redisTemplate.opsForValue().set(otpKey, otp, Expiration.from(5, TimeUnit.MINUTES))`, which failed because `opsForValue().set()` takes `(K, V, long timeout, TimeUnit unit)`.
* **Root cause**: Incorrect method overload usage for Spring Data Redis `ValueOperations`.
* **Existing behavior**: Potential runtime invocation error during OTP generation.
* **New behavior**: Updated to `redisTemplate.opsForValue().set(otpKey, otp, OTP_EXPIRY_MINUTES, TimeUnit.MINUTES);` with guaranteed 5-minute TTL. Added DB-backed event idempotency so duplicate `verification.required` events do not overwrite an active OTP.
* **Files changed**:
  - `transaction-service/.../service/TransactionEventConsumer.java`
* **Why this approach**: Clean native Spring Data Redis API usage with predictable TTL expiration.

---

### Issue 7: Refund / SAGA Compensation Reliability
* **Problem**: If `accountServiceClient.creditBalance()` failed during compensation (e.g. network timeout or Account Service temporary downtime), the transaction state would still be updated as if refunded, resulting in silent financial discrepancies.
* **Root cause**: No error handling or failure capture during SAGA compensation execution.
* **Existing behavior**: `compensateTransaction` assumed synchronous Feign credit always succeeded.
* **New behavior**: Wrapped Feign credit in a `try-catch` block. If the credit fails, the transaction is marked with a detailed failure reason indicating compensation failure requiring operational review, preventing false-positive success notifications.
* **Files changed**:
  - `transaction-service/.../service/TransactionService.java`
* **Why this approach**: Transparent failure recording without introducing complex distributed lock managers.

---

### Issue 8: Global Exception Handling
* **Problem**: Unhandled exceptions exposed raw 500 status codes, stack traces, and internal database details to clients.
* **Root cause**: Missing `@RestControllerAdvice` exception handlers.
* **Existing behavior**: Default Spring Boot error response with stack traces on exceptions.
* **New behavior**: Implemented structured `GlobalExceptionHandler` returning consistent `ErrorResponse` objects with standard HTTP statuses:
  - 400 Bad Request: Validation errors, business rule violations (insufficient balance, inactive account)
  - 401 Unauthorized: Invalid Razorpay webhook signature
  - 404 Not Found: Entity not found (account/transaction/payment)
  - 409 Conflict: Optimistic locking concurrency collisions
  - 502 Bad Gateway: Downstream Feign service failures, Razorpay gateway errors
  - 500 Internal Server Error: Generic unexpected errors
* **Files changed**:
  - `account-service/.../dto/ErrorResponse.java` [NEW]
  - `account-service/.../exception/GlobalExceptionHandler.java` [NEW]
  - `transaction-service/.../dto/ErrorResponse.java` [NEW]
  - `transaction-service/.../exception/GlobalExceptionHandler.java` [NEW]
  - `payment-service/.../dto/ErrorResponse.java` [NEW]
  - `payment-service/.../exception/GlobalExceptionHandler.java` [NEW]

---

## 3. Architecture Impact

| Component | Impact Summary |
|:---|:---|
| **Kafka** | Added consumer for `payment.completed` in Account Service. Topics and payload structures remain 100% backward compatible. |
| **Redis** | Fixed Redis key expiration API in `TransactionEventConsumer`. OTPs expire in 5 minutes. |
| **MySQL** | Added `processed_events` table (auto-created via JPA) in `accountdb` and `transaction_db`. Added `version` column to `accounts` table for optimistic locking. |
| **SAGA Orchestration** | SAGA flow remains intact with enhanced compensation failure tracking and duplicate message safety. |
| **Account Service** | Gains idempotency, optimistic locking, and Razorpay deposit crediting. |
| **Transaction Service** | Gains idempotency for fraud results/OTP generation and robust compensation error handling. |
| **Payment Service** | Gains HMAC-SHA256 signature verification and webhook idempotency. |
| **Fraud Detection Service** | Maintained correct rule execution and balance evaluation. |

---

## 4. Idempotency Explanation

### Why Database-Backed Idempotency is Required for Financial Operations
```text
Kafka Event (e.g. transaction.completed / payment.completed)
                      ↓
           Begin Database Transaction
                      ↓
    Check `processed_events` WHERE event_id = ?
        ├── Found → ROLLBACK & SKIP (Duplicate detected)
        └── Not Found →
                 Credit/Debit Account Balance
                 INSERT INTO `processed_events` (event_id, processed_at)
                 COMMIT Database Transaction
```

1. **Why not Redis-only?**: Redis is an in-memory store. In the event of a Redis restart, eviction, or network partition, idempotency keys could be lost, leading to double-crediting real money.
2. **ACID Transaction Guarantee**: By placing the balance update and the `ProcessedEvent` record inside the same relational database transaction (`@Transactional`), the balance update and the idempotency marker succeed or fail together. If the database crashes mid-operation, both roll back.

---

## 5. Failure Scenarios Matrix

| Scenario | Behavior & Protection Mechanism |
|:---|:---|
| **Account Service Down** | Feign call from Transaction Service fails → Transaction marked `FAILED` immediately before sending Kafka event. Clean 502/Bad Gateway returned. |
| **Kafka Message Duplicated** | Consumer checks `processed_events`. If key exists, logs warning and exits without touching balances. |
| **Consumer Crashes Mid-Processing** | Entire `@Transactional` block rolls back (balance update + event marker). When Kafka redelivers, the event is processed freshly once. |
| **Payment Webhook Duplicated** | Payment Service checks `payment.getStatus() == COMPLETED`. Skips redundant Kafka publication. |
| **Webhook Signature Invalid** | `Utils.verifyWebhookSignature()` rejects request with 401 Unauthorized before any processing occurs. |
| **OTP Expires (5 min)** | Redis key `verification:otp{txId}` expires. `verifyOtp()` detects `storedOtp == null`, executes SAGA compensation (refund), and marks transaction `FLAGGED`. |
| **Compensation/Refund Fails** | Caught in `try-catch`, logged with `CRITICAL`, and recorded on the Transaction record with `SAGA Compensation FAILED (manual intervention needed)`. |
| **Concurrent Balance Update** | JPA `@Version` throws `OptimisticLockingFailureException`. Handled by `GlobalExceptionHandler` returning HTTP 409 Conflict. No money is lost. |

---

## 6. Testing Guide

### Test 1: Normal Transfer
* **POST** `http://localhost:8080/api/v1/transactions/transfer`
```json
{
  "senderAccountNumber": "ACC_1001",
  "receiverAccountNumber": "ACC_1002",
  "amount": 100.00,
  "description": "Lunch"
}
```
* **Expected**: HTTP 201 Created. Sender balance reduced by 100, receiver increased by 100. Transaction status = `COMPLETED`. `processed_events` in `accountdb` receives `transaction.completed:<txId>`.

### Test 2: Fraud Detection & OTP Expiration
* **POST** `http://localhost:8080/api/v1/transactions/transfer` with amount > 90% balance.
* **Expected**: HTTP 201 Created. Transaction status = `PENDING_VERIFICATION`. Redis contains key `verification:otp<txId>` with 300s TTL.
* Wait 5 minutes or submit after expiration:
* **POST** `http://localhost:8080/api/v1/transactions/<txId>/verify?otp=000000`
* **Expected**: Transaction status = `FLAGGED`, sender refunded.

### Test 3: Razorpay Deposit & Account Credit
* **POST** `http://localhost:8080/api/v1/payments/create-order`
```json
{
  "accountNumber": "ACC_1001",
  "amount": 500.00,
  "description": "Wallet top-up"
}
```
* **POST** `http://localhost:8080/api/v1/payments/webhook`
```json
{
  "event": "payment.captured",
  "payload": {
    "payment": {
      "entity": {
        "id": "pay_test_9999",
        "order_id": "<order_id_from_above>",
        "amount": 50000,
        "currency": "INR",
        "status": "captured"
      }
    }
  }
}
```
* **Expected**: HTTP 200 OK. Account balance for `ACC_1001` increases by ₹500.00. `processed_events` in `accountdb` records `payment.completed:<paymentId>`.

### Test 4: Duplicate Kafka Event Simulation
* Resend the identical webhook or publish duplicate `transaction.completed` to Kafka.
* **Expected**: Log shows `Event already processed. Skipping duplicate execution.` Account balance is NOT credited a second time.

### Test 5: Concurrent Balance Modification
* Send 5 concurrent HTTP PUT requests to `/api/v1/accounts/<accountNumber>/deduct?amount=100`.
* **Expected**: Requests that encounter optimistic locking collision receive HTTP 409 Conflict with message `"Concurrent update detected on account"`. Total balance deducted equals exactly `(successful_requests * 100)`.
