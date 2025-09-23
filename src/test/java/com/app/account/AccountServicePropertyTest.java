package com.app.account;

import com.app.AppApplication;
import com.app.common.exception.BusinessException;
import com.app.idempotency.IdempotencyRecordRepository;
import com.app.transaction.AccountTransaction;
import com.app.transaction.AccountTransactionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.statistics.Statistics;
import org.assertj.core.api.Assertions;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

class AccountServicePropertyTest {

    private static final AtomicInteger ACCOUNT_SEQUENCE = new AtomicInteger();

    private static ConfigurableApplicationContext context;
    private static AccountService accountService;
    private static AccountRepository accountRepository;
    private static AccountTransactionRepository transactionRepository;
    private static IdempotencyRecordRepository idempotencyRecordRepository;

    @BeforeContainer
    static void loadSpringContext() {
        if (context == null) {
            // Inicializamos la aplicación exactamente igual que en producción para reutilizar todos los beans reales.
            context = new SpringApplicationBuilder(AppApplication.class)
                    .properties(Map.of("server.port", "0"))
                    .run();
        }
    }

    @AfterContainer
    static void closeSpringContext() {
        if (context != null) {
            // Cerramos el contexto al finalizar todas las propiedades para liberar conexiones y limpiar la memoria.
            context.close();
            context = null;
            accountService = null;
            accountRepository = null;
            transactionRepository = null;
            idempotencyRecordRepository = null;
        }
    }

    @BeforeContainer
    void loadSpringContext() {
        if (context == null) {
            // Inicializamos la aplicación exactamente igual que en producción para reutilizar todos los beans reales.
            context = new SpringApplicationBuilder(AppApplication.class)
                    .properties(Map.of("server.port", "0"))
                    .run();
        }

        // Recuperamos los beans necesarios a partir del contexto arrancado una sola vez para todo el conjunto de propiedades.
        accountService = context.getBean(AccountService.class);
        accountRepository = context.getBean(AccountRepository.class);
        transactionRepository = context.getBean(AccountTransactionRepository.class);
        idempotencyRecordRepository = context.getBean(IdempotencyRecordRepository.class);
    }

    @AfterContainer
    void closeSpringContext() {
        if (context != null) {
            // Cerramos el contexto al finalizar todas las propiedades para liberar conexiones y limpiar la memoria.
            context.close();
            context = null;
        }
    }

    @BeforeTry
    void cleanState() {
        // Aseguramos que los beans hayan sido recuperados del contexto compartido antes de interactuar con la base de datos.
        ensureDependenciesLoaded();
        // Eliminamos cualquier rastro de datos generado en pruebas anteriores antes de cada intento de propiedad.
        transactionRepository.deleteAll();
        idempotencyRecordRepository.deleteAll();
        accountRepository.deleteAll();
    }

    private void ensureDependenciesLoaded() {
        if (accountService == null) {
            // Recuperamos los beans necesarios a partir del contexto arrancado una sola vez para todo el conjunto de propiedades.
            accountService = context.getBean(AccountService.class);
            accountRepository = context.getBean(AccountRepository.class);
            transactionRepository = context.getBean(AccountTransactionRepository.class);
            idempotencyRecordRepository = context.getBean(IdempotencyRecordRepository.class);
        }
    }

    @Provide
    Arbitrary<BigDecimal> nonNegativeBalances() {
        return monetaryAmount(BigDecimal.ZERO, new BigDecimal("1000000"), 0, 2);
    }

    @Provide
    Arbitrary<BigDecimal> positiveAmounts() {
        Arbitrary<BigDecimal> small = monetaryAmount(new BigDecimal("0.01"), new BigDecimal("100"), 0, 2);
        Arbitrary<BigDecimal> medium = monetaryAmount(new BigDecimal("100.01"), new BigDecimal("10000"), 0, 2);
        Arbitrary<BigDecimal> large = monetaryAmount(new BigDecimal("10000.01"), new BigDecimal("1000000"), 0, 2);
        return Arbitraries.oneOf(small, medium, large);
    }

    @Provide
    Arbitrary<BigDecimal> variableScaleAmounts() {
        // Para cantidades con más precisión forzamos a que la escala mínima permita representar 0.0001 sin errores.
        return monetaryAmount(new BigDecimal("0.0001"), new BigDecimal("1000000"), 4, 6);
    }

    @Provide
    Arbitrary<List<BigDecimal>> depositSequences() {
        return Arbitraries.integers().between(1, 5)
                .flatMap(size -> positiveAmounts().list().ofSize(size));
    }

    @Provide
    Arbitrary<List<AccountOperation>> operationSequences() {
        return Arbitraries.integers().between(1, 12)
                .flatMap(length -> operationArbitrary().list().ofSize(length));
    }

    private Arbitrary<AccountOperation> operationArbitrary() {
        Arbitrary<AccountOperationType> type = Arbitraries.of(AccountOperationType.values());
        Arbitrary<BigDecimal> amount = monetaryAmount(new BigDecimal("0.01"), new BigDecimal("5000"), 0, 2);
        return Combinators.combine(type, amount).as(AccountOperation::new);
    }

    @Property(tries = 50)
    @Label("1.1 Depósito aumenta saldo y nunca negativo")
    void depositAlwaysIncreasesBalance(@ForAll("nonNegativeBalances") BigDecimal initialBalance,
            @ForAll("positiveAmounts") BigDecimal deposit) {
        // Creamos una cuenta de prueba y aplicamos un depósito para comprobar que el saldo solo puede crecer.
        Account account = accountService.createAccount(nextAccountNumber(), "Holder", initialBalance);
        Account afterDeposit = accountService.deposit(account.getAccountNumber(), deposit);

        BigDecimal expected = normalize(initialBalance.add(deposit));
        Assertions.assertThat(afterDeposit.getBalance()).isEqualByComparingTo(expected);
        Assertions.assertThat(afterDeposit.getBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    @Property(tries = 50)
    @Label("1.2 Retiros nunca dejan saldo negativo")
    void withdrawalsRespectAvailableBalance(@ForAll("nonNegativeBalances") BigDecimal initialBalance,
            @ForAll("positiveAmounts") BigDecimal withdrawal) {
        Account account = accountService.createAccount(nextAccountNumber(), "Holder", initialBalance);
        BigDecimal before = accountService.getAccount(account.getAccountNumber()).getBalance();
        if (before.compareTo(withdrawal) >= 0) {
            Account afterWithdraw = accountService.withdraw(account.getAccountNumber(), withdrawal);
            BigDecimal expected = normalize(initialBalance.subtract(withdrawal));
            Assertions.assertThat(afterWithdraw.getBalance()).isEqualByComparingTo(expected);
        } else {
            Assertions.assertThatThrownBy(
                    () -> accountService.withdraw(account.getAccountNumber(), withdrawal))
                    .isInstanceOf(BusinessException.class);
            Account after = accountService.getAccount(account.getAccountNumber());
            Assertions.assertThat(after.getBalance()).isEqualByComparingTo(before);
        }
    }

    @Property(tries = 25)
    @Label("1.3 Consulta es idempotente")
    void balanceLookupIsIdempotent(@ForAll("nonNegativeBalances") BigDecimal initialBalance) {
        Account account = accountService.createAccount(nextAccountNumber(), "Holder", initialBalance);
        Account firstRead = accountService.getAccount(account.getAccountNumber());
        Account secondRead = accountService.getAccount(account.getAccountNumber());
        Assertions.assertThat(firstRead.getBalance()).isEqualByComparingTo(secondRead.getBalance());
    }

    @Property(tries = 50)
    @Label("1.4 Escala monetaria consistente")
    void monetaryScaleIsConsistent(@ForAll("nonNegativeBalances") BigDecimal initialBalance,
            @ForAll("variableScaleAmounts") BigDecimal amount) {
        Account account = accountService.createAccount(nextAccountNumber(), "Holder", initialBalance);
        Account afterDeposit = accountService.deposit(account.getAccountNumber(), amount);
        BigDecimal expected = normalize(initialBalance.add(amount));
        Assertions.assertThat(afterDeposit.getBalance()).isEqualByComparingTo(expected);

        if (afterDeposit.getBalance().compareTo(amount) >= 0) {
            Account afterWithdraw = accountService.withdraw(account.getAccountNumber(), amount);
            BigDecimal expectedWithdraw = normalize(expected.subtract(amount));
            Assertions.assertThat(afterWithdraw.getBalance()).isEqualByComparingTo(expectedWithdraw);
        }
    }

    @Property(tries = 40)
    @Label("2.1 Conservación de dinero en transferencias")
    void transferConservesTotal(@ForAll("nonNegativeBalances") BigDecimal sourceBalance,
            @ForAll("nonNegativeBalances") BigDecimal targetBalance,
            @ForAll("positiveAmounts") BigDecimal amount) {
        // Se crean dos cuentas y se registra el saldo total para verificar que una transferencia no crea ni destruye dinero.
        Account source = accountService.createAccount(nextAccountNumber(), "Source", sourceBalance);
        Account target = accountService.createAccount(nextAccountNumber(), "Target", targetBalance);

        BigDecimal totalBefore = accountService.getAccount(source.getAccountNumber()).getBalance()
                .add(accountService.getAccount(target.getAccountNumber()).getBalance());

        try {
            accountService.transfer(source.getAccountNumber(), target.getAccountNumber(), amount);
            BigDecimal totalAfter = accountService.getAccount(source.getAccountNumber()).getBalance()
                    .add(accountService.getAccount(target.getAccountNumber()).getBalance());
            Assertions.assertThat(totalAfter).isEqualByComparingTo(normalize(totalBefore));
        } catch (BusinessException ex) {
            BigDecimal totalAfter = accountService.getAccount(source.getAccountNumber()).getBalance()
                    .add(accountService.getAccount(target.getAccountNumber()).getBalance());
            Assertions.assertThat(totalAfter).isEqualByComparingTo(totalBefore);
        }
    }

    @Property(tries = 40)
    @Label("2.2 No negatividad tras transferencias")
    void transferNeverCreatesNegativeBalances(@ForAll("nonNegativeBalances") BigDecimal sourceBalance,
            @ForAll("nonNegativeBalances") BigDecimal targetBalance,
            @ForAll("positiveAmounts") BigDecimal amount) {
        Account source = accountService.createAccount(nextAccountNumber(), "Source", sourceBalance);
        Account target = accountService.createAccount(nextAccountNumber(), "Target", targetBalance);

        try {
            accountService.transfer(source.getAccountNumber(), target.getAccountNumber(), amount);
            Assertions.assertThat(accountService.getAccount(source.getAccountNumber()).getBalance())
                    .isGreaterThanOrEqualTo(BigDecimal.ZERO);
            Assertions.assertThat(accountService.getAccount(target.getAccountNumber()).getBalance())
                    .isGreaterThanOrEqualTo(BigDecimal.ZERO);
        } catch (BusinessException ex) {
            Assertions.assertThat(accountService.getAccount(source.getAccountNumber()).getBalance())
                    .isEqualByComparingTo(sourceBalance.setScale(2, RoundingMode.HALF_UP));
            Assertions.assertThat(accountService.getAccount(target.getAccountNumber()).getBalance())
                    .isEqualByComparingTo(targetBalance.setScale(2, RoundingMode.HALF_UP));
        }
    }

    @Property(tries = 30)
    @Label("2.3 Idempotencia de transferencias por idempotency-key")
    void transferHonoursIdempotencyKey(@ForAll("nonNegativeBalances") BigDecimal sourceBalance,
            @ForAll("nonNegativeBalances") BigDecimal targetBalance,
            @ForAll("positiveAmounts") BigDecimal amount) {
        Account source = accountService.createAccount(nextAccountNumber(), "Source", sourceBalance);
        Account target = accountService.createAccount(nextAccountNumber(), "Target", targetBalance);
        String key = UUID.randomUUID().toString();

        boolean success = true;
        try {
            accountService.transfer(source.getAccountNumber(), target.getAccountNumber(), amount, key);
        } catch (BusinessException ex) {
            success = false;
        }

        BigDecimal sourceAfterFirst = accountService.getAccount(source.getAccountNumber()).getBalance();
        BigDecimal targetAfterFirst = accountService.getAccount(target.getAccountNumber()).getBalance();

        if (success) {
            accountService.transfer(source.getAccountNumber(), target.getAccountNumber(), amount, key);
            BigDecimal sourceAfterSecond = accountService.getAccount(source.getAccountNumber()).getBalance();
            BigDecimal targetAfterSecond = accountService.getAccount(target.getAccountNumber()).getBalance();
            Assertions.assertThat(sourceAfterSecond).isEqualByComparingTo(sourceAfterFirst);
            Assertions.assertThat(targetAfterSecond).isEqualByComparingTo(targetAfterFirst);
        } else {
            Assertions.assertThatThrownBy(
                    () -> accountService.transfer(source.getAccountNumber(), target.getAccountNumber(), amount, key))
                    .isInstanceOf(BusinessException.class);
            BigDecimal sourceAfterSecond = accountService.getAccount(source.getAccountNumber()).getBalance();
            BigDecimal targetAfterSecond = accountService.getAccount(target.getAccountNumber()).getBalance();
            Assertions.assertThat(sourceAfterSecond).isEqualByComparingTo(sourceAfterFirst);
            Assertions.assertThat(targetAfterSecond).isEqualByComparingTo(targetAfterFirst);
        }
    }

    @Property(tries = 25)
    @Label("3.1 Conmutatividad de depósitos")
    void depositOrderDoesNotMatter(@ForAll("nonNegativeBalances") BigDecimal initialBalance,
            @ForAll("depositSequences") List<BigDecimal> amounts) {
        Account account = accountService.createAccount(nextAccountNumber(), "Holder", initialBalance);
        List<BigDecimal> shuffled = new ArrayList<>(amounts);
        List<BigDecimal> reversed = new ArrayList<>(amounts);
        java.util.Collections.shuffle(shuffled);
        java.util.Collections.reverse(reversed);

        BigDecimal expected = normalize(initialBalance
                .add(amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add)));

        for (BigDecimal amount : shuffled) {
            accountService.deposit(account.getAccountNumber(), amount);
        }
        BigDecimal afterShuffled = accountService.getAccount(account.getAccountNumber()).getBalance();

        cleanState();
        account = accountService.createAccount(nextAccountNumber(), "Holder", initialBalance);
        for (BigDecimal amount : reversed) {
            accountService.deposit(account.getAccountNumber(), amount);
        }
        BigDecimal afterReversed = accountService.getAccount(account.getAccountNumber()).getBalance();

        Assertions.assertThat(afterShuffled).isEqualByComparingTo(expected);
        Assertions.assertThat(afterReversed).isEqualByComparingTo(expected);
    }

    @Property(tries = 30)
    @Label("3.2 Depositar y retirar vs retirar y depositar")
    void depositWithdrawSequencesBehaveAsExpected(@ForAll("nonNegativeBalances") BigDecimal initialBalance,
            @ForAll("positiveAmounts") BigDecimal deposit,
            @ForAll("positiveAmounts") BigDecimal withdrawal) {
        Account accountOne = accountService.createAccount(nextAccountNumber(), "Holder", initialBalance);
        accountService.deposit(accountOne.getAccountNumber(), deposit);
        BigDecimal postDeposit = accountService.getAccount(accountOne.getAccountNumber()).getBalance();
        try {
            accountService.withdraw(accountOne.getAccountNumber(), withdrawal);
        } catch (BusinessException ignored) {
            // ignore
        }
        BigDecimal finalOne = accountService.getAccount(accountOne.getAccountNumber()).getBalance();

        cleanState();
        Account accountTwo = accountService.createAccount(nextAccountNumber(), "Holder", initialBalance);
        try {
            accountService.withdraw(accountTwo.getAccountNumber(), withdrawal);
            accountService.deposit(accountTwo.getAccountNumber(), deposit);
            BigDecimal expected = normalize(initialBalance.subtract(withdrawal).add(deposit));
            Assertions.assertThat(accountService.getAccount(accountTwo.getAccountNumber()).getBalance())
                    .isEqualByComparingTo(expected);
        } catch (BusinessException ex) {
            accountService.deposit(accountTwo.getAccountNumber(), deposit);
            Assertions.assertThat(accountService.getAccount(accountTwo.getAccountNumber()).getBalance())
                    .isEqualByComparingTo(postDeposit);
        }

        Assertions.assertThat(finalOne)
                .isEqualByComparingTo(accountService.getAccount(accountTwo.getAccountNumber()).getBalance());
    }

    @Property(tries = 30)
    @Label("3.3 Transferencias reversibles")
    void transferIsReversible(@ForAll("nonNegativeBalances") BigDecimal sourceBalance,
            @ForAll("nonNegativeBalances") BigDecimal targetBalance,
            @ForAll("positiveAmounts") BigDecimal amount) {
        Account source = accountService.createAccount(nextAccountNumber(), "Source", sourceBalance);
        Account target = accountService.createAccount(nextAccountNumber(), "Target", targetBalance);

        try {
            accountService.transfer(source.getAccountNumber(), target.getAccountNumber(), amount);
            accountService.transfer(target.getAccountNumber(), source.getAccountNumber(), amount);
        } catch (BusinessException ex) {
            // ignore invalid transfer attempts
        }

        Assertions.assertThat(accountService.getAccount(source.getAccountNumber()).getBalance())
                .isEqualByComparingTo(sourceBalance.setScale(2, RoundingMode.HALF_UP));
        Assertions.assertThat(accountService.getAccount(target.getAccountNumber()).getBalance())
                .isEqualByComparingTo(targetBalance.setScale(2, RoundingMode.HALF_UP));
    }

    @Property(tries = 15)
    @Label("4.1 Secuencias aleatorias mantienen invariantes")
    void randomSequencesMaintainInvariants(@ForAll("nonNegativeBalances") BigDecimal baseBalance,
            @ForAll("operationSequences") List<AccountOperation> operations) {
        List<Account> accounts = createAccounts(3, baseBalance);
        Map<String, BigDecimal> model = accounts.stream().collect(Collectors.toMap(Account::getAccountNumber,
                account -> account.getBalance()));

        BigDecimal systemTotal = accounts.stream().map(Account::getBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        for (AccountOperation operation : operations) {
            Account account = accounts.get(operation.index(accounts.size()));
            switch (operation.type()) {
                case DEPOSIT -> {
                    accountService.deposit(account.getAccountNumber(), operation.amount());
                    model.compute(account.getAccountNumber(), (k, v) -> normalize(v.add(operation.amount())));
                    systemTotal = normalize(systemTotal.add(operation.amount()));
                }
                case WITHDRAW -> {
                    try {
                        accountService.withdraw(account.getAccountNumber(), operation.amount());
                        model.compute(account.getAccountNumber(), (k, v) -> normalize(v.subtract(operation.amount())));
                        systemTotal = normalize(systemTotal.subtract(operation.amount()));
                    } catch (BusinessException ignored) {
                    }
                }
                case TRANSFER -> {
                    Account target = accounts.get(operation.otherIndex(accounts.indexOf(account), accounts.size()));
                    try {
                        accountService.transfer(account.getAccountNumber(), target.getAccountNumber(),
                                operation.amount());
                        model.compute(account.getAccountNumber(), (k, v) -> normalize(v.subtract(operation.amount())));
                        model.compute(target.getAccountNumber(), (k, v) -> normalize(v.add(operation.amount())));
                    } catch (BusinessException ignored) {
                    }
                }
            }

            List<Account> persisted = accountRepository.findAll();
            persisted.forEach(persistedAccount -> Assertions.assertThat(persistedAccount.getBalance())
                    .isGreaterThanOrEqualTo(BigDecimal.ZERO));
            BigDecimal totalPersisted = persisted.stream().map(Account::getBalance)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            Assertions.assertThat(totalPersisted).isEqualByComparingTo(systemTotal);
        }
    }

    @Property(tries = 10)
    @Label("4.2 Invariantes en permutaciones de operaciones")
    void permutationsMaintainInvariants(@ForAll("operationSequences") List<AccountOperation> operations) {
        List<Account> accounts = createAccounts(3, new BigDecimal("100.00"));
        List<List<AccountOperation>> permutations = generatePermutations(operations.stream().limit(3)
                .collect(Collectors.toList()));

        for (List<AccountOperation> permutation : permutations) {
            cleanState();
            accounts = createAccounts(3, new BigDecimal("100.00"));
            BigDecimal total = accounts.stream().map(Account::getBalance).reduce(BigDecimal.ZERO, BigDecimal::add);
            for (AccountOperation operation : permutation) {
                Account account = accounts.get(operation.index(accounts.size()));
                switch (operation.type()) {
                    case DEPOSIT -> {
                        accountService.deposit(account.getAccountNumber(), operation.amount());
                        total = normalize(total.add(operation.amount()));
                    }
                    case WITHDRAW -> {
                        try {
                            accountService.withdraw(account.getAccountNumber(), operation.amount());
                            total = normalize(total.subtract(operation.amount()));
                        } catch (BusinessException ignored) {
                        }
                    }
                    case TRANSFER -> {
                        Account target = accounts.get(operation.otherIndex(accounts.indexOf(account), accounts.size()));
                        try {
                            accountService.transfer(account.getAccountNumber(), target.getAccountNumber(),
                                    operation.amount());
                        } catch (BusinessException ignored) {
                        }
                    }
                }
                List<Account> persisted = accountRepository.findAll();
                BigDecimal totalPersisted = persisted.stream().map(Account::getBalance)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                Assertions.assertThat(totalPersisted).isEqualByComparingTo(total);
                persisted.forEach(persistedAccount -> Assertions.assertThat(persistedAccount.getBalance())
                        .isGreaterThanOrEqualTo(BigDecimal.ZERO));
            }
        }
    }

    @Property(tries = 30)
    @Label("5.1 Historial crece solo en éxito")
    void transactionHistoryGrowsOnlyOnSuccess(@ForAll("nonNegativeBalances") BigDecimal initialBalance,
            @ForAll("operationSequences") List<AccountOperation> operations) {
        Account account = accountService.createAccount(nextAccountNumber(), "Holder", initialBalance);
        int historySize = transactionRepository.findByAccount(account).size();

        for (AccountOperation operation : operations) {
            try {
                switch (operation.type()) {
                    case DEPOSIT -> accountService.deposit(account.getAccountNumber(), operation.amount());
                    case WITHDRAW -> accountService.withdraw(account.getAccountNumber(), operation.amount());
                    case TRANSFER -> {
                        Account target = accountService.createAccount(nextAccountNumber(), "Target",
                                initialBalance);
                        accountService.transfer(account.getAccountNumber(), target.getAccountNumber(),
                                operation.amount());
                    }
                }
                historySize++;
            } catch (BusinessException ignored) {
            }

            int currentSize = transactionRepository.findByAccount(account).size();
            Assertions.assertThat(currentSize).isLessThanOrEqualTo(historySize);
        }
    }

    @Property(tries = 20)
    @Label("5.2 IDs únicos y orden temporal")
    void transactionsAreStoredWithUniqueIds(@ForAll("nonNegativeBalances") BigDecimal initialBalance,
            @ForAll("depositSequences") List<BigDecimal> amounts) {
        Account account = accountService.createAccount(nextAccountNumber(), "Holder", initialBalance);
        amounts.forEach(amount -> accountService.deposit(account.getAccountNumber(), amount));
        List<Long> ids = transactionRepository.findAll().stream().map(AccountTransaction::getId).toList();
        Assertions.assertThat(ids).doesNotHaveDuplicates();

        List<AccountTransaction> ordered = transactionRepository.findByAccount(account);
        List<AccountTransaction> sortedByOccurred = ordered.stream()
                .sorted(Comparator.comparing(AccountTransaction::getOccurredAt).reversed())
                .toList();
        Assertions.assertThat(ordered).isEqualTo(sortedByOccurred);
    }

    @Property(tries = 20)
    @Label("5.3 Persistencia round-trip")
    void accountPersistenceRoundTrip(@ForAll("nonNegativeBalances") BigDecimal initialBalance) {
        Account created = accountService.createAccount(nextAccountNumber(), "Holder", initialBalance);
        Account loaded = accountService.getAccount(created.getAccountNumber());
        Assertions.assertThat(loaded.getAccountNumber()).isEqualTo(created.getAccountNumber());
        Assertions.assertThat(loaded.getBalance()).isEqualByComparingTo(created.getBalance());
    }

    @Property(tries = 20)
    @Label("7.1 Depósitos concurrentes")
    void concurrentDepositsDoNotLoseUpdates(@ForAll("nonNegativeBalances") BigDecimal initialBalance,
            @ForAll("depositSequences") List<BigDecimal> deposits) throws InterruptedException {
        Account account = accountService.createAccount(nextAccountNumber(), "Holder", initialBalance);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (BigDecimal amount : deposits) {
            futures.add(CompletableFuture.runAsync(
                    () -> accountService.deposit(account.getAccountNumber(), amount), executor));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.SECONDS);

        BigDecimal expected = normalize(initialBalance
                .add(deposits.stream().reduce(BigDecimal.ZERO, BigDecimal::add)));
        Assertions.assertThat(accountService.getAccount(account.getAccountNumber()).getBalance())
                .isEqualByComparingTo(expected);
    }

    @Property(tries = 10)
    @Label("7.2 Transferencias cruzadas concurrentes")
    void concurrentCrossTransfersConserveTotals(@ForAll("positiveAmounts") BigDecimal amount) throws Exception {
        Account accountA = accountService.createAccount(nextAccountNumber(), "A", new BigDecimal("1000"));
        Account accountB = accountService.createAccount(nextAccountNumber(), "B", new BigDecimal("1000"));
        ExecutorService executor = Executors.newFixedThreadPool(4);

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            futures.add(CompletableFuture.runAsync(
                    () -> safeTransfer(accountA, accountB, amount), executor));
            futures.add(CompletableFuture.runAsync(
                    () -> safeTransfer(accountB, accountA, amount), executor));
        }

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.SECONDS);

        BigDecimal total = accountService.getAccount(accountA.getAccountNumber()).getBalance()
                .add(accountService.getAccount(accountB.getAccountNumber()).getBalance());
        Assertions.assertThat(total).isEqualByComparingTo(new BigDecimal("2000.00"));
        Assertions.assertThat(accountService.getAccount(accountA.getAccountNumber()).getBalance())
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
        Assertions.assertThat(accountService.getAccount(accountB.getAccountNumber()).getBalance())
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    @Property(tries = 20)
    @Label("13.1/13.2 Invariantes globales")
    void globalInvariantsHold(@ForAll("operationSequences") List<AccountOperation> operations) {
        List<Account> accounts = createAccounts(4, new BigDecimal("100"));
        BigDecimal initialTotal = accounts.stream().map(Account::getBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expectedTotal = initialTotal;

        for (AccountOperation operation : operations) {
            Account account = accounts.get(operation.index(accounts.size()));
            switch (operation.type()) {
                case DEPOSIT -> {
                    accountService.deposit(account.getAccountNumber(), operation.amount());
                    expectedTotal = normalize(expectedTotal.add(operation.amount()));
                }
                case WITHDRAW -> {
                    try {
                        accountService.withdraw(account.getAccountNumber(), operation.amount());
                        expectedTotal = normalize(expectedTotal.subtract(operation.amount()));
                    } catch (BusinessException ignored) {
                    }
                }
                case TRANSFER -> {
                    Account target = accounts.get(operation.otherIndex(accounts.indexOf(account), accounts.size()));
                    try {
                        accountService.transfer(account.getAccountNumber(), target.getAccountNumber(),
                                operation.amount());
                    } catch (BusinessException ignored) {
                    }
                }
            }

            BigDecimal persistedTotal = accountRepository.findAll().stream().map(Account::getBalance)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            Assertions.assertThat(persistedTotal).isEqualByComparingTo(expectedTotal);
            accountRepository.findAll().forEach(stored -> Assertions.assertThat(stored.getBalance())
                    .isGreaterThanOrEqualTo(BigDecimal.ZERO));
        }

        Statistics.label("total").collect(expectedTotal);
    }

    private void safeTransfer(Account source, Account target, BigDecimal amount) {
        try {
            accountService.transfer(source.getAccountNumber(), target.getAccountNumber(), amount);
        } catch (BusinessException ignored) {
        }
    }

    private List<Account> createAccounts(int count, BigDecimal balance) {
        List<Account> accounts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            accounts.add(accountService.createAccount(nextAccountNumber(), "Holder" + i, balance));
        }
        return accounts;
    }

    private List<List<AccountOperation>> generatePermutations(List<AccountOperation> operations) {
        if (operations.isEmpty()) {
            return List.of(List.of());
        }
        List<List<AccountOperation>> permutations = new ArrayList<>();
        for (int i = 0; i < operations.size(); i++) {
            AccountOperation head = operations.get(i);
            List<AccountOperation> rest = new ArrayList<>(operations);
            rest.remove(i);
            for (List<AccountOperation> perm : generatePermutations(rest)) {
                List<AccountOperation> newPerm = new ArrayList<>();
                newPerm.add(head);
                newPerm.addAll(perm);
                permutations.add(newPerm);
            }
        }
        return permutations;
    }

    private BigDecimal normalize(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private String nextAccountNumber() {
        return "ACC-" + ACCOUNT_SEQUENCE.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private Arbitrary<BigDecimal> monetaryAmount(BigDecimal min, BigDecimal max, int minScale, int maxScale) {
        return Combinators.combine(
                        Arbitraries.bigDecimals().between(min, max),
                        Arbitraries.integers().between(minScale, maxScale))
                .as((value, scale) -> value.setScale(scale, RoundingMode.HALF_UP))
                .filter(scaled -> scaled.compareTo(min) >= 0 && scaled.compareTo(max) <= 0);
    }

    private enum AccountOperationType {
        DEPOSIT,
        WITHDRAW,
        TRANSFER
    }

    private record AccountOperation(AccountOperationType type, BigDecimal amount) {

        int index(int max) {
            return Math.abs(amount.unscaledValue().hashCode()) % max;
        }

        int otherIndex(int currentIndex, int max) {
            return (currentIndex + 1) % max;
        }
    }
}
