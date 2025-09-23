package com.app.account;

import com.app.common.exception.BusinessException;
import com.app.transaction.AccountTransaction;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Transactional
class AccountServiceTest {

    @Autowired
    private AccountService accountService;

    @Test
    void depositWithdrawAndTransferUpdateBalancesAndHistory() {
        // Escenario integral: se crea una cuenta con saldo inicial, se realizan depósito, retiro y transferencia
        // y se verifica que los saldos y los historiales registrados coinciden con lo esperado paso a paso.
        Account source = accountService.createAccount("ACC-100", "John Doe", BigDecimal.valueOf(100));
        Account target = accountService.createAccount("ACC-200", "Jane Doe", BigDecimal.ZERO);

        Account afterDeposit = accountService.deposit(source.getAccountNumber(), BigDecimal.valueOf(50));
        assertThat(afterDeposit.getBalance()).isEqualByComparingTo("150");

        Account afterWithdraw = accountService.withdraw(source.getAccountNumber(), BigDecimal.valueOf(40));
        assertThat(afterWithdraw.getBalance()).isEqualByComparingTo("110");

        accountService.transfer(source.getAccountNumber(), target.getAccountNumber(), BigDecimal.valueOf(30));

        Account updatedSource = accountService.getAccount(source.getAccountNumber());
        Account updatedTarget = accountService.getAccount(target.getAccountNumber());

        assertThat(updatedSource.getBalance()).isEqualByComparingTo("80");
        assertThat(updatedTarget.getBalance()).isEqualByComparingTo("30");

        List<AccountTransaction> sourceHistory = accountService.getTransactions(source.getAccountNumber());
        assertThat(sourceHistory).hasSize(4);
        assertThat(sourceHistory.getFirst().getType()).isNotNull();

        List<AccountTransaction> targetHistory = accountService.getTransactions(target.getAccountNumber());
        assertThat(targetHistory).hasSize(1);
        assertThat(targetHistory.getFirst().getAmount()).isEqualByComparingTo("30");
    }

    @Test
    void withdrawWithInsufficientFundsThrowsException() {
        // Prueba negativa puntual: un retiro mayor que el saldo debe fallar lanzando BusinessException.
        accountService.createAccount("ACC-300", "No Funds", BigDecimal.ZERO);
        assertThrows(BusinessException.class,
                () -> accountService.withdraw("ACC-300", BigDecimal.valueOf(10)));
    }
}
