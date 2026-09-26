package com.trading.account.domain.reconciliation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

// "언제 도는지"만 담당. test 설정에서 꺼서 CI가 우연히 01:00(UTC)에 돌 때 테스트 도중
// 배치가 끼어들지 않게 한다 — Job 자체는 테스트에서 JobLauncherTestUtils로 직접 실행.
@Slf4j
@Component
@ConditionalOnProperty(name = "reconciliation.scheduler.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class BalanceReconciliationScheduler {

    private static final String ZONE = "Asia/Seoul";

    private final JobLauncher jobLauncher;
    private final Job balanceReconciliationJob;

    // 컨테이너는 UTC라 zone 없이 두면 한국시간 오전 10시(거래 피크)에 전체 스캔이 돈다.
    // 새벽 1시 = 전날 영업 마감 이후, 거래가 가장 뜸한 시간대.
    @Scheduled(cron = "0 0 1 * * *", zone = ZONE)
    public void runDailyReconciliation() {
        // Pod가 여러 대면 @Scheduled가 Pod마다 돈다. 파라미터를 "검증 대상 영업일"로 두면
        // 같은 날짜 = 같은 JobInstance라서 Spring Batch 메타테이블이 중복 실행을 막아준다
        // (실행 시각을 넣으면 밀리초만 달라도 다른 Job으로 보여 Pod 수만큼 중복 실행됨).
        // ponytail: 두 Pod가 동시에 JobInstance를 만드는 극단적 경합은 메타테이블 unique 제약에
        // 기대는 중. 배치 외 스케줄 작업이 늘면 ShedLock 같은 분산 락으로.
        // ponytail: 실행 중 Pod가 죽으면(배포/스케일인) JobExecution이 STARTED로 남아 같은 날짜 재실행이
        // 막힌다. 새벽 1시라 배포와 거의 안 겹침 — 겹치기 시작하면 STARTED→FAILED 정리 후 재시작 로직 추가.
        LocalDate businessDate = LocalDate.now(ZoneId.of(ZONE)).minusDays(1);
        try {
            JobExecution execution = jobLauncher.run(balanceReconciliationJob,
                    new JobParametersBuilder().addLocalDate("businessDate", businessDate).toJobParameters());
            // Job 내부 실패는 예외가 아니라 FAILED 상태의 JobExecution으로 돌아온다 — 반환값을 봐야 감지된다.
            if (execution.getStatus() != BatchStatus.COMPLETED) {
                log.error("일일 잔고 대사 배치 비정상 종료: businessDate={}, status={}, exitStatus={}",
                        businessDate, execution.getStatus(), execution.getExitStatus());
            }
        } catch (JobInstanceAlreadyCompleteException | JobExecutionAlreadyRunningException e) {
            // 다른 Pod가 이미 돌았거나 돌고 있는 정상 상황 — 장애가 아니므로 INFO
            log.info("일일 잔고 대사 배치 스킵(다른 인스턴스에서 실행됨): businessDate={}", businessDate);
        } catch (Exception e) {
            log.error("일일 잔고 대사 배치 실행 실패: businessDate={}", businessDate, e);
        }
    }
}
