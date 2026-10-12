# 수정·추가 파일 목록

기준: 7d6f766. 생성물·빌드 캐시·개인 로컬 설정 제외. 변경 의도는 개선 결과와 분야별 노트에서 설명합니다.

| 파일 | 유형 |
|---|---|
| `.dockerignore` | 추가 |
| `.github/ISSUE_TEMPLATE/config.yml` | 수정 |
| `.github/workflows/cd.yml` | 수정 |
| `.github/workflows/ci.yml` | 수정 |
| `Dockerfile` | 수정 |
| `README.md` | 수정 |
| `build.gradle` | 수정 |
| `docs/API_CHANGES.md` | 추가 |
| `docs/CORE_HARDENING_NOTES.md` | 추가 |
| `docs/HARDENING_CHANGED_FILES.md` | 추가 |
| `docs/HARDENING_PLAN.md` | 추가 |
| `docs/HARDENING_REPORT.md` | 추가 |
| `docs/HISTORICAL_CONCURRENCY_NOTES.md` | 추가 |
| `docs/OPERATIONS_RUNBOOK.md` | 추가 |
| `docs/RECONCILIATION_HARDENING_NOTES.md` | 추가 |
| `docs/SECURITY_HARDENING_NOTES.md` | 추가 |
| `docs/account_hardening_report_2026-09-29.pdf` | 추가 |
| `k8s/deployment.yaml` | 수정 |
| `k8s/ingress.yaml` | 수정 |
| `k8s/migration-job.yaml` | 추가 |
| `k8s/networkpolicy.yaml` | 추가 |
| `k8s/prometheusrule.yaml` | 수정 |
| `k8s/service.yaml` | 수정 |
| `scripts/recover-reconciliation.ps1` | 추가 |
| `scripts/recover-reconciliation.sql` | 추가 |
| `scripts/render_manifests.py` | 추가 |
| `scripts/test_render_manifests.py` | 추가 |
| `src/main/java/com/trading/account/common/config/DatabaseMigration.java` | 추가 |
| `src/main/java/com/trading/account/common/exception/ErrorCode.java` | 수정 |
| `src/main/java/com/trading/account/common/exception/GlobalExceptionHandler.java` | 수정 |
| `src/main/java/com/trading/account/common/ratelimit/RateLimitFilter.java` | 수정 |
| `src/main/java/com/trading/account/common/ratelimit/RateLimitProperties.java` | 수정 |
| `src/main/java/com/trading/account/common/ratelimit/TokenBucketRateLimiter.java` | 수정 |
| `src/main/java/com/trading/account/common/security/JwtAuthenticationFilter.java` | 수정 |
| `src/main/java/com/trading/account/common/security/JwtTokenProvider.java` | 수정 |
| `src/main/java/com/trading/account/common/security/SecurityConfig.java` | 수정 |
| `src/main/java/com/trading/account/domain/account/Account.java` | 수정 |
| `src/main/java/com/trading/account/domain/account/AccountRepository.java` | 수정 |
| `src/main/java/com/trading/account/domain/account/MoneyAmount.java` | 추가 |
| `src/main/java/com/trading/account/domain/auth/dto/LoginReqDto.java` | 수정 |
| `src/main/java/com/trading/account/domain/idempotency/IdempotencyKey.java` | 수정 |
| `src/main/java/com/trading/account/domain/idempotency/IdempotencyKeyRepository.java` | 수정 |
| `src/main/java/com/trading/account/domain/idempotency/IdempotencyService.java` | 수정 |
| `src/main/java/com/trading/account/domain/member/dto/MemberCreateReqDto.java` | 수정 |
| `src/main/java/com/trading/account/domain/reconciliation/BalanceMismatch.java` | 수정 |
| `src/main/java/com/trading/account/domain/reconciliation/BalanceReconciliationJobConfig.java` | 수정 |
| `src/main/java/com/trading/account/domain/reconciliation/BalanceReconciliationProcessor.java` | 수정 |
| `src/main/java/com/trading/account/domain/reconciliation/BalanceReconciliationScheduler.java` | 수정 |
| `src/main/java/com/trading/account/domain/reconciliation/ReconciliationMismatch.java` | 추가 |
| `src/main/java/com/trading/account/domain/reconciliation/ReconciliationResults.java` | 추가 |
| `src/main/java/com/trading/account/domain/reconciliation/ReconciliationRun.java` | 추가 |
| `src/main/java/com/trading/account/domain/transaction/TransactionController.java` | 수정 |
| `src/main/java/com/trading/account/domain/transaction/TransactionHistory.java` | 수정 |
| `src/main/java/com/trading/account/domain/transaction/TransactionHistoryRepository.java` | 수정 |
| `src/main/java/com/trading/account/domain/transaction/TransactionService.java` | 수정 |
| `src/main/java/com/trading/account/domain/transaction/dto/DepositReqDto.java` | 수정 |
| `src/main/java/com/trading/account/domain/transaction/dto/TransactionHistoryCursorResDto.java` | 추가 |
| `src/main/java/com/trading/account/domain/transaction/dto/TransactionHistoryResDto.java` | 수정 |
| `src/main/java/com/trading/account/domain/transaction/dto/TransactionResDto.java` | 수정 |
| `src/main/java/com/trading/account/domain/transaction/dto/TransferReqDto.java` | 수정 |
| `src/main/java/com/trading/account/domain/transaction/dto/TransferResDto.java` | 수정 |
| `src/main/java/com/trading/account/domain/transaction/dto/WithdrawReqDto.java` | 수정 |
| `src/main/resources/application-common.yaml` | 추가 |
| `src/main/resources/application-dev.yaml` | 추가 |
| `src/main/resources/application-prod.yaml` | 추가 |
| `src/main/resources/application.yaml` | 수정 |
| `src/main/resources/db/migration/V1__original_business_schema.sql` | 추가 |
| `src/main/resources/db/migration/V2__spring_batch_5_2_schema.sql` | 추가 |
| `src/main/resources/db/migration/V3__atomic_request_schema.sql` | 추가 |
| `src/main/resources/db/migration/V4__reconciliation_evidence.sql` | 추가 |
| `src/test/java/com/trading/account/AbstractIntegrationTest.java` | 수정 |
| `src/test/java/com/trading/account/AccountApplicationTests.java` | 수정 |
| `src/test/java/com/trading/account/DatabaseMigrationCommandTest.java` | 추가 |
| `src/test/java/com/trading/account/FinancialHardeningIntegrationTest.java` | 추가 |
| `src/test/java/com/trading/account/ProductionBoundaryIntegrationTest.java` | 추가 |
| `src/test/java/com/trading/account/SchemaMigrationTest.java` | 추가 |
| `src/test/java/com/trading/account/common/exception/ExceptionLoggingSafetyTest.java` | 추가 |
| `src/test/java/com/trading/account/common/ratelimit/RateLimitFilterTest.java` | 수정 |
| `src/test/java/com/trading/account/common/ratelimit/RateLimitSecurityTest.java` | 추가 |
| `src/test/java/com/trading/account/common/ratelimit/RedisTokenBucketContractTest.java` | 추가 |
| `src/test/java/com/trading/account/common/security/CredentialInputTest.java` | 추가 |
| `src/test/java/com/trading/account/common/security/JwtAuthenticationFilterTest.java` | 추가 |
| `src/test/java/com/trading/account/common/security/SecurityBoundaryTest.java` | 추가 |
| `src/test/java/com/trading/account/domain/idempotency/IdempotencyConstraintTest.java` | 추가 |
| `src/test/java/com/trading/account/domain/idempotency/IdempotencyServiceTest.java` | 수정 |
| `src/test/java/com/trading/account/domain/member/MemberRepositoryTest.java` | 수정 |
| `src/test/java/com/trading/account/domain/reconciliation/BalanceReconciliationJobIntegrationTest.java` | 수정 |
| `src/test/java/com/trading/account/domain/reconciliation/BalanceReconciliationSchedulerIntegrationTest.java` | 추가 |
| `src/test/java/com/trading/account/domain/transaction/PessimisticLockWithdrawService.java` | 수정 |
| `src/test/java/com/trading/account/domain/transaction/SimulatedDepositDisabledTest.java` | 추가 |
| `src/test/java/com/trading/account/domain/transaction/TransactionConcurrencyTest.java` | 수정 |
| `src/test/java/com/trading/account/domain/transaction/TransactionControllerTest.java` | 수정 |
| `src/test/java/com/trading/account/domain/transaction/TransactionServiceTest.java` | 수정 |
| `src/test/resources/application.yaml` | 수정 |

## 영역별 역할

| 경로 | 담당 역할 |
|---|---|
| domain/account, transaction, idempotency | 금액 검증, 잔액·원장 원자성, 요청 중복 방지, 조회 |
| common/security, ratelimit, exception | 인증·인가, 입력 경계, 요청 제한, 안전한 오류 로그 |
| domain/reconciliation | 현재 원장 정합성 검사, 증거 저장, 실행 상태와 지표 |
| common/config/DatabaseMigration, db/migration | 별도 릴리스 명령과 버전별 스키마 이전 |
| src/test | 위 동작의 단위·HTTP·MySQL·Redis 회귀 검증 |
| application-*.yaml | 개발/운영 환경 분리와 안전한 기본 설정 |
| k8s, .github/workflows, Dockerfile | 테스트에 연결된 이미지/배포, TLS·네트워크·프로브 |
| scripts | 배포 입력 검증과 중단된 대사 실행의 복구 |
| docs, README | 수정 근거, API 계약, 운영 절차, 기존 학습 기록 보존 |
