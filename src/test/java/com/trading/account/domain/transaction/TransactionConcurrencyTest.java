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

    // IS-31: 잔액 확인과 차감을 조건부 원자적 UPDATE 한 번으로 처리한다.
    // 같은 계좌에 100개 출금 요청이 몰려도 DB가 row update를 직렬화하고, 각 UPDATE는
    // 최신 잔액 기준으로 balance >= amount 조건을 다시 판단한다. 초기 잔액이 정확히
    // 100건을 감당할 수 있으므로, 원본 DB 예외 누출 없이 100건 모두 성공해야 한다.
    @Test
    void concurrentWithdraw_100Threads_balanceStaysConsistentAndFailuresAreStructured() throws InterruptedException {
        Member member = memberRepository.save(new Member("김유현", "yuhyun@example.com", "password123!"));
        accountRepository.save(new Account("999-999-99990", member));
        transactionService.deposit("999-999-99990", member.getId(), BigDecimal.valueOf(10_000));

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
                    transactionService.withdraw("999-999-99990", member.getId(), withdrawAmount);
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
        boolean finishedInTime = done.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        BigDecimal finalBalance = accountRepository.findByAccountNumber("999-999-99990")
                .orElseThrow()
                .getBalance();

        assertThat(finishedInTime).isTrue();
        assertThat(unexpectedFailures).isEmpty();
        assertThat(conflictCount.get()).isZero();
        assertThat(successCount.get()).isEqualTo(threadCount);
        assertThat(finalBalance).isEqualByComparingTo(BigDecimal.ZERO);
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
        Member memberA = memberRepository.save(new Member("김에이", "a@example.com", "password123!"));
        Member memberB = memberRepository.save(new Member("김비", "b@example.com", "password123!"));
        String accountA = "111-111-11115";
        String accountB = "222-222-22220";
        accountRepository.save(new Account(accountA, memberA));
        accountRepository.save(new Account(accountB, memberB));
        transactionService.deposit(accountA, memberA.getId(), BigDecimal.valueOf(100_000));
        transactionService.deposit(accountB, memberB.getId(), BigDecimal.valueOf(100_000));

        int transfersPerDirection = 50;
        BigDecimal transferAmount = BigDecimal.valueOf(100);
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(transfersPerDirection * 2);
        List<Throwable> unexpectedFailures = new CopyOnWriteArrayList<>();

        Runnable aToB = () -> runTransfer(accountA, accountB, transferAmount, memberA.getId(), start, done, unexpectedFailures);
        Runnable bToA = () -> runTransfer(accountB, accountA, transferAmount, memberB.getId(), start, done, unexpectedFailures);
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

    private void runTransfer(String from, String to, BigDecimal amount, Long requesterId, CountDownLatch start, CountDownLatch done,
                              List<Throwable> unexpectedFailures) {
        try {
            start.await();
            transactionService.transfer(new TransferReqDto(from, to, amount), requesterId);
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
