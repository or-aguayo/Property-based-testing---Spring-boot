package com.app.account.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class TransactionResponse {

    private final String type;
    private final BigDecimal amount;
    private final LocalDateTime occurredAt;
    private final String sourceAccountNumber;
    private final String destinationAccountNumber;

    public TransactionResponse(String type, BigDecimal amount, LocalDateTime occurredAt, String sourceAccountNumber,
            String destinationAccountNumber) {
        this.type = type;
        this.amount = amount;
        this.occurredAt = occurredAt;
        this.sourceAccountNumber = sourceAccountNumber;
        this.destinationAccountNumber = destinationAccountNumber;
    }

    public String getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public String getSourceAccountNumber() {
        return sourceAccountNumber;
    }

    public String getDestinationAccountNumber() {
        return destinationAccountNumber;
    }
}
