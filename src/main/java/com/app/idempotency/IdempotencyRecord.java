package com.app.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 128)
    private String idempotencyKey;

    @Column(name = "operation_type", nullable = false, length = 32)
    private String operationType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private IdempotencyStatus status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "source_account_number", length = 64)
    private String sourceAccountNumber;

    @Column(name = "target_account_number", length = 64)
    private String targetAccountNumber;

    @Column(name = "transaction_id")
    private Long transactionId;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    protected IdempotencyRecord() {
        // JPA requirement
    }

    public IdempotencyRecord(String idempotencyKey, String operationType, BigDecimal amount,
            String sourceAccountNumber, String targetAccountNumber, LocalDateTime createdAt) {
        this.idempotencyKey = idempotencyKey;
        this.operationType = operationType;
        this.amount = amount;
        this.sourceAccountNumber = sourceAccountNumber;
        this.targetAccountNumber = targetAccountNumber;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.status = IdempotencyStatus.IN_PROGRESS;
    }

    public Long getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getOperationType() {
        return operationType;
    }

    public IdempotencyStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getSourceAccountNumber() {
        return sourceAccountNumber;
    }

    public String getTargetAccountNumber() {
        return targetAccountNumber;
    }

    public Long getTransactionId() {
        return transactionId;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void markSuccess(Long transactionId, LocalDateTime at) {
        this.status = IdempotencyStatus.SUCCESS;
        this.transactionId = transactionId;
        this.updatedAt = at;
        this.errorMessage = null;
    }

    public void markFailed(String errorMessage, LocalDateTime at) {
        this.status = IdempotencyStatus.FAILED;
        this.updatedAt = at;
        this.errorMessage = errorMessage;
    }

    public void refreshUpdatedAt(LocalDateTime at) {
        this.updatedAt = at;
    }
}
