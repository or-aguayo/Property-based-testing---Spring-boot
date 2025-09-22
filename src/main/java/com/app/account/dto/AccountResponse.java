package com.app.account.dto;

import java.math.BigDecimal;

public class AccountResponse {

    private final String accountNumber;
    private final String holderName;
    private final BigDecimal balance;

    public AccountResponse(String accountNumber, String holderName, BigDecimal balance) {
        this.accountNumber = accountNumber;
        this.holderName = holderName;
        this.balance = balance;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public String getHolderName() {
        return holderName;
    }

    public BigDecimal getBalance() {
        return balance;
    }
}
