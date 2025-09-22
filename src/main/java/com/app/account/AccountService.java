package com.app.account;

import com.app.common.exception.BusinessException;
import com.app.common.exception.ResourceNotFoundException;
import com.app.idempotency.IdempotencyRecord;
import com.app.idempotency.IdempotencyRecordRepository;
import com.app.idempotency.IdempotencyStatus;
import com.app.time.TimeProvider;
import com.app.transaction.AccountTransaction;
import com.app.transaction.AccountTransactionRepository;
import com.app.transaction.TransactionType;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
@Transactional
public class AccountService {

    private static final String TRANSFER_OPERATION = "TRANSFER";

    private final AccountRepository accountRepository;
    private final AccountTransactionRepository transactionRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final TimeProvider timeProvider;

    public AccountService(AccountRepository accountRepository, AccountTransactionRepository transactionRepository,
            IdempotencyRecordRepository idempotencyRecordRepository, TimeProvider timeProvider) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.timeProvider = timeProvider;
    }

    public Account createAccount(String accountNumber, String holderName, BigDecimal initialBalance) {
        if (accountRepository.existsByAccountNumber(accountNumber)) {
            throw new BusinessException("El número de cuenta ya existe");
        }
        validateNonNegative(initialBalance, "El saldo inicial debe ser mayor o igual a cero");
        BigDecimal normalized = normalize(initialBalance);
        Account account = new Account(accountNumber, holderName, normalized);
        Account saved = accountRepository.save(account);
        if (normalized.compareTo(BigDecimal.ZERO) > 0) {
            AccountTransaction transaction = new AccountTransaction(null, saved, TransactionType.DEPOSIT, normalized,
                    timeProvider.now());
            transactionRepository.save(transaction);
        }
        return saved;
    }

    public List<Account> getAccounts() {
        return accountRepository.findAll();
    }

    public Account getAccount(String accountNumber) {
        return findAccount(accountNumber);
    }

    public Account deposit(String accountNumber, BigDecimal amount) {
        validatePositive(amount, "El monto del depósito debe ser mayor que cero");
        BigDecimal normalized = normalize(amount);
        Account account = findAccount(accountNumber);
        account.deposit(normalized);
        Account updated = accountRepository.save(account);
        AccountTransaction transaction = new AccountTransaction(null, updated, TransactionType.DEPOSIT, normalized,
                timeProvider.now());
        transactionRepository.save(transaction);
        return updated;
    }

    public Account withdraw(String accountNumber, BigDecimal amount) {
        validatePositive(amount, "El monto del retiro debe ser mayor que cero");
        BigDecimal normalized = normalize(amount);
        Account account = findAccount(accountNumber);
        if (account.getBalance().compareTo(normalized) < 0) {
            throw new BusinessException("Saldo insuficiente para el retiro");
        }
        account.withdraw(normalized);
        Account updated = accountRepository.save(account);
        AccountTransaction transaction = new AccountTransaction(updated, null, TransactionType.WITHDRAWAL, normalized,
                timeProvider.now());
        transactionRepository.save(transaction);
        return updated;
    }

    public void transfer(String sourceAccountNumber, String targetAccountNumber, BigDecimal amount) {
        transfer(sourceAccountNumber, targetAccountNumber, amount, null);
    }

    public void transfer(String sourceAccountNumber, String targetAccountNumber, BigDecimal amount,
            String idempotencyKey) {
        if (sourceAccountNumber.equals(targetAccountNumber)) {
            throw new BusinessException("La cuenta origen y destino deben ser diferentes");
        }
        validatePositive(amount, "El monto de la transferencia debe ser mayor que cero");
        BigDecimal normalized = normalize(amount);
        Optional<IdempotencyRecord> existingRecord = Optional.empty();
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            existingRecord = idempotencyRecordRepository.findByIdempotencyKey(idempotencyKey);
            if (existingRecord.isPresent()) {
                IdempotencyRecord record = existingRecord.get();
                if (record.getStatus() == IdempotencyStatus.SUCCESS) {
                    return;
                }
                if (record.getStatus() == IdempotencyStatus.FAILED) {
                    throw new BusinessException("Operación previamente fallida");
                }
                record.refreshUpdatedAt(timeProvider.now());
                idempotencyRecordRepository.save(record);
            } else {
                IdempotencyRecord newRecord = new IdempotencyRecord(idempotencyKey, TRANSFER_OPERATION, normalized,
                        sourceAccountNumber, targetAccountNumber, timeProvider.now());
                existingRecord = Optional.of(idempotencyRecordRepository.save(newRecord));
            }
        }
        Account source = findAccount(sourceAccountNumber);
        Account target = findAccount(targetAccountNumber);
        if (source.getBalance().compareTo(normalized) < 0) {
            existingRecord.ifPresent(record -> {
                record.markFailed("Saldo insuficiente", timeProvider.now());
                idempotencyRecordRepository.save(record);
            });
            throw new BusinessException("Saldo insuficiente para la transferencia");
        }
        source.withdraw(normalized);
        target.deposit(normalized);
        accountRepository.save(source);
        accountRepository.save(target);
        AccountTransaction transaction = new AccountTransaction(source, target, TransactionType.TRANSFER, normalized,
                timeProvider.now());
        AccountTransaction persisted = transactionRepository.save(transaction);
        existingRecord.ifPresent(record -> {
            record.markSuccess(persisted.getId(), timeProvider.now());
            idempotencyRecordRepository.save(record);
        });
    }

    public List<AccountTransaction> getTransactions(String accountNumber) {
        Account account = findAccount(accountNumber);
        return transactionRepository.findByAccount(account);
    }

    private Account findAccount(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Cuenta no encontrada"));
    }

    private void validatePositive(BigDecimal amount, String message) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(message);
        }
    }

    private void validateNonNegative(BigDecimal amount, String message) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(message);
        }
    }

    private BigDecimal normalize(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }
}
