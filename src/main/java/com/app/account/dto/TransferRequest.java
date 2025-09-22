package com.app.account.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public class TransferRequest {

    @NotBlank(message = "La cuenta origen es obligatoria")
    private String sourceAccountNumber;

    @NotBlank(message = "La cuenta destino es obligatoria")
    private String destinationAccountNumber;

    @NotNull(message = "El monto es obligatorio")
    @DecimalMin(value = "0.0", inclusive = false, message = "El monto debe ser mayor que cero")
    private BigDecimal amount;

    @Size(max = 128, message = "La idempotency-key no puede superar 128 caracteres")
    private String idempotencyKey;

    public String getSourceAccountNumber() {
        return sourceAccountNumber;
    }

    public void setSourceAccountNumber(String sourceAccountNumber) {
        this.sourceAccountNumber = sourceAccountNumber;
    }

    public String getDestinationAccountNumber() {
        return destinationAccountNumber;
    }

    public void setDestinationAccountNumber(String destinationAccountNumber) {
        this.destinationAccountNumber = destinationAccountNumber;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }
}
