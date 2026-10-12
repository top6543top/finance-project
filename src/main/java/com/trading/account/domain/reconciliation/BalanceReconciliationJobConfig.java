package com.trading.account.domain.reconciliation;

import com.trading.account.domain.account.Account;
import com.trading.account.domain.account.AccountRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.*;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;

import java.util.Map;

/** Current consistency scan, not a historical closing balance or business-day settlement. */
@Slf4j
@Configuration
public class BalanceReconciliationJobConfig {
    private static final int CHUNK_SIZE = 100;

    @Bean
    public Job balanceReconciliationJob(JobRepository repository, Step balanceReconciliationStep,
                                       ReconciliationResults results) {
        return new JobBuilder("balanceReconciliationJob", repository)
                .validator(parameters -> {
                    if (parameters.getLocalDate("scheduleDate") == null) {
                        throw new JobParametersInvalidException("scheduleDate (Asia/Seoul scheduling date) is required");
                    }
                })
                .listener(new JobExecutionListener() {
                    @Override public void beforeJob(JobExecution execution) { results.started(execution); }
                    @Override public void afterJob(JobExecution execution) {
                        try {
                            results.finished(execution);
                        } catch (RuntimeException failure) {
                            // Batch swallows listener exceptions; explicitly fail the execution as well.
                            execution.setStatus(BatchStatus.FAILED);
                            execution.setExitStatus(ExitStatus.FAILED.addExitDescription("Reconciliation result persistence failed"));
                            log.error("Reconciliation result persistence failed: runId={}, errorType={}",
                                    execution.getId(), failure.getClass().getSimpleName());
                        }
                    }
                })
                .start(balanceReconciliationStep).build();
    }

    @Bean
    public Step balanceReconciliationStep(JobRepository repository, PlatformTransactionManager transactionManager,
                                         RepositoryItemReader<Account> reconciliationAccountReader,
                                         BalanceReconciliationProcessor processor, ItemWriter<BalanceMismatch> balanceMismatchWriter) {
        DefaultTransactionAttribute transaction = new DefaultTransactionAttribute() {
            @Override public boolean rollbackOn(Throwable failure) { return true; }
        };
        // Same MySQL snapshot for account reads and ledger sums within each chunk, independent of server defaults.
        // Remains writable because Batch checkpoints and mismatch evidence commit with the chunk.
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return new StepBuilder("balanceReconciliationStep", repository)
                .<Account, BalanceMismatch>chunk(CHUNK_SIZE, transactionManager)
                .transactionAttribute(transaction)
                .reader(reconciliationAccountReader).processor(processor).writer(balanceMismatchWriter).build();
    }

    @Bean
    @StepScope
    public RepositoryItemReader<Account> reconciliationAccountReader(AccountRepository repository) {
        RepositoryItemReader<Account> reader = new RepositoryItemReader<>();
        reader.setName("reconciliationAccountReader");
        reader.setRepository(repository);
        reader.setMethodName("findAll");
        reader.setSort(Map.of("id", Sort.Direction.ASC));
        reader.setPageSize(CHUNK_SIZE);
        // A retry is a new observation with a new run id: revisit all accounts, not just the uncommitted tail.
        reader.setSaveState(false);
        // ponytail: OFFSET pages and per-account lifetime SUM are bounded-memory, but ledger growth costs time.
        // Add immutable snapshots/keyset aggregates only after measured scan-duration pressure.
        return reader;
    }

    @Bean
    @StepScope
    public ItemWriter<BalanceMismatch> balanceMismatchWriter(ReconciliationResults results,
                         @Value("#{stepExecution.jobExecution.id}") Long runId) {
        return chunk -> { if (!chunk.isEmpty()) results.write(runId, chunk); };
    }
}
