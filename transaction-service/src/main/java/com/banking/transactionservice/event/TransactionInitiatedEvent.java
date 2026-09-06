package com.banking.transactionservice.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TransactionInitiatedEvent {
    private String transactionId;
    private String senderAccountNumber;
    private String receiverAccountNumber;
    private BigDecimal amount;
    private String description;
}
