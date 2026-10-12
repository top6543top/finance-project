# 계좌 서비스 전체 개선 결과와 실무 재검토

2026-09-29 / 기준 커밋 7d6f766 / 최종 Java 134개 통과, 실패·오류·건너뜀 0.

실무의 기본 통제에 가까워졌습니다. 실제 자금 운영에는 외부 입금 확인, 업무 한도·역분개·마감과 운영 환경의 증거가 추가로 필요합니다. 운영 배포나 실제 DB 이전은 실행하지 않았습니다.

읽기 좋은 전체 설명은 같은 폴더의 account_hardening_report_2026-09-29.pdf를 참고합니다.

## 문제별 수정과 검증

### R01. 거래와 요청 결과의 원자성

**이전 문제:** 완료 기록 저장 실패 후 재시도하면 돈이 두 번 반영될 수 있었습니다.

**수정:** 키 선점, 잔액 변경, 원장 기록, 응답 완료를 한 MySQL 트랜잭션에 묶었습니다. 재시도는 커밋을 포함한 전체 경계를 다시 시작합니다.

**검증과 한계:** 완료 UPDATE·이력 INSERT·응답 직렬화 실패 시 잔액/원장/키 모두 롤백. 같은 키 재시도는 1회 반영.

코드: `src/main/java/com/trading/account/domain/idempotency/IdempotencyService.java:47`

### R02. 사용자와 요청 내용에 묶인 키

**이전 문제:** 전역 키만 같으면 타 사용자 또는 다른 작업의 과거 응답을 받을 수 있었습니다.

**수정:** 사용자+키를 유일하게 저장하고 작업·계좌·정규화 금액 지문을 비교합니다. 수취인 변경도 거절합니다.

**검증과 한계:** 타 사용자 소유 계좌 접근 거절, 다른 금액/작업/수취인 C017, 동등한 금액 표현은 같은 거래 ID.

코드: `src/main/java/com/trading/account/domain/transaction/TransactionController.java:45`

### R03. 금액 단위와 잔액 상한

**이전 문제:** 0.015 이체가 DB에서 서로 다르게 반올림되어 총액을 바꾸었습니다.

**수정:** DTO와 서비스에 공통 금액 검증을 적용하고 잔액 상한을 조건부 UPDATE에 넣었습니다.

**검증과 한계:** 과도한 소수·음수·0·범위 초과 거절. 수취 계좌 상한 초과 시 출금도 롤백.

코드: `src/main/java/com/trading/account/domain/account/MoneyAmount.java:14`

### R04. 모의 입금의 운영 차단

**이전 문제:** 계좌 소유자가 입금 API로 잔액을 임의 생성할 수 있었습니다.

**수정:** 기본 비활성화하고 prod에서 항상 차단합니다. dev에서 명시적으로 켠 경우에만 학습용 입금을 허용합니다.

**검증과 한계:** 실제 HTTP 운영 프로필에서 인증한 소유자도 403/C019, 잔액 0 유지. 실제 입금 확인 연동은 별도 범위.

코드: `src/main/java/com/trading/account/domain/transaction/TransactionService.java:34`

### R05. 민감값 없는 오류 로그

**이전 문제:** 검증 오류와 DB 오류 원문에 비밀번호·이메일·요청 값이 남을 수 있었습니다.

**수정:** 안전한 코드·예외 종류만 기록합니다. SQL/bind와 SqlExceptionHelper 원문 로그를 차단했습니다.

**검증과 한계:** 잘못된 DTO/JWT 입력과 실제 MySQL 중복 INSERT의 루트 로그·예외 스택에서 민감값 미노출 확인.

코드: `src/main/java/com/trading/account/common/exception/GlobalExceptionHandler.java:19`

### R06. 전달 헤더와 로그인 제한

**이전 문제:** 클라이언트가 보낸 X-Forwarded-For로 제한 대상을 바꾸거나 Redis 장애를 우회할 수 있었습니다.

**수정:** 연결 remoteAddr만 사용하고 전달 헤더 처리를 끕니다. Redis 장애는 503으로 거절합니다.

**검증과 한계:** 위조 헤더로 키가 바뀌지 않음, 장애 503. 프록시/NAT 고객별 제한은 경계 장비 정책 검증 필요.

코드: `src/main/java/com/trading/account/common/ratelimit/RateLimitFilter.java:73`

### R07. 운영 Redis 연결과 제한 정책

**이전 문제:** 제공된 배포에 필요한 Redis 설정이 없었고 제한 장애를 허용했습니다.

**수정:** 호스트·비밀번호·TLS를 환경에서 주입합니다. Redis TIME과 정확한 버킷 TTL을 사용합니다.

**검증과 한계:** 실제 Redis 50개 동시 요청 중 허용 수 5 확인, refill/TTL 및 잘못된 설정 거절. 실환경 인증/TLS는 별도 확인.

코드: `src/main/java/com/trading/account/common/ratelimit/TokenBucketRateLimiter.java:21`

### R08. 검증 가능한 DB 변경

**이전 문제:** 운영 자동 DDL로 변경 이력과 사전 검증을 통제하기 어려웠습니다.

**수정:** Flyway V1~V4, prod validate, 별도 마이그레이션 명령과 계정을 도입했습니다. 자동 baseline/clean은 금지합니다.

**검증과 한계:** 빈 DB 설치·재실행·명시 baseline 이전, 잔액/이력/키 보존 및 PAD/NO PAD 문자 규칙을 검증.

코드: `src/main/java/com/trading/account/common/config/DatabaseMigration.java:28`

### R09. 공개 TLS와 네트워크 경계

**이전 문제:** 제공된 Ingress에 외부 TLS와 관리 포트 접근 경계가 충분히 드러나지 않았습니다.

**수정:** websecure+TLS Secret, /api 라우팅, 8080/9090 NetworkPolicy를 제공합니다.

**검증과 한계:** YAML·템플릿 검증 완료. 인증서, Traefik 라벨, CNI 정책 적용과 외부 차단은 실제 클러스터 확인 필요.

코드: `k8s/ingress.yaml:8`

### R10. 커밋된 거래의 감사 연결

**이전 문제:** 커밋 전 성공 로그와 식별자 부족으로 실제 확정 거래를 추적하기 어려웠습니다.

**수정:** 원장에 actor/idempotency 참조를 저장하고 응답에 거래 ID를 추가합니다. 성공 로그는 afterCommit에서만 남깁니다.

**검증과 한계:** 롤백된 거래의 성공 로그 0건. 확정 원장·요청·수행자 ID 연결 확인. 별도 불변 감사 저장소는 추가 운영 범위.

코드: `src/main/java/com/trading/account/domain/transaction/TransactionService.java:146`

### R11. 대사의 의미와 읽기 일관성

**이전 문제:** 현재 데이터를 조회하면서 일별 마감처럼 해석할 여지가 있었습니다.

**수정:** CURRENT_LEDGER_CONSISTENCY로 의미를 명확히 하고 청크별 REPEATABLE_READ와 관측 시각을 적용합니다.

**검증과 한계:** 계좌 조회 뒤 입금이 커밋되어도 같은 청크의 원장 합계와 비교해 거짓 불일치를 만들지 않음. 과거 일마감은 구현 범위 밖.

코드: `src/main/java/com/trading/account/domain/reconciliation/BalanceReconciliationJobConfig.java:64`

### R12. 불일치 증거와 알림

**이전 문제:** 대사 불일치가 로그에만 남아 조사·해결 상태를 관리하기 어려웠습니다.

**수정:** 실행/불일치 테이블, 원본 증거 보존 upsert, 상태와 DB 기반 지표 및 경보를 추가합니다.

**검증과 한계:** 재생해도 기존 관측 금액 유지, Prometheus에 실제 지표 출력 확인. 알림 수신 경로는 운영 확인 필요.

코드: `src/main/java/com/trading/account/domain/reconciliation/ReconciliationResults.java:41`

### R13. 실행 누락과 중단 복구

**이전 문제:** 예약 시각의 앱 중단으로 대사가 누락되거나 STARTED가 고착될 수 있었습니다.

**수정:** 01시 이후 반복 확인과 같은 날짜 실행 식별자를 사용합니다. 고착 상태는 전체 실행기 정지 후 지정 실행만 복구합니다.

**검증과 한계:** 늦은 기동·FAILED 재시도·완료 중복 방지·살아 있는 STARTED 보호·실제 복구 SQL 검증. 자동 강제 복구는 하지 않음.

코드: `src/main/java/com/trading/account/domain/reconciliation/BalanceReconciliationScheduler.java:44`

### R14. 동일 커밋의 테스트와 배포

**이전 문제:** CI 실패와 배포의 선후 관계를 보장하기 어려웠습니다.

**수정:** CD가 재사용 CI 성공에 의존하고 동일 Git SHA를 빌드합니다. 운영 배포는 점검 창을 명시한 수동 실행만 허용합니다.

**검증과 한계:** 워크플로 의존 관계·구문 검토. GitHub 보호 규칙과 실제 실행 이력은 미검증.

코드: `.github/workflows/cd.yml:24`

### R15. 이미지 불변성과 배포 순서

**이전 문제:** latest 적용과 겹친 배포로 어떤 코드가 실행되는지 불명확해질 수 있었습니다.

**수정:** SHA 빌드에서 digest를 확인하고 최종 매니페스트를 한 번 적용합니다. 워크플로 직렬화와 최신 master 확인을 추가했습니다.

**검증과 한계:** mutable 태그·잘못된 호스트 입력 거절, 정상 digest 렌더링. 운영 rollout 자체는 실행하지 않음.

코드: `scripts/render_manifests.py:9`

### R16. 실제 요청 경로의 상태 점검

**이전 문제:** 관리 포트만 정상이면 주 API 프로세스 문제를 놓칠 수 있었습니다.

**수정:** 주 포트 /livez·/readyz를 사용합니다. readiness에 DB/Redis를 포함하고 liveness에는 외부 의존성을 넣지 않습니다.

**검증과 한계:** 실제 HTTP 프로브 200/세부정보 비노출, 기타 관리·Swagger 경로 익명 401. 실제 파드 장애 전환은 별도 검증.

코드: `k8s/deployment.yaml:73`

### R17. 실패를 놓치지 않는 테스트

**이전 문제:** 총액만 검사하면 거래가 전부 실패해도 동시성 테스트가 통과할 수 있었습니다.

**수정:** 성공 건수와 이력 수를 함께 확인하고 모든 DB 테스트를 MySQL/Flyway로 통일했습니다. H2를 제거했습니다.

**검증과 한계:** 양방향 이체 성공 수·원장 건수·잔액 합계, 동일 키 50개 동시 요청 1회 원장 반영 확인.

코드: `src/test/java/com/trading/account/domain/transaction/TransactionConcurrencyTest.java:27`

### R18. 키 입력과 제약 오류 분류

**이전 문제:** 키 길이·문자 계약과 중복 오류 구분이 불명확했습니다.

**수정:** 키를 1~100자의 제한된 문자로 검증하고 명명한 MySQL 유일 제약 위반만 재생 경로로 처리합니다.

**검증과 한계:** 다른 무결성 오류는 재생하지 않음. legacy 키의 대소문자·악센트·후행 공백 비교 규칙 보존.

코드: `src/main/java/com/trading/account/domain/idempotency/IdempotencyService.java:79`

### R19. 안정적인 이력 순서와 커서

**이전 문제:** 같은 시각 이력의 순서가 흔들리고 무제한/임의 정렬이 조회 계약을 약하게 했습니다.

**수정:** 유일 ID 내림차순과 1~100 크기를 고정하고 beforeId 커서 API를 추가했습니다.

**검증과 한계:** 동일 timestamp 순서 고정, 새 거래 추가 중 다음 커서 페이지 중복 없음. 전역 불변 스냅샷 보장은 아님.

코드: `src/main/java/com/trading/account/domain/transaction/TransactionService.java:109`

## 최종 검증

Java 35개 테스트 클래스, 134개 테스트 통과. 전체 test와 bootJar를 같은 작업본에서 실행했습니다. 배포 렌더러 3개와 YAML 15개 파일도 검증했습니다. 개별 에이전트/재현 실행 수를 중복 합산하지 않았습니다.

Docker Testcontainers의 실제 MySQL/Redis를 사용했습니다. 새 DB 설치, 기존 baseline, PAD/NO PAD 키 보존, 실제 HTTP 운영 경계, 거래 실패 롤백, Redis 경쟁, 대사 복구를 포함합니다.

실클러스터 배포, 장시간 부하, 프로세스 강제 종료와 네트워크 장애 전체 조합, 백업/PITR 복원, CVE/SBOM 전수 검사는 수행하지 않았습니다.

## 호환성과 운영 전제

새 키는 사용자별·대소문자 구분이며 요청 지문이 다르면 C017입니다. 금액 검증과 커서 계약은 API_CHANGES.md를 확인합니다. 기존 키는 삭제하지 않고 LEGACY로 격리합니다. 기존 문자 비교 규칙과 후행 공백 구분을 보존합니다.

DB 이전은 전체 구버전 쓰기를 중지한 점검 창에서 별도 계정으로 수행합니다. 백업·복원과 실제 V1 호환성 확인 후 명시 baseline → migrate → validate를 진행합니다. 자세한 명령은 OPERATIONS_RUNBOOK.md에 있습니다. 실제 마이그레이션 실패 시 DDL 부분 반영을 조사하며 자동으로 구버전을 시작하지 않습니다.

현재 대사는 현재 잔액의 정합성 점검이며 과거 일마감이 아닙니다. 고착 실행 복구는 모든 실행기 정지가 전제입니다. TLS 인증서/네트워크, DB 권한, 알림 수신, RTO/RPO, 키 회전, 업무 승인 정책의 실제 증거가 필요합니다.

## 테스트 클래스별 실행 수

| 테스트 클래스 | 통과 |
|---|---|
| AccountApplicationTests | 1 |
| DatabaseMigrationCommandTest | 6 |
| FinancialHardeningIntegrationTest | 17 |
| ProductionBoundaryIntegrationTest | 3 |
| ScenarioIntegrationTest | 5 |
| SchemaMigrationTest | 3 |
| common.exception.ExceptionLoggingSafetyTest | 3 |
| common.exception.GlobalExceptionHandlerTest | 3 |
| common.logging.CorrelationIdFilterTest | 3 |
| common.ratelimit.RateLimitFilterTest | 7 |
| common.ratelimit.RateLimitSecurityTest | 9 |
| common.ratelimit.RedisTokenBucketContractTest | 3 |
| common.ratelimit.TokenBucketRateLimiterTest | 2 |
| common.response.ApiResponseTest | 4 |
| common.security.CredentialInputTest | 2 |
| common.security.JwtAuthenticationFilterTest | 4 |
| common.security.SecurityBoundaryTest | 3 |
| domain.account.AccountControllerTest | 3 |
| domain.account.AccountNumberValidatorTest | 6 |
| domain.account.AccountRepositoryTest | 3 |
| domain.account.AccountServiceTest | 6 |
| domain.idempotency.IdempotencyConstraintTest | 1 |
| domain.idempotency.IdempotencyServiceTest | 5 |
| domain.member.MemberControllerTest | 3 |
| domain.member.MemberRepositoryTest | 2 |
| domain.member.MemberServiceTest | 3 |
| domain.reconciliation.BalanceReconciliationJobIntegrationTest | 2 |
| domain.reconciliation.BalanceReconciliationProcessorTest | 2 |
| domain.reconciliation.BalanceReconciliationSchedulerIntegrationTest | 4 |
| domain.transaction.PessimisticLockExperimentTest | 1 |
| domain.transaction.SimulatedDepositDisabledTest | 1 |
| domain.transaction.TransactionConcurrencyTest | 2 |
| domain.transaction.TransactionControllerTest | 4 |
| domain.transaction.TransactionHistoryRepositoryTest | 2 |
| domain.transaction.TransactionServiceTest | 6 |
