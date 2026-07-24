package com.trading.account.domain.transaction;

import com.trading.account.common.exception.CustomException;
import com.trading.account.common.exception.ErrorCode;
import com.trading.account.domain.account.Account;
import com.trading.account.domain.account.AccountRepository;
import com.trading.account.domain.member.Member;
import com.trading.account.domain.member.MemberRepository;
import com.trading.account.domain.transaction.dto.TransferReqDto;
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

@SpringBootTest
@Testcontainers
class TransactionConcurrencyTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private MemberRepository memberRepository;

    // IS-12/13: 100 스레드가 계좌 1개에 동시 출금. Account.version(@Version) 덕분에
    // 서로 덮어쓰는 lost update(잔고 마이너스)는 애초에 안 나고, TransactionService의
    // @Retryable(최대 5회)이 버전 충돌/데드락을 재시도로 흡수한다.
    // 다만 100 스레드가 "같은 행 1개"를 동시에 잡는 건 낙관적 락이 애초에 가정한
    // "충돌 낮은 빈도"를 크게 벗어난 최악의 시나리오라, 5회 재시도로도 일부는 끝까지
    // 충돌해 실패할 수 있음 — 그건 버그가 아니라 낙관적 락의 설계상 트레이드오프.
    // 그래서 이 테스트가 지키는 건 "100건 전부 성공"이 아니라:
    //   1) 잔고 정합성 (성공한 만큼만 정확히 차감, 유실/중복 없음)
    //   2) 실패는 반드시 구조화된 CustomException(409) — 원본 DB 예외가 새어나가면 안 됨
    //   3) 재시도 덕분에 성공률이 재현 테스트(재시도 없음, 12/100) 대비 크게 개선
    @Test
    void concurrentWithdraw_100Threads_balanceStaysConsistentAndFailuresAreStructured() throws InterruptedException {
        Member member = memberRepository.save(new Member("김유현", "yuhyun@example.com"));
        accountRepository.save(new Account("999-999-9999", member));
        transactionService.deposit("999-999-9999", BigDecimal.valueOf(10_000));

        int threadCount = 100;
        BigDecimal withdrawAmount = BigDecimal.valueOf(100);
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();
        List<Throwable> unexpectedFailures = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    transactionService.withdraw("999-999-9999", withdrawAmount);
                    successCount.incrementAndGet();
                } catch (CustomException e) {
                    if (e.getErrorCode() == ErrorCode.CONCURRENT_UPDATE_CONFLICT) {
                        conflictCount.incrementAndGet();
                    } else {
                        unexpectedFailures.add(e);
                    }
                } catch (Exception e) {
                    unexpectedFailures.add(e);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        BigDecimal finalBalance = accountRepository.findByAccountNumber("999-999-9999")
                .orElseThrow()
                .getBalance();

        // 원본 DB 예외(deadlock, optimistic lock 등)가 그대로 새어나온 요청은 하나도 없어야 함
        assertThat(unexpectedFailures).isEmpty();
        // 성공/충돌로 분류된 개수 합이 총 요청 수와 일치 (요청이 조용히 사라지지 않음)
        assertThat(successCount.get() + conflictCount.get()).isEqualTo(threadCount);
        // 성공한 만큼만 정확히 차감됨 (lost update, 중복 차감 없음)
        assertThat(finalBalance).isEqualByComparingTo(
                BigDecimal.valueOf(10_000).subtract(withdrawAmount.multiply(BigDecimal.valueOf(successCount.get()))));
        // 재시도 덕분에 재현 테스트(재시도 없이 12/100 성공) 대비 성공률이 크게 개선됨
        assertThat(successCount.get()).isGreaterThanOrEqualTo(70);
    }

    // transfer()는 두 계좌(from, to)를 함께 잠그는데, A→B와 B→A가 동시에 일어나면 각자
    // 반대 순서로 X-lock을 잡으려다 서로 물려 데드락이 날 수 있다(lock ordering deadlock).
    // transfer() 내부에서 계좌번호를 정렬해 항상 같은 전역 순서로 잠그도록 고쳤는데,
    // 이 테스트는 그 고침이 실제로 순환 대기를 없앴는지를 검증한다:
    //   1) done.await 타임아웃 안에 전부 끝남 (데드락으로 영영 안 끝나는 상황이 아님)
    //   2) 두 계좌 잔고 합이 처음과 동일 (돈이 새거나 중복 생성되지 않음)
    //   3) 실패는 반드시 구조화된 CONCURRENT_UPDATE_CONFLICT(재시도 소진)뿐, 원본 DB 예외 누출 없음
    @Test
    void concurrentBidirectionalTransfer_noDeadlock_balanceSumStaysConsistent() throws InterruptedException {
        Member memberA = memberRepository.save(new Member("김에이", "a@example.com"));
        Member memberB = memberRepository.save(new Member("김비", "b@example.com"));
        String accountA = "111-111-1111";
        String accountB = "222-222-2222";
        accountRepository.save(new Account(accountA, memberA));
        accountRepository.save(new Account(accountB, memberB));
        transactionService.deposit(accountA, BigDecimal.valueOf(100_000));
        transactionService.deposit(accountB, BigDecimal.valueOf(100_000));

        int transfersPerDirection = 50;
        BigDecimal transferAmount = BigDecimal.valueOf(100);
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(transfersPerDirection * 2);
        List<Throwable> unexpectedFailures = new CopyOnWriteArrayList<>();

        Runnable aToB = () -> runTransfer(accountA, accountB, transferAmount, start, done, unexpectedFailures);
        Runnable bToA = () -> runTransfer(accountB, accountA, transferAmount, start, done, unexpectedFailures);
        for (int i = 0; i < transfersPerDirection; i++) {
            executor.submit(aToB);
            executor.submit(bToA);
        }

        start.countDown();
        boolean finishedInTime = done.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // 데드락이 여전히 있었다면 재시도 5회를 반복 소진하며 지연되거나, 최악의 경우 여기서 타임아웃남
        assertThat(finishedInTime).isTrue();
        assertThat(unexpectedFailures).isEmpty();

        BigDecimal balanceA = accountRepository.findByAccountNumber(accountA).orElseThrow().getBalance();
        BigDecimal balanceB = accountRepository.findByAccountNumber(accountB).orElseThrow().getBalance();
        assertThat(balanceA.add(balanceB)).isEqualByComparingTo(BigDecimal.valueOf(200_000));
    }

    private void runTransfer(String from, String to, BigDecimal amount, CountDownLatch start, CountDownLatch done,
                              List<Throwable> unexpectedFailures) {
        try {
            start.await();
            transactionService.transfer(new TransferReqDto(from, to, amount));
        } catch (CustomException e) {
            if (e.getErrorCode() != ErrorCode.CONCURRENT_UPDATE_CONFLICT) {
                unexpectedFailures.add(e);
            }
        } catch (Exception e) {
            unexpectedFailures.add(e);
        } finally {
            done.countDown();
        }
    }
}
