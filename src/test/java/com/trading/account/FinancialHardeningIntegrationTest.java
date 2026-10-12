package com.trading.account;

import com.trading.account.domain.transaction.TransactionService;
import com.trading.account.domain.transaction.TransactionHistoryRepository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.account.AbstractIntegrationTest;
import com.trading.account.common.exception.CustomException;
import com.trading.account.common.exception.ErrorCode;
import com.trading.account.common.security.JwtTokenProvider;
import com.trading.account.domain.account.AccountRepository;
import com.trading.account.domain.account.AccountService;
import com.trading.account.domain.idempotency.IdempotencyKeyRepository;
import com.trading.account.domain.idempotency.IdempotencyService;
import com.trading.account.domain.member.Member;
import com.trading.account.domain.member.MemberRepository;
import com.trading.account.domain.transaction.dto.TransferReqDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// No outer test transaction: all assertions observe real MySQL commits and rollbacks.
@SpringBootTest(properties = "ledger.simulated-deposits-enabled=true")
@AutoConfigureMockMvc
class FinancialHardeningIntegrationTest extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MemberRepository members;
    @Autowired AccountService accounts;
    @Autowired AccountRepository accountRepository;
    @Autowired TransactionService transactions;
    @Autowired TransactionHistoryRepository history;
    @Autowired IdempotencyKeyRepository keys;
    @Autowired IdempotencyService idempotency;
    @Autowired JwtTokenProvider tokens;
    @Autowired JdbcTemplate jdbc;

    record Actor(Long id, String token, String account) {}
    private Actor actor() {
        String email = UUID.randomUUID() + "@test.invalid";
        Member member = members.save(new Member("Test", email, "test-hash"));
        return new Actor(member.getId(), tokens.generateToken(member.getId(), email),
                accounts.createAccount(member.getId()).accountNumber());
    }
    private BigDecimal balance(Actor actor) {
        return accountRepository.findByAccountNumber(actor.account()).orElseThrow().getBalance();
    }
    private ResultActions request(Actor actor, String path, String key, String body) throws Exception {
        return mvc.perform(post(path).header("Authorization", "Bearer " + actor.token())
                .header("Idempotency-Key", key).contentType("application/json").content(body));
    }
    private String depositPath(Actor a) { return "/api/accounts/" + a.account() + "/deposit"; }
    private void adminSql(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @Test
    void fractionalCentTransferIsRejectedWithoutChangingTotalOrLedger() throws Exception {
        Actor a = actor();
        Actor b = actor();
        transactions.deposit(a.account(), a.id(), new BigDecimal("1.00"));
        request(a, "/api/transfer", "fraction", mapper.writeValueAsString(
                new TransferReqDto(a.account(), b.account(), new BigDecimal("0.015"))))
                .andExpect(status().isBadRequest());
        assertThat(balance(a)).isEqualByComparingTo("1.00");
        assertThat(balance(b)).isEqualByComparingTo("0.00");
        assertThat(history.count()).isEqualTo(1);
        assertThat(keys.count()).isZero();
    }

    @Test
    void serviceRejectsNegativeAndFractionalAmountsWithoutHttpValidation() {
        Actor a = actor();
        Actor b = actor();
        for (String text : new String[]{"-1", "0", "0.001", "0.015", "1.999", "100000000000000000"}) {
            BigDecimal amount = new BigDecimal(text);
            assertInvalidAmount(() -> transactions.deposit(a.account(), a.id(), amount));
            assertInvalidAmount(() -> transactions.withdraw(a.account(), a.id(), amount));
            assertInvalidAmount(() -> transactions.transfer(new TransferReqDto(a.account(), b.account(), amount), a.id()));
        }
        assertInvalidAmount(() -> transactions.deposit(a.account(), a.id(), null));
        assertThat(balance(a)).isEqualByComparingTo("0.00");
        assertThat(balance(b)).isEqualByComparingTo("0.00");
        assertThat(history.count()).isZero();
    }

    private void assertInvalidAmount(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(CustomException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }

    @Test
    void anotherActorCannotReplayOwnersResponseButCanUseSameKeyForOwnAccount() throws Exception {
        Actor a = actor();
        Actor b = actor();
        request(a, depositPath(a), "shared-key", "{\"amount\":100}").andExpect(status().isOk());
        request(b, "/api/accounts/" + a.account() + "/withdraw", "shared-key", "{\"amount\":1}")
                .andExpect(status().isForbidden());
        request(b, depositPath(b), "shared-key", "{\"amount\":50}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.accountNumber").value(b.account()));
        assertThat(balance(a)).isEqualByComparingTo("100");
        assertThat(balance(b)).isEqualByComparingTo("50");
        assertThat(history.count()).isEqualTo(2);
    }

    @Test
    void sameActorKeyRejectsChangedAmountOperationAndAccount() throws Exception {
        Actor a = actor();
        String secondAccount = accounts.createAccount(a.id()).accountNumber();
        request(a, depositPath(a), "bound-key", "{\"amount\":100}").andExpect(status().isOk());
        request(a, depositPath(a), "bound-key", "{\"amount\":200}").andExpect(status().isConflict());
        request(a, "/api/accounts/" + a.account() + "/withdraw", "bound-key", "{\"amount\":100}")
                .andExpect(status().isConflict());
        request(a, "/api/accounts/" + secondAccount + "/deposit", "bound-key", "{\"amount\":100}")
                .andExpect(status().isConflict());
        assertThat(balance(a)).isEqualByComparingTo("100");
        assertThat(history.count()).isEqualTo(1);
    }

    @Test
    void transferKeyCannotBeReusedForAnotherRecipient() throws Exception {
        Actor a = actor();
        Actor b = actor();
        Actor c = actor();
        transactions.deposit(a.account(), a.id(), new BigDecimal("3.00"));
        request(a, "/api/transfer", "recipient-bound", mapper.writeValueAsString(
                new TransferReqDto(a.account(), b.account(), BigDecimal.ONE))).andExpect(status().isOk());
        request(a, "/api/transfer", "recipient-bound", mapper.writeValueAsString(
                new TransferReqDto(a.account(), c.account(), BigDecimal.ONE))).andExpect(status().isConflict());
        assertThat(balance(a)).isEqualByComparingTo("2");
        assertThat(balance(b)).isEqualByComparingTo("1");
        assertThat(balance(c)).isEqualByComparingTo("0");
        assertThat(history.count()).isEqualTo(2);
    }

    @Test
    void equivalentAmountReplaysSameTransactionAndPersistsActorAndKeyLink() throws Exception {
        Actor a = actor();
        String first = request(a, depositPath(a), "canonical", "{\"amount\":100}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.transactionId").isNumber())
                .andExpect(jsonPath("$.data.actorId").value(a.id()))
                .andExpect(jsonPath("$.data.idempotencyId").isNumber())
                .andReturn().getResponse().getContentAsString();
        String again = request(a, depositPath(a), "canonical", "{\"amount\":100.00}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(again).path("data")).isEqualTo(mapper.readTree(first).path("data"));
        assertThat(jdbc.queryForObject("select actor_id from transaction_history", Long.class)).isEqualTo(a.id());
        assertThat(jdbc.queryForObject("select count(*) from transaction_history t join idempotency_key i "
                + "on t.idempotency_key_id=i.id where i.status='COMPLETED'", Long.class)).isEqualTo(1);
        assertThat(history.count()).isEqualTo(1);
    }

    @Test
    void completionFailureRollsBackMoneyHistoryAndClaimThenRetryCommitsOnce() throws Exception {
        Actor a = actor();
        adminSql("CREATE TRIGGER test_fail_completion BEFORE UPDATE ON idempotency_key FOR EACH ROW "
                + "BEGIN IF NEW.status = 'COMPLETED' THEN SIGNAL SQLSTATE '45000' "
                + "SET MESSAGE_TEXT = 'test completion failure'; END IF; END");
        try {
            request(a, depositPath(a), "completion-fault", "{\"amount\":100}")
                    .andExpect(status().isInternalServerError());
            assertThat(balance(a)).isEqualByComparingTo("0");
            assertThat(history.count()).isZero();
            assertThat(keys.count()).isZero();
        } finally {
            adminSql("DROP TRIGGER IF EXISTS test_fail_completion");
        }
        request(a, depositPath(a), "completion-fault", "{\"amount\":100}").andExpect(status().isOk());
        assertThat(balance(a)).isEqualByComparingTo("100");
        assertThat(history.count()).isEqualTo(1);
        assertThat(keys.count()).isEqualTo(1);
    }

    @Test
    void malformedKeysAreRejectedBeforeClaimOrBalanceChange() throws Exception {
        Actor a = actor();
        for (String key : new String[]{"", "   ", "x".repeat(101), "contains space", "a/b", "한글"}) {
            request(a, depositPath(a), key, "{\"amount\":1}").andExpect(status().isBadRequest());
        }
        assertThat(balance(a)).isEqualByComparingTo("0");
        assertThat(keys.count()).isZero();
    }

    @Test
    void maximumBalanceRejectsOverflowAndRollsBackTransferDebit() throws Exception {
        Actor a = actor();
        Actor b = actor();
        transactions.deposit(a.account(), a.id(), BigDecimal.ONE);
        transactions.deposit(b.account(), b.id(), new BigDecimal("99999999999999999.99"));
        request(b, depositPath(b), "overflow-deposit", "{\"amount\":0.01}").andExpect(status().isConflict());
        request(a, "/api/transfer", "overflow-transfer", mapper.writeValueAsString(
                new TransferReqDto(a.account(), b.account(), new BigDecimal("0.01"))))
                .andExpect(status().isConflict());
        assertThat(balance(a)).isEqualByComparingTo("1.00");
        assertThat(balance(b)).isEqualByComparingTo("99999999999999999.99");
        assertThat(history.count()).isEqualTo(2);
    }

    @Test
    void historyWithEqualTimestampsUsesDescendingUniqueIds() throws Exception {
        Actor a = actor();
        for (int i = 0; i < 3; i++) transactions.deposit(a.account(), a.id(), BigDecimal.ONE);
        jdbc.update("update transaction_history set created_at='2026-01-01 00:00:00'");
        long largestId = jdbc.queryForObject("select max(id) from transaction_history", Long.class);
        mvc.perform(get("/api/accounts/" + a.account() + "/transactions?size=1")
                        .header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].id").value(largestId));
    }

    @Test
    void cursorHistoryDoesNotRepeatRowsWhenNewTransactionsArrive() throws Exception {
        Actor a = actor();
        for (int i = 0; i < 4; i++) transactions.deposit(a.account(), a.id(), BigDecimal.ONE);
        String path = "/api/accounts/" + a.account() + "/transactions/cursor";
        String first = mvc.perform(get(path).param("size", "2").header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.hasNext").value(true))
                .andReturn().getResponse().getContentAsString();
        var firstData = mapper.readTree(first).path("data");
        long nextCursor = firstData.path("nextCursor").asLong();
        transactions.deposit(a.account(), a.id(), BigDecimal.ONE);
        String second = mvc.perform(get(path).param("size", "2").param("beforeId", Long.toString(nextCursor))
                        .header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.hasNext").value(false))
                .andReturn().getResponse().getContentAsString();
        var secondItems = mapper.readTree(second).path("data").path("items");
        assertThat(secondItems.size()).isEqualTo(2);
        assertThat(secondItems.get(0).path("id").asLong()).isLessThan(nextCursor);
        assertThat(secondItems.get(1).path("id").asLong()).isLessThan(secondItems.get(0).path("id").asLong());
    }

    @Test
    void transientMySqlFailureRetriesWholeTransactionIncludingClaim() throws Exception {
        Actor a = actor();
        // Nontransactional counter is fault-injection state only, so the first failure survives rollback.
        adminSql("CREATE TABLE test_retry_probe (attempts INT NOT NULL) ENGINE=MyISAM");
        adminSql("INSERT INTO test_retry_probe VALUES (0)");
        adminSql("CREATE TRIGGER test_fail_first_balance BEFORE UPDATE ON account FOR EACH ROW BEGIN "
                + "UPDATE test_retry_probe SET attempts=attempts+1; "
                + "IF (SELECT attempts FROM test_retry_probe)=1 THEN SIGNAL SQLSTATE '40001' "
                + "SET MYSQL_ERRNO=1213, MESSAGE_TEXT='test transient deadlock'; END IF; END");
        try {
            request(a, depositPath(a), "retry-transaction", "{\"amount\":100}")
                    .andExpect(status().isOk());
            assertThat(jdbc.queryForObject("select attempts from test_retry_probe", Integer.class)).isEqualTo(2);
            assertThat(balance(a)).isEqualByComparingTo("100");
            assertThat(history.count()).isEqualTo(1);
            assertThat(keys.count()).isEqualTo(1);
        } finally {
            adminSql("DROP TRIGGER IF EXISTS test_fail_first_balance");
            adminSql("DROP TABLE IF EXISTS test_retry_probe");
        }
    }

    @Test
    void serializationFailureRollsBackMoneyAndHistory() {
        Actor a = actor();
        assertThatThrownBy(() -> idempotency.execute(a.id(), "serialize-failure", "DEPOSIT", a.account(), Object.class, claim -> {
            transactions.deposit(a.account(), a.id(), BigDecimal.TEN, claim);
            return new Object() {
                public String getValue() { throw new IllegalStateException("test serialization failure"); }
            };
        })).isInstanceOf(RuntimeException.class);
        assertThat(balance(a)).isEqualByComparingTo("0");
        assertThat(history.count()).isZero();
        assertThat(keys.count()).isZero();
    }

    @Test
    void failedCommitEmitsNoSuccessfulTransactionLog() throws Exception {
        Actor a = actor();
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(TransactionService.class);
        var events = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        adminSql("CREATE TRIGGER test_fail_audit_completion BEFORE UPDATE ON idempotency_key FOR EACH ROW "
                + "BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test audit completion failure'; END");
        try {
            request(a, depositPath(a), "audit-fail", "{\"amount\":73.29}")
                    .andExpect(status().isInternalServerError());
            assertThat(events.list).noneMatch(event -> event.getLevel() == ch.qos.logback.classic.Level.INFO);
        } finally {
            adminSql("DROP TRIGGER IF EXISTS test_fail_audit_completion");
            logger.detachAppender(events);
            events.stop();
        }
    }

    @Test
    void ledgerInsertFailureRollsBackBalancesAndKey() throws Exception {
        Actor a = actor();
        adminSql("CREATE TRIGGER test_fail_ledger BEFORE INSERT ON transaction_history FOR EACH ROW "
                + "BEGIN SIGNAL SQLSTATE '23000' SET MYSQL_ERRNO=1048, MESSAGE_TEXT='test ledger integrity failure'; END");
        try {
            request(a, depositPath(a), "ledger-fail", "{\"amount\":10}").andExpect(status().isInternalServerError());
            assertThat(balance(a)).isEqualByComparingTo("0");
            assertThat(history.count()).isZero();
            assertThat(keys.count()).isZero();
        } finally {
            adminSql("DROP TRIGGER IF EXISTS test_fail_ledger");
        }
    }

    @Test
    void repeatedTransientFailureReturnsConflictAndLeavesNoPartialEffects() throws Exception {
        Actor a = actor();
        adminSql("CREATE TRIGGER test_retry_exhaustion BEFORE UPDATE ON account FOR EACH ROW "
                + "BEGIN SIGNAL SQLSTATE '40001' SET MYSQL_ERRNO=1213, MESSAGE_TEXT='test repeated deadlock'; END");
        try {
            request(a, depositPath(a), "retry-exhaustion", "{\"amount\":10}")
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("C010"));
            assertThat(balance(a)).isEqualByComparingTo("0");
            assertThat(history.count()).isZero();
            assertThat(keys.count()).isZero();
        } finally {
            adminSql("DROP TRIGGER IF EXISTS test_retry_exhaustion");
        }
    }

    @Test
    void successfulAuditLogContainsCommittedIdsWithoutAccountOrAmount() throws Exception {
        Actor a = actor();
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(TransactionService.class);
        var events = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        try {
            request(a, depositPath(a), "audit-success", "{\"amount\":73.29}").andExpect(status().isOk());
            assertThat(events.list).hasSize(1);
            assertThat(events.list.get(0).getFormattedMessage()).contains("transactionId=", "actorId=", "idempotencyId=")
                    .doesNotContain(a.account(), "73.29");
            assertThat(history.count()).isEqualTo(1);
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }
}
