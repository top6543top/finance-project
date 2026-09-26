package com.trading.account.domain.reconciliation;

import com.trading.account.AbstractIntegrationTest;
import com.trading.account.domain.account.Account;
import com.trading.account.domain.account.AccountRepository;
import com.trading.account.domain.member.Member;
import com.trading.account.domain.member.MemberRepository;
import com.trading.account.domain.transaction.TransactionHistory;
import com.trading.account.domain.transaction.TransactionHistoryRepository;
import com.trading.account.domain.transaction.TransactionType;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Iterator;

import static org.assertj.core.api.Assertions.assertThat;

// Job -> Step(Reader/Processor/Writer) 전체 배선이 실제 MySQL(Testcontainers)에서 맞물려
// 동작하는지 검증. "몇 개 계좌를 읽어서 몇 개를 불일치로 판정했는지"는 StepExecution의
// read/write count로 확인 — 로그 내용 자체를 캡처하는 건 배선 검증엔 과함.
@SpringBootTest
@SpringBatchTest
class BalanceReconciliationJobIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionHistoryRepository transactionHistoryRepository;

    @Test
    void job_readsAllAccounts_writesOnlyMismatchedOnes() throws Exception {
        Member member = memberRepository.save(new Member("김대사", "reconcile@example.com", "password123!"));

        // 정상 계좌: 입금 이력 합계(1000)와 잔고(1000)가 일치
        Account matched = accountRepository.save(new Account("111-222-33334", member));
        matched.deposit(BigDecimal.valueOf(1000));
        accountRepository.save(matched);
        transactionHistoryRepository.save(
                new TransactionHistory(null, matched, BigDecimal.valueOf(1000), TransactionType.DEPOSIT));

        // 불일치 계좌: 잔고는 1000인데 거래 이력엔 500만 남아있는 상태를 인위로 재현
        Account mismatched = accountRepository.save(new Account("444-555-66668", member));
        mismatched.deposit(BigDecimal.valueOf(1000));
        accountRepository.save(mismatched);
        transactionHistoryRepository.save(
                new TransactionHistory(null, mismatched, BigDecimal.valueOf(500), TransactionType.DEPOSIT));

        JobExecution jobExecution = jobLauncherTestUtils.launchJob(
                new JobParametersBuilder().addLocalDateTime("runAt", LocalDateTime.now()).toJobParameters());

        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Iterator<StepExecution> steps = jobExecution.getStepExecutions().iterator();
        StepExecution step = steps.next();
        assertThat(step.getReadCount()).isEqualTo(2);
        assertThat(step.getWriteCount()).isEqualTo(1);
    }
}
