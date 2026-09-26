package com.trading.account.domain.reconciliation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class BalanceReconciliationScheduler {

    private final JobLauncher jobLauncher;
    private final Job balanceReconciliationJob;

    // 매일 새벽 1시(일 마감 이후, 다음 영업일 개장 전) 실행. 실서비스라면 은행 마감 배치
    // 시간대(자정~새벽)에 맞추되, 실시간 거래가 뜸한 시간대면 언제든 무방.
    @Scheduled(cron = "0 0 1 * * *")
    public void runDailyReconciliation() {
        try {
            // JobParameters에 실행 시각을 넣어 매번 새 JobInstance로 인식시킴 — 안 넣으면
            // Spring Batch가 "이미 완료된 동일 파라미터의 Job"으로 보고 재실행을 거부한다.
            jobLauncher.run(balanceReconciliationJob,
                    new JobParametersBuilder().addLocalDateTime("runAt", LocalDateTime.now()).toJobParameters());
        } catch (Exception e) {
            log.error("일일 잔고 대사 배치 실행 실패", e);
        }
    }
}
