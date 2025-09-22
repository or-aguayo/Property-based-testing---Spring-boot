package com.app.account;

import com.app.common.exception.BusinessException;
import com.app.common.exception.ResourceNotFoundException;
import com.app.transaction.AccountTransaction;
import com.app.transaction.AccountTransactionRepository;
import com.app.transaction.TransactionType;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
@Transactional
public class AccountService {

    private final AccountRepository accountRepository;
    private final AccountTransactionRepository transactionRepository;

    public AccountService(AccountRepository accountRepository, AccountTransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    public Account createAccount(String accountNumber, String holderName, BigDecimal initialBalance) {
        if (accountRepository.existsByAccountNumber(accountNumber)) {
            throw new BusinessException("El número de cuenta ya existe");
        }
        validateNonNegative(initialBalance, "El saldo inicial debe ser mayor o igual a cero");
        Account account = new Account(accountNumber, holderName, initialBalance);
        Account saved = accountRepository.save(account);
        if (initialBalance.compareTo(BigDecimal.ZERO) > 0) {
            AccountTransaction transaction = new AccountTransaction(null, saved, TransactionType.DEPOSIT, initialBalance,
                    LocalDateTime.now());
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
        Account account = findAccount(accountNumber);
        account.deposit(amount);
        Account updated = accountRepository.save(account);
        AccountTransaction transaction = new AccountTransaction(null, updated, TransactionType.DEPOSIT, amount,
                LocalDateTime.now());
        transactionRepository.save(transaction);
        return updated;
    }

    public Account withdraw(String accountNumber, BigDecimal amount) {
        validatePositive(amount, "El monto del retiro debe ser mayor que cero");
        Account account = findAccount(accountNumber);
        if (account.getBalance().compareTo(amount) < 0) {
            throw new BusinessException("Saldo insuficiente para el retiro");
        }
        account.withdraw(amount);
        Account updated = accountRepository.save(account);
        AccountTransaction transaction = new AccountTransaction(updated, null, TransactionType.WITHDRAWAL, amount,
                LocalDateTime.now());
        transactionRepository.save(transaction);
        return updated;
    }

    public void transfer(String sourceAccountNumber, String targetAccountNumber, BigDecimal amount) {
        if (sourceAccountNumber.equals(targetAccountNumber)) {
            throw new BusinessException("La cuenta origen y destino deben ser diferentes");
        }
        validatePositive(amount, "El monto de la transferencia debe ser mayor que cero");
        Account source = findAccount(sourceAccountNumber);
        Account target = findAccount(targetAccountNumber);
        if (source.getBalance().compareTo(amount) < 0) {
            throw new BusinessException("Saldo insuficiente para la transferencia");
        }
        source.withdraw(amount);
        target.deposit(amount);
        accountRepository.save(source);
        accountRepository.save(target);
        AccountTransaction transaction = new AccountTransaction(source, target, TransactionType.TRANSFER, amount,
                LocalDateTime.now());
        transactionRepository.save(transaction);
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
}
