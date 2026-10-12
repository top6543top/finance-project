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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.test.StepScopeTestUtils;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Iterator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

// Job -> Step(Reader/Processor/Writer) 전체 배선이 실제 MySQL(Testcontainers)에서 맞물려
// 동작하는지 검증. "몇 개 계좌를 읽어서 몇 개를 불일치로 판정했는지"는 StepExecution의
// read/write count로 확인 — 로그 내용 자체를 캡처하는 건 배선 검증엔 과함.
@SpringBootTest(properties = "spring.datasource.hikari.transaction-isolation=TRANSACTION_READ_COMMITTED")
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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    private BalanceReconciliationProcessor processor;

    @Test
    void concurrentCommittedDepositDoesNotProduceFalseMismatchWithinChunk() throws Exception {
        Member member = memberRepository.save(new Member("snapshot", "snapshot@example.com", "password123!"));
        Account account = accountRepository.save(new Account("111-222-33335", member));
        account.deposit(BigDecimal.valueOf(1000));
        accountRepository.save(account);
        transactionHistoryRepository.save(new TransactionHistory(null, account, BigDecimal.valueOf(1000), TransactionType.DEPOSIT));
        doAnswer(invocation -> {
            Account alreadyRead = invocation.getArgument(0);
            CompletableFuture.runAsync(() -> new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
                Account updated = accountRepository.findById(alreadyRead.getId()).orElseThrow();
                updated.deposit(BigDecimal.valueOf(500));
                transactionHistoryRepository.save(new TransactionHistory(null, updated, BigDecimal.valueOf(500), TransactionType.DEPOSIT));
            })).get(20, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(processor).process(any(Account.class));

        JobExecution execution = jobLauncherTestUtils.launchJob(new JobParametersBuilder()
                .addLocalDate("scheduleDate", LocalDate.of(2040, 1, 2)).toJobParameters());
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getStepExecutions().iterator().next().getReadCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from reconciliation_mismatch where run_id = ?",
                Long.class, execution.getId())).isZero();
        assertThat(accountRepository.findById(account.getId()).orElseThrow().getBalance()).isEqualByComparingTo("1500.00");
    }

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
                new JobParametersBuilder().addLocalDate("scheduleDate", LocalDate.of(2040, 1, 1)).toJobParameters());

        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Iterator<StepExecution> steps = jobExecution.getStepExecutions().iterator();
        StepExecution step = steps.next();
        assertThat(step.getReadCount()).isEqualTo(2);
        assertThat(step.getWriteCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from reconciliation_mismatch where run_id = ? and account_id = ?",
                Long.class, jobExecution.getId(), mismatched.getId())).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("select actual_balance from reconciliation_mismatch where run_id = ?",
                BigDecimal.class, jobExecution.getId())).isEqualByComparingTo("1000.00");
        assertThat(jdbcTemplate.queryForObject("select expected_balance from reconciliation_mismatch where run_id = ?",
                BigDecimal.class, jobExecution.getId())).isEqualByComparingTo("500.00");
        assertThat(jdbcTemplate.queryForMap("select basis, status, mismatch_count, resolution_status "
                + "from reconciliation_run r join reconciliation_mismatch m on r.id = m.run_id where r.id = ?", jobExecution.getId()))
                .containsEntry("basis", "CURRENT_LEDGER_CONSISTENCY")
                .containsEntry("status", "COMPLETED")
                .containsEntry("mismatch_count", 1L)
                .containsEntry("resolution_status", "OPEN");
        assertThat(meters.get("reconciliation.mismatches").gauge().value()).isEqualTo(1);
        assertThat(meters.get("reconciliation.last.completed.timestamp").gauge().value()).isPositive();
        assertThat(meters.get("reconciliation.last.success.timestamp").gauge().value()).isZero();
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            new ReconciliationResults(jdbcTemplate, prometheus);
            assertThat(prometheus.scrape()).contains("reconciliation_mismatches_recorded 1.0")
                    .contains("reconciliation_mismatches 1.0")
                    .contains("reconciliation_last_completed_timestamp")
                    .contains("reconciliation_last_success_timestamp");
        } finally {
            prometheus.close();
        }

        // Replaying a committed chunk must preserve one immutable observation per run/account.
        StepScopeTestUtils.doInStepScope(step, () -> {
            @SuppressWarnings("unchecked")
            ItemWriter<BalanceMismatch> writer = context.getBean("balanceMismatchWriter", ItemWriter.class);
            writer.write(new Chunk<>(List.of(new BalanceMismatch(mismatched.getId(), mismatched.getAccountNumber(),
                    BigDecimal.valueOf(2000), BigDecimal.valueOf(700)))));
            return null;
        });
        assertThat(jdbcTemplate.queryForObject("select count(*) from reconciliation_mismatch where run_id = ?",
                Long.class, jobExecution.getId())).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("select actual_balance from reconciliation_mismatch where run_id = ?",
                BigDecimal.class, jobExecution.getId())).isEqualByComparingTo("1000.00");
        assertThat(jdbcTemplate.queryForObject("select expected_balance from reconciliation_mismatch where run_id = ?",
                BigDecimal.class, jobExecution.getId())).isEqualByComparingTo("500.00");
    }
}
