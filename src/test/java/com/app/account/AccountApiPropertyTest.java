package com.app.account;

import com.app.account.dto.CreateAccountRequest;
import com.app.account.dto.TransferRequest;
import com.app.idempotency.IdempotencyRecordRepository;
import com.app.transaction.AccountTransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeTry;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountApiPropertyTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AccountTransactionRepository transactionRepository;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @BeforeTry
    void cleanState() {
        transactionRepository.deleteAll();
        idempotencyRecordRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Provide
    Arbitrary<String> accountNumbers() {
        return Arbitraries.strings().withCharRange('A', 'Z')
                .ofMinLength(6).ofMaxLength(10)
                .map(prefix -> prefix + UUID.randomUUID().toString().substring(0, 6));
    }

    @Provide
    Arbitrary<String> holderNames() {
        return Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(20);
    }

    @Provide
    Arbitrary<BigDecimal> balances() {
        return monetaryAmounts(BigDecimal.ZERO, new BigDecimal("10000"), 0, 2);
    }

    @Provide
    Arbitrary<BigDecimal> transferAmounts() {
        return monetaryAmounts(new BigDecimal("0.01"), new BigDecimal("5000"), 0, 2);
    }

    @Property(tries = 20)
    @Label("6.1 POST seguido de GET mantiene coherencia")
    void postThenGetMatchesState(@ForAll("accountNumbers") String accountNumber,
            @ForAll("holderNames") String holderName,
            @ForAll("balances") BigDecimal balance) throws Exception {
        CreateAccountRequest request = new CreateAccountRequest();
        request.setAccountNumber(accountNumber);
        request.setHolderName(holderName);
        request.setInitialBalance(balance);

        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(get("/api/accounts/" + accountNumber))
                .andExpect(status().isOk())
                .andReturn();

        Map<?, ?> payload = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        Assertions.assertThat(payload.get("accountNumber")).isEqualTo(accountNumber);
        Assertions.assertThat(new BigDecimal(payload.get("balance").toString()))
                .isEqualByComparingTo(balance.setScale(2, RoundingMode.HALF_UP));
    }

    @Property(tries = 20)
    @Label("6.2 Transferencia conserva la suma reportada")
    void transferKeepsReportedTotals(@ForAll("balances") BigDecimal sourceBalance,
            @ForAll("balances") BigDecimal targetBalance,
            @ForAll("transferAmounts") BigDecimal amount) throws Exception {
        String source = createAccountViaApi(sourceBalance);
        String target = createAccountViaApi(targetBalance);

        BigDecimal totalBefore = fetchBalance(source).add(fetchBalance(target));

        TransferRequest transferRequest = new TransferRequest();
        transferRequest.setSourceAccountNumber(source);
        transferRequest.setDestinationAccountNumber(target);
        transferRequest.setAmount(amount);
        transferRequest.setIdempotencyKey(UUID.randomUUID().toString());

        mockMvc.perform(post("/api/accounts/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(transferRequest)))
                .andExpect(result -> {
                    if (result.getResponse().getStatus() == 204) {
                        Assertions.assertThat(fetchBalance(source).add(fetchBalance(target)))
                                .isEqualByComparingTo(totalBefore);
                    } else {
                        Assertions.assertThat(fetchBalance(source).add(fetchBalance(target)))
                                .isEqualByComparingTo(totalBefore);
                    }
                });
    }

    @Property(tries = 20)
    @Label("6.3 Validación de entrada rechaza estados inválidos")
    void invalidPayloadsAreRejected(@ForAll("invalidAccountNumbers") String accountNumber,
            @ForAll("invalidAmounts") BigDecimal amount) throws Exception {
        CreateAccountRequest invalidCreate = new CreateAccountRequest();
        invalidCreate.setAccountNumber(accountNumber);
        invalidCreate.setHolderName("");
        invalidCreate.setInitialBalance(amount);

        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidCreate)))
                .andExpect(status().is4xxClientError());
    }

    @Provide
    Arbitrary<String> invalidAccountNumbers() {
        return Arbitraries.oneOf(
                Arbitraries.just(""),
                Arbitraries.just("   "),
                Arbitraries.strings().withChars('A', 'B', 'C', 'D', 'E', 'F', 'G', '-', '#')
                        .ofMinLength(70).ofMaxLength(140));
    }

    @Provide
    Arbitrary<BigDecimal> invalidAmounts() {
        return monetaryAmounts(new BigDecimal("-9999"), new BigDecimal("-0.01"), 2, 4);
    }

    private String createAccountViaApi(BigDecimal balance) throws Exception {
        String accountNumber = UUID.randomUUID().toString().substring(0, 10).toUpperCase();
        CreateAccountRequest request = new CreateAccountRequest();
        request.setAccountNumber(accountNumber);
        request.setHolderName("Holder" + accountNumber.substring(0, 2));
        request.setInitialBalance(balance);

        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
        return accountNumber;
    }

    private BigDecimal fetchBalance(String accountNumber) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/accounts/" + accountNumber))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> payload = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        return new BigDecimal(payload.get("balance").toString());
    }

    private Arbitrary<BigDecimal> monetaryAmounts(BigDecimal min, BigDecimal max, int minScale, int maxScale) {
        return Combinators.combine(
                        Arbitraries.bigDecimals().between(min, max),
                        Arbitraries.integers().between(minScale, maxScale))
                .as((value, scale) -> value.setScale(scale, RoundingMode.HALF_UP))
                .filter(scaled -> scaled.compareTo(min) >= 0 && scaled.compareTo(max) <= 0);
    }
}
