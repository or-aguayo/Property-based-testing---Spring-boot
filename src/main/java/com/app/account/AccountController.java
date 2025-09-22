package com.app.account;

import com.app.account.dto.AccountResponse;
import com.app.account.dto.AmountRequest;
import com.app.account.dto.CreateAccountRequest;
import com.app.account.dto.TransactionResponse;
import com.app.account.dto.TransferRequest;
import com.app.transaction.AccountTransaction;
import jakarta.validation.Valid;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        Account account = accountService.createAccount(request.getAccountNumber(), request.getHolderName(),
                request.getInitialBalance());
        return ResponseEntity.status(HttpStatus.CREATED).body(toAccountResponse(account));
    }

    @GetMapping
    public List<AccountResponse> getAccounts() {
        return accountService.getAccounts().stream()
                .map(this::toAccountResponse)
                .collect(Collectors.toList());
    }

    @GetMapping("/{accountNumber}")
    public AccountResponse getAccount(@PathVariable String accountNumber) {
        return toAccountResponse(accountService.getAccount(accountNumber));
    }

    @PostMapping("/{accountNumber}/deposit")
    public AccountResponse deposit(@PathVariable String accountNumber, @Valid @RequestBody AmountRequest request) {
        return toAccountResponse(accountService.deposit(accountNumber, request.getAmount()));
    }

    @PostMapping("/{accountNumber}/withdraw")
    public AccountResponse withdraw(@PathVariable String accountNumber, @Valid @RequestBody AmountRequest request) {
        return toAccountResponse(accountService.withdraw(accountNumber, request.getAmount()));
    }

    @PostMapping("/transfer")
    public ResponseEntity<Void> transfer(@Valid @RequestBody TransferRequest request) {
        accountService.transfer(request.getSourceAccountNumber(), request.getDestinationAccountNumber(),
                request.getAmount());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{accountNumber}/transactions")
    public List<TransactionResponse> getTransactions(@PathVariable String accountNumber) {
        return accountService.getTransactions(accountNumber).stream()
                .map(this::toTransactionResponse)
                .collect(Collectors.toList());
    }

    private AccountResponse toAccountResponse(Account account) {
        return new AccountResponse(account.getAccountNumber(), account.getHolderName(), account.getBalance());
    }

    private TransactionResponse toTransactionResponse(AccountTransaction transaction) {
        String source = transaction.getSourceAccount() != null ? transaction.getSourceAccount().getAccountNumber() : null;
        String target = transaction.getTargetAccount() != null ? transaction.getTargetAccount().getAccountNumber() : null;
        return new TransactionResponse(transaction.getType().name(), transaction.getAmount(),
                transaction.getOccurredAt(), source, target);
    }
}
