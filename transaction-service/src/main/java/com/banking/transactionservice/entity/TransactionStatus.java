package com.banking.transactionservice.entity;


public enum TransactionStatus {
    INITIATED,
    PENDING,
    PROCESSING,
    COMPLETED,
    PENDING_VERIFICATION,
    FAILED,
    FLAGGED
}
