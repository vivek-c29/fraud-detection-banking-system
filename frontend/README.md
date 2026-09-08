# Secure Banking Frontend

A Vite + React + Tailwind CSS interface for the existing Digital Banking and Fraud Detection services.

## Setup

```bash
cd frontend
npm install
npm run dev
```

Copy `.env.example` to `.env` if the API Gateway is not running at its default address:

```text
VITE_API_BASE_URL=http://localhost:8080
```

No secrets are required or stored in the frontend.

## Backend requirements

Start Redis, MySQL, ZooKeeper, and Kafka using the repository `docker-compose.yml`, then start the account, transaction, fraud-detection, notification, and API Gateway services. The UI talks only to the gateway at port `8080`.

The API Gateway permits the local Vite development origin `http://localhost:5173`; this is the sole backend configuration addition made for the frontend.

## Routes

| Route | Purpose |
| --- | --- |
| `/` | Look up and display a real account summary. |
| `/accounts/new` | Create an account using the Account Service. |
| `/transfer` | Create a transfer through the gateway. |
| `/payments` | Add money through Razorpay test Checkout. |
| `/transactions` | Show outgoing transfers for a manually entered sender account number. |
| `/transactions/:transactionId` | Show a transaction and live asynchronous status. |
| `/transactions/:transactionId/verify` | Verify a suspicious transfer's OTP. |

## Gateway APIs used

- `GET /api/v1/accounts/{accountNumber}`
- `POST /api/v1/accounts`
- `POST /api/v1/transactions/transfer`
- `GET /api/v1/transactions/{transactionId}`
- `GET /api/v1/transactions/account/{accountNumber}`
- `POST /api/v1/transactions/{transactionId}/verify?otp={otp}`
- `POST /api/v1/payments/create-order`
- `POST /api/v1/payments/verify`

The existing history endpoint returns transactions where the supplied account is the **sender**. The UI labels this accurately as “Outgoing transfers”; it does not show global or recipient-side history.

## Async transaction and OTP behavior

After transfer creation the user is taken to the transaction detail page. The page polls every three seconds only for `INITIATED`, `PENDING`, or `PROCESSING`, and stops for every other status. When the backend changes the status to `PENDING_VERIFICATION`, the user is taken to the OTP screen. OTP expiry, incorrect-attempt handling, and final state are determined entirely by the backend response; the UI never generates, resends, or guesses an OTP.

## Testing checklist

1. Start the infrastructure with `docker compose up -d` from the repository root.
2. Start each Spring Boot service and the API Gateway; confirm the gateway listens on `8080`.
3. Start the frontend with `npm run dev` in this directory.
4. On the dashboard, enter an existing account number and confirm its balance and status match the Account Service.
5. Submit a clean transfer and verify that `PROCESSING` is displayed, then that the page refreshes to the backend final status.
6. Submit a transfer that the fraud service marks suspicious. Confirm the UI moves to OTP verification when the status is `PENDING_VERIFICATION`.
7. Verify with the delivered OTP, then test an incorrect OTP, expiry, and final-attempt failure. Confirm the displayed status and message come from the backend.
8. Test a rejected transfer (for example, insufficient balance) and confirm the backend error message is shown.

## Security note

The backend currently has no authentication/authorization. The account and outgoing-history endpoints are scoped by the manually supplied account number but are not production-secure until backend authorization is introduced.

## Razorpay test payments

The payment service reads its Razorpay credentials from environment variables instead of source-controlled configuration:

```text
RAZORPAY_KEY_ID=rzp_test_...
RAZORPAY_KEY_SECRET=...
RAZORPAY_WEBHOOK_SECRET=...  # optional locally; required for webhook processing
```

The frontend creates an order through the API Gateway, uses only the public key returned with that order to open Razorpay Checkout, then submits Razorpay's payment signature to the Payment Service. The service verifies that signature before publishing the existing `payment.completed` Kafka event. Use Razorpay test data only; never enter real payment details in test mode.
