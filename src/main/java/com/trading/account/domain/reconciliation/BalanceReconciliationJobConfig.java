package com.trading.account.domain.reconciliation;

import com.trading.account.common.logging.AccountNumberMasker;
import com.trading.account.domain.account.Account;
import com.trading.account.domain.account.AccountRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;

// 일 마감 대사(Reconciliation, IS-28) — 매일 밤 전체 계좌를 순회하며 Account.balance와
// TransactionHistory 합계가 어긋난 곳이 있는지 검증한다. 불일치를 자동으로 고치지 않고
// 로그만 남기는 이유: 배치가 balance를 임의로 덮어쓰면 그 자체가 새로운 사고 원인이 될 수
// 있어, 사람이 확인할 리포트만 남기는 게 금융권 대사 배치의 관행.
@Slf4j
@Configuration
public class BalanceReconciliationJobConfig {

    private static final int CHUNK_SIZE = 100;

    @Bean
    public Job balanceReconciliationJob(JobRepository jobRepository, Step balanceReconciliationStep) {
        return new JobBuilder("balanceReconciliationJob", jobRepository)
                .start(balanceReconciliationStep)
                .build();
    }

    @Bean
    public Step balanceReconciliationStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                                           AccountRepository accountRepository,
                                           BalanceReconciliationProcessor balanceReconciliationProcessor) {
        // 비즈니스 데이터는 읽기만 하지만 청크 트랜잭션을 readOnly로 두면 안 된다 — Spring Batch가
        // 청크마다 같은 트랜잭션 안에서 BATCH_STEP_EXECUTION(_CONTEXT)에 진행 상태를 UPDATE하기 때문.
        // readOnly로 두면 MySQL이 "Connection is read-only"로 거부해 스텝이 FAILED 된다
        // (Testcontainers 통합테스트에서 실제로 재현됨).
        return new StepBuilder("balanceReconciliationStep", jobRepository)
                .<Account, BalanceMismatch>chunk(CHUNK_SIZE, transactionManager)
                .reader(accountReader(accountRepository))
                .processor(balanceReconciliationProcessor)
                .writer(mismatchLogWriter())
                .build();
    }

    private RepositoryItemReader<Account> accountReader(AccountRepository accountRepository) {
        RepositoryItemReader<Account> reader = new RepositoryItemReader<>();
        reader.setRepository(accountRepository);
        reader.setMethodName("findAll");
        reader.setSort(Map.of("id", Sort.Direction.ASC));
        return reader;
    }

    private ItemWriter<BalanceMismatch> mismatchLogWriter() {
        return chunk -> chunk.getItems().forEach(mismatch ->
                log.warn("잔고 불일치 발견: account={}, 실제잔고={}, 거래이력합계={}",
                        AccountNumberMasker.mask(mismatch.accountNumber()),
                        mismatch.actualBalance(), mismatch.expectedBalance()));
    }
}
