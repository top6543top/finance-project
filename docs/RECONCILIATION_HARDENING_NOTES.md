# 대사 기능 보강 기록 (R11–R13)

## 이 작업이 확인하는 것
이 배치는 **현재 계좌 잔액과 현재 거래 이력 합계가 일치하는지** 검사합니다. 과거 날짜의 확정 마감 잔액을 계산하는 기능은 아닙니다.

`scheduleDate`는 한국 시간(Asia/Seoul)으로 검사를 수행할 날짜입니다. 매일 01:00부터 그 날짜의 검사가 실행 대상이 됩니다. 이 값으로 거래 이력의 날짜를 제한하지 않습니다.

계좌를 100개씩 묶어 처리합니다. 같은 묶음 안에서는 MySQL의 REPEATABLE READ 트랜잭션으로 계좌 잔액과 거래 이력 합계를 읽습니다. 검사 도중 입금이 완료되어도 두 값을 서로 다른 시점의 데이터로 비교하지 않도록 한 것입니다. 다만 **묶음마다 읽는 시점은 다르므로 전체 계좌를 한 시점에 마감한 결과로 볼 수 없습니다.**

`observed_started_at`과 각 결과의 `observed_at`은 UTC 기준 검사 시작/결과 기록 시각입니다. 과거 시점의 잔액을 보장하는 마감 기준 시각이 아닙니다. 과거 잔액 스냅샷이 없는 상태에서 거래 이력에 날짜 조건만 붙이면 현재 잔액과 비교 기준이 달라집니다.

## 불일치 결과를 남기는 방법
Spring Batch가 실행을 시작할 때마다 `reconciliation_run`에 실행 기록을 남깁니다. 기본 키는 `JOB_EXECUTION_ID`입니다.

실패 후 재시도는 새 실행 ID로 전체 계좌를 다시 검사합니다. 이전 실행에서 이미 저장한 불일치 결과는 보존합니다. 같은 실행에서 동일 계좌를 다시 저장하더라도 `(run_id, account_id)` 고유 제약과 중복 시 값을 바꾸지 않는 저장 방식으로 최초 결과 하나만 유지합니다.

계좌 식별자와 잔액은 접근 권한을 제한한 DB 결과 테이블에만 저장합니다. 결과를 공개하는 API나 금액/계좌를 출력하는 애플리케이션 로그는 추가하지 않았습니다.

불일치 결과의 `resolution_status`는 처음에 `OPEN`입니다. 권한 있는 운영자가 조사한 뒤 DB에서 `RESOLVED`로 바꿀 수 있습니다. 이 상태 변경은 거래 이력이나 계좌 잔액을 수정하지 않습니다. 과거에 불일치가 있었다는 기록과 지표도 유지합니다.

**배치가 기술적으로 완료된 것과 잔액이 모두 일치하는 것은 별개입니다.** 불일치가 있어도 검사를 끝까지 수행했다면 Batch 상태는 COMPLETED가 될 수 있습니다. 운영 알림은 불일치 개수와 마지막 정상 검사 시각을 함께 확인해야 합니다.

## 실행 일정과 재시도
스케줄러는 기본적으로 5분마다 실행 대상 여부를 확인합니다.

- 01:00 전에는 그날 검사를 시작하지 않습니다.
- 01:00 이후 애플리케이션이 늦게 켜져도 당일 검사가 없으면 실행합니다.
- 당일 실행이 FAILED이면 새 실행으로 재시도합니다.
- 이미 완료되었거나 실행 중이면 Spring Batch의 동일 날짜 JobInstance 보호 기능으로 중복 실행을 막습니다.
- 지난 날짜의 검사를 과거 마감인 것처럼 새로 만들어 채우지 않습니다. 다시 켜진 시점의 당일 현재 상태를 검사합니다.

오래된 STARTED 기록만 보고 죽은 작업이라고 판단하거나 자동 초기화하지 않습니다. 실제 작업이 살아 있을 수도 있으므로, 중단된 실행의 복구는 아래의 오프라인 절차로만 수행합니다.

## 운영 지표
실행 시작 실패 카운터를 제외하면 모두 DB의 기록을 읽는 gauge 지표입니다. 애플리케이션 복제본마다 같은 값을 내보내므로 복제본 사이에서는 합계(sum)가 아니라 최댓값(max)을 사용합니다.

| Prometheus 지표 | 의미 |
| --- | --- |
| `reconciliation_last_completed_timestamp` | 마지막으로 검사를 끝낸 시각. UTC epoch 초이며 완료 기록이 없으면 0입니다. |
| `reconciliation_last_success_timestamp` | 마지막으로 불일치 없이 검사를 끝낸 시각. 해당 기록이 없으면 0입니다. |
| `reconciliation_mismatches` | 가장 최근에 완료한 검사의 불일치 개수입니다. |
| `reconciliation_mismatches_recorded` | 실패한 검사의 일부 결과까지 포함한 전체 저장 불일치 건수입니다. 실행/계좌별 한 건으로 셉니다. |
| `reconciliation_runs_failed` | DB에 남아 있는 FAILED 실행의 누적 개수입니다. |
| `reconciliation_launch_failures_total` | 현재 프로세스에서 배치 시작 중 예외가 발생한 횟수입니다. |

`reconciliation_runs_failed`는 누적값이므로 단순히 0보다 크다는 조건으로 영구 경보를 만들지 않습니다. 일정 시간 동안 증가했는지, 마지막 완료 시각이 오래되었는지 함께 판단합니다.

Prometheus는 gauge 이름의 `_total` 접미사를 제거합니다. 최신 불일치 개수와 이름이 충돌하지 않도록 누적 저장 건수에는 `recorded`를 사용했습니다. 실제 Prometheus 출력으로 두 지표가 모두 노출되는지 검증했습니다.

## DB 스키마 약속
테이블 생성은 통합 작업의 Flyway 마이그레이션이 담당하고, 운영 시작 시 JPA 매핑과 스키마가 맞는지 검증합니다. 애플리케이션이 운영 테이블을 자동 변경할 필요는 없습니다.

### reconciliation_run

| 컬럼 | 타입/조건 |
| --- | --- |
| `id` | BIGINT PRIMARY KEY. Batch 실행 ID이며 자동 증가하지 않습니다. |
| `job_instance_id` | BIGINT NOT NULL |
| `schedule_date` | DATE NOT NULL |
| `basis` | VARCHAR(48) NOT NULL. 값은 `CURRENT_LEDGER_CONSISTENCY`입니다. |
| `observed_started_at` | DATETIME(6) NOT NULL, UTC |
| `completed_at` | DATETIME(6) NULL, UTC |
| `status` | VARCHAR(16) NOT NULL |
| `mismatch_count` | BIGINT NOT NULL DEFAULT 0 |

인덱스는 `idx_reconciliation_completed(completed_at)`와 `idx_reconciliation_schedule(schedule_date)`입니다.

### reconciliation_mismatch

| 컬럼 | 타입/조건 |
| --- | --- |
| `id` | BIGINT AUTO_INCREMENT PRIMARY KEY |
| `run_id` | BIGINT NOT NULL, `reconciliation_run(id)` 외래 키 |
| `account_id` | BIGINT NOT NULL, `account(id)` 외래 키 |
| `observed_at` | DATETIME(6) NOT NULL, UTC |
| `actual_balance` | DECIMAL(19,2) NOT NULL |
| `expected_balance` | DECIMAL(19,2) NOT NULL |
| `resolution_status` | VARCHAR(16) NOT NULL DEFAULT 'OPEN' |

`uk_reconciliation_run_account(run_id, account_id)` 고유 제약을 둡니다. MySQL은 외래 키용 인덱스도 생성합니다.

실행 ID에는 Batch 메타데이터로 향하는 외래 키를 만들지 않습니다. 오래된 Batch 메타데이터를 별도로 보관하거나 정리하더라도 불일치 결과를 유지하기 위해서입니다.

## 중단된 실행을 복구하는 절차
작업이 STARTING/STARTED/STOPPING 상태이면 실행 시각이 오래되어도 자동으로 상태를 바꾸지 않습니다. 다음 절차는 **모든 실행기를 중지한 점검 시간**에만 수행합니다.

1. 복구할 정확한 `JOB_EXECUTION_ID`를 확인하고 DB 백업과 장애 증거를 보존합니다.
2. 배포/GitOps/수동 실행기를 중지하고 HPA를 비활성화합니다. 모든 작업 복제본을 0으로 줄인 다음 Pod와 프로세스가 완전히 종료될 때까지 기다립니다.
3. `mysql_config_editor`로 운영자 전용 MySQL login path를 준비합니다. 비밀번호를 명령행 인자로 넘기지 않습니다.
4. 아래 명령에서 실행 ID, 네임스페이스, Deployment, DB와 login path를 실제 값으로 바꾸어 실행합니다.

```powershell
./scripts/recover-reconciliation.ps1 -ExecutionId 123 -Namespace trading -Deployment account -Database account -MysqlLoginPath reconciliation-operator -AllOtherLaunchersStopped
```

5. 스크립트는 Deployment 복제본이 0인지, 종료 중인 Pod를 포함해 해당 Pod가 하나도 없는지, 연결된 HPA가 없는지 확인합니다. 그 뒤 지정한 실행이 해당 대사 작업의 활성 실행일 때만 DB 행을 잠그고, 실행과 활성 단계 상태를 한 트랜잭션에서 FAILED로 바꿉니다. 메타데이터 버전도 올리며 불일치 결과는 지우지 않습니다. 이미 종료되었거나 복구된 실행은 변경하지 않습니다.
6. 작업과 컨트롤러를 다시 켭니다. 스케줄러가 당일 검사를 재시도합니다. 전날 이전의 중단 실행은 실패 기록으로 보존하며, 과거 마감 결과로 다시 만들지 않습니다.

**남아 있는 운영 조건:** 이 스크립트는 DB lease로 작업 실행을 강제로 차단하는 구조가 아닙니다. 운영자가 복구 트랜잭션이 끝날 때까지 모든 실행기와 자동 확장/배포 컨트롤러를 중지해 두어야 합니다. 스크립트는 선택한 Deployment 밖의 작업까지 자동으로 확인할 수 없으므로 `-AllOtherLaunchersStopped` 확인 인자를 요구합니다. 다른 작업이 살아 있거나 자동으로 다시 켜질 수 있는 상황에서는 실행하면 안 됩니다.

스크립트 실행에는 `kubectl`, MySQL 클라이언트와 필요한 운영자 권한이 필요합니다. 제공한 SQL은 로컬 MySQL 통합 테스트로 검증했으며, 실제 운영 DB 복구나 배포는 수행하지 않았습니다.

## 규모가 커질 때 남는 과제
Reader와 Writer는 각 Step 실행마다 별도로 만들어집니다. 한 번에 100개 계좌만 처리하므로 읽기 메모리는 제한됩니다.

다만 OFFSET 페이지 조회와 계좌별 전체 거래 이력 합계 계산은 거래 이력이 커질수록 오래 걸립니다. 검사 중 계좌 행을 삭제하지 않는 것도 전제입니다. 현재 애플리케이션에는 계좌 삭제 경로가 없습니다.

운영에 적용하기 전에 실제 크기의 거래 이력으로 검사 시간과 DB 부하를 측정해야 합니다. 과거 확정 잔액 스냅샷, 대용량 이력의 증분 집계, 결과 보관 기간, 조사 화면, 자동 장애 복구용 실행 차단 장치, 재무 기준에 맞는 과거 마감 설계는 별도 과제입니다.

## 검증 결과
Java 17, Gradle 9.4.1, MySQL 8.0 Testcontainers로 확인했습니다. H2는 사용하지 않았습니다.

```text
gradle --offline --no-daemon test --tests '*BalanceReconciliation*'
```

2026-09-29 최종 결과는 **8개 통과, 실패 0개, 건너뜀 0개**입니다. 마지막 명령의 54분 실행 시간에는 호스트 일시 정지 시간이 포함되어 있습니다. 배치 성능 측정값이 아닙니다.

- 최초 저장 검증은 `reconciliation_mismatch` 테이블이 없어 실패했습니다. 수정 후 실행 기준/상태/개수와 잔액 저장을 확인했고, 다른 값으로 같은 결과를 다시 써도 최초 증거가 유지됨을 확인했습니다.
- 최초 스케줄 검증은 기존 코드가 오늘의 `scheduleDate` 실행을 만들지 않아 실패했습니다. 수정 후 고정 시각 테스트로 01:00 전 건너뜀, 늦은 시작, 실패 재시도, 완료 실행 중복 방지, 오래된 STARTED 보호를 확인했습니다.
- 계좌를 읽은 직후 다른 스레드에서 입금을 완료했습니다. 데이터소스 기본 격리 수준을 READ_COMMITTED로 낮춰도 배치가 명시한 REPEATABLE READ 덕분에 거짓 불일치가 생기지 않았고, 실제 계좌 잔액에는 입금이 반영되었습니다.
- Prometheus 출력 검증에서 `_total` gauge 이름 충돌을 재현한 뒤 수정했습니다. 최신 불일치 개수와 누적 저장 건수가 모두 출력됩니다.
- 제공한 복구 SQL을 MySQL에서 실행했습니다. 지정한 활성 실행/단계만 FAILED로 바뀌고 새 실행으로 재시도할 수 있으며, 이미 완료/복구된 실행은 변경하지 않는 것을 확인했습니다.
- PowerShell 스크립트를 확인 인자 없이 실행해, 외부 명령 실행 전에 거부하는 것도 확인했습니다.

분리 작업본의 기존 `create-drop` 설정에서는 여러 테스트 컨텍스트 종료 시 외래 키 삭제 경고가 남았습니다. 통합 작업의 Flyway/validate 설정으로 해결하는 항목이며, 테스트를 숨기거나 비활성화하지 않았습니다.

## 변경 파일
운영 코드: `src/main/java/com/trading/account/domain/reconciliation/`

- `BalanceMismatch.java`
- `BalanceReconciliationJobConfig.java`
- `BalanceReconciliationProcessor.java`
- `BalanceReconciliationScheduler.java`
- `ReconciliationRun.java`
- `ReconciliationMismatch.java`
- `ReconciliationResults.java`

테스트: `src/test/java/com/trading/account/domain/reconciliation/`

- `BalanceReconciliationJobIntegrationTest.java`: 2개 통과
- `BalanceReconciliationSchedulerIntegrationTest.java`: 4개 통과
- 기존 `BalanceReconciliationProcessorTest.java`: 수정 없이 2개 통과

운영 보조 파일은 `scripts/recover-reconciliation.ps1`, `scripts/recover-reconciliation.sql`, 이 문서입니다.
