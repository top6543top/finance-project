package com.trading.account.domain.idempotency;

import com.trading.account.AbstractIntegrationTest;
import com.trading.account.common.exception.CustomException;
import com.trading.account.common.exception.ErrorCode;
import com.trading.account.domain.account.AccountRepository;
import com.trading.account.domain.account.AccountService;
import com.trading.account.domain.member.Member;
import com.trading.account.domain.member.MemberRepository;
import com.trading.account.domain.transaction.TransactionHistoryRepository;
import com.trading.account.domain.transaction.TransactionService;
import com.trading.account.domain.transaction.dto.TransactionResDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "ledger.simulated-deposits-enabled=true")
class IdempotencyServiceTest extends AbstractIntegrationTest {
    @Autowired IdempotencyService idempotency;
    @Autowired IdempotencyKeyRepository keys;
    @Autowired MemberRepository members;
    @Autowired AccountService accounts;
    @Autowired AccountRepository accountRepository;
    @Autowired TransactionService transactions;
    @Autowired TransactionHistoryRepository history;

    record Actor(Long id, String account) {}
    private Actor actor() {
        Member member = members.save(new Member("Test", "idempotency@test.invalid", "test-hash"));
        return new Actor(member.getId(), accounts.createAccount(member.getId()).accountNumber());
    }
    private TransactionResDto deposit(Actor actor, String key) {
        return idempotency.execute(actor.id(), key, "DEPOSIT", List.of(actor.account(), new BigDecimal("10.00")),
                TransactionResDto.class, claim -> transactions.deposit(actor.account(), actor.id(), BigDecimal.TEN, claim));
    }

    @Test
    void fiftyConcurrentSameKeyRequestsCommitOneTransactionAndReplaySameId() throws Exception {
        Actor a = actor();
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<TransactionResDto>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 50; i++) {
                futures.add(executor.submit(() -> {
                    if (!start.await(30, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                    return deposit(a, "same-key");
                }));
            }
            start.countDown();
            List<TransactionResDto> responses = new ArrayList<>();
            for (var future : futures) responses.add(future.get(60, TimeUnit.SECONDS));
            assertThat(responses).hasSize(50);
            assertThat(responses).extracting(TransactionResDto::transactionId).doesNotContainNull().containsOnly(responses.get(0).transactionId());
            assertThat(history.count()).isEqualTo(1);
            assertThat(keys.count()).isEqualTo(1);
            assertThat(accountRepository.findByAccountNumber(a.account()).orElseThrow().getBalance()).isEqualByComparingTo("10");
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void rejectedBusinessOperationLeavesNoClaimAndCanBeRetriedAfterFunding() {
        Actor a = actor();
        assertThatThrownBy(() -> idempotency.execute(a.id(), "withdraw", "WITHDRAW", a.account(), TransactionResDto.class,
                claim -> transactions.withdraw(a.account(), a.id(), BigDecimal.ONE, claim)))
                .isInstanceOfSatisfying(CustomException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_BALANCE));
        assertThat(keys.count()).isZero();
        assertThat(history.count()).isZero();
        transactions.deposit(a.account(), a.id(), BigDecimal.TEN);
        idempotency.execute(a.id(), "withdraw", "WITHDRAW", a.account(), TransactionResDto.class,
                claim -> transactions.withdraw(a.account(), a.id(), BigDecimal.ONE, claim));
        assertThat(keys.count()).isEqualTo(1);
        assertThat(history.count()).isEqualTo(2);
        assertThat(accountRepository.findByAccountNumber(a.account()).orElseThrow().getBalance()).isEqualByComparingTo("9");
    }

    @Test
    void migratedLegacyKeyCannotExecuteUnknownHistoricalRequestAgain() {
        Actor a = actor();
        IdempotencyKey legacy = new IdempotencyKey(0L, "legacy-key", "LEGACY", "0".repeat(64));
        legacy.complete("{\"obsolete\":true}");
        keys.saveAndFlush(legacy);
        assertThatThrownBy(() -> deposit(a, "legacy-key"))
                .isInstanceOfSatisfying(CustomException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
        assertThat(history.count()).isZero();
        assertThat(keys.count()).isEqualTo(1);
    }

    @Test
    void legacyQuarantinePreservesOriginalCaseAndAccentInsensitiveAliases() {
        Actor a = actor();
        keys.saveAndFlush(new IdempotencyKey(0L, "Legacy-Key", "LEGACY", "0".repeat(64)));
        keys.saveAndFlush(new IdempotencyKey(0L, "café-key", "LEGACY", "0".repeat(64)));
        for (String alias : List.of("legacy-key", "cafe-key")) {
            assertThatThrownBy(() -> deposit(a, alias))
                    .isInstanceOfSatisfying(CustomException.class,
                            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
        }
        assertThat(history.count()).isZero();
    }

    @Test
    void keysAreCaseSensitiveAndOneHundredCharactersIsAccepted() {
        Actor a = actor();
        deposit(a, "Case-Sensitive");
        deposit(a, "case-sensitive");
        deposit(a, "x".repeat(100));
        assertThat(keys.count()).isEqualTo(3);
        assertThat(history.count()).isEqualTo(3);
        assertThat(accountRepository.findByAccountNumber(a.account()).orElseThrow().getBalance()).isEqualByComparingTo("30");
    }
}
