package com.trading.account.domain.transaction;

import com.trading.account.domain.account.Account;
import com.trading.account.domain.account.AccountRepository;
import com.trading.account.domain.member.Member;
import com.trading.account.domain.member.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// IS-30: TransactionConcurrencyTest.concurrentWithdraw_100Threads_...와 동일한 핫스팟 시나리오를
// 비관적 락(PESSIMISTIC_WRITE)으로 재현해 낙관적 락(@Version+@Retryable) 대비 성공률/소요시간을
// 실측 비교한다. 재시도가 없으므로 여기서 실패가 나오면 락 대기 자체가 문제라는 뜻 — 정상적으로는
// 100건 전부 "성공 또는 대기 후 성공"이어야 하고, 원본 DB 예외 누출도 없어야 한다.
@SpringBootTest
@Testcontainers
class PessimisticLockExperimentTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @Autowired
    private PessimisticLockWithdrawService pessimisticLockWithdrawService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Test
    void concurrentWithdraw_100Threads_pessimisticLock_measuresSuccessRateAndLatency() throws InterruptedException {
        Member member = memberRepository.save(new Member("김유현", "pessimistic@example.com", "password123!"));
        Account account = new Account("999-999-99990", member);
        account.deposit(BigDecimal.valueOf(10_000));
        accountRepository.save(account);

        int threadCount = 100;
        BigDecimal withdrawAmount = BigDecimal.valueOf(100);
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();
        List<Throwable> unexpectedFailures = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    pessimisticLockWithdrawService.withdraw("999-999-99990", withdrawAmount);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    unexpectedFailures.add(e);
                } finally {
                    done.countDown();
                }
            });
        }

        long startedAt = System.currentTimeMillis();
        start.countDown();
        boolean finishedInTime = done.await(30, TimeUnit.SECONDS);
        long elapsedMs = System.currentTimeMillis() - startedAt;
        executor.shutdown();

        BigDecimal finalBalance = accountRepository.findByAccountNumber("999-999-99990")
                .orElseThrow()
                .getBalance();

        System.out.printf("[IS-30] pessimistic lock: success=%d/%d, elapsedMs=%d, unexpectedFailures=%d%n",
                successCount.get(), threadCount, elapsedMs, unexpectedFailures.size());
        unexpectedFailures.forEach(Throwable::printStackTrace);

        assertThat(finishedInTime).isTrue();
        // 원본 DB 예외(락 타임아웃 등)가 그대로 새어나온 요청은 하나도 없어야 함
        assertThat(unexpectedFailures).isEmpty();
        // 비관적 락은 재시도가 아니라 대기이므로, 낙관적 락과 달리 100건 전부 성공해야 함
        assertThat(successCount.get()).isEqualTo(threadCount);
        assertThat(finalBalance).isEqualByComparingTo(
                BigDecimal.valueOf(10_000).subtract(withdrawAmount.multiply(BigDecimal.valueOf(successCount.get()))));
    }
}
