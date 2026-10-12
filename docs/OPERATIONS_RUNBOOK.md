# 계좌 서비스 실행·배포·복구 절차

## 이 서비스가 제공하는 범위

소수 두 자리 금액의 모의 계좌·이체 서비스다. 운영 프로필은 사용자가 임의로 잔액을 늘리는 모의 입금을 금지한다. 실제 은행 입금 확인, 통화 환전, 승인·역분개·계좌 정지와 일별 업무 한도는 별도 업무 설계가 필요하다. 현재 대사는 **실행 당시 원장과 현재 잔액의 일치 여부**를 검사한다. 과거 특정 영업일의 확정 마감 잔액을 재현하는 기능으로 표현하지 않는다.

## 개발 실행

Java 17, Docker, MySQL 8.0, Redis 7이 필요하다. 테스트는 MySQL/Redis Testcontainers를 사용하며 H2는 사용하지 않는다.

```powershell
./gradlew.bat test
./gradlew.bat bootJar
python scripts/test_render_manifests.py
```

개발 환경은 `SPRING_PROFILES_ACTIVE=dev`, `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`을 환경변수로 지정한다. `REDIS_HOST`의 개발 기본값은 localhost다. 개발 DB만 앱 시작 시 Flyway를 실행한다. 모의 입금이 필요할 때만 `SIMULATED_DEPOSITS_ENABLED=true`를 설정한다. Swagger는 dev에서만 익명 접근이 가능하다. 기존 ignored `application-local.yaml`은 수정하지 않았다. 과거 local 설정이 `ddl-auto:update` 등을 덮어쓸 수 있으므로 신규 dev 프로필로 이전하거나 직접 설정을 검토한다.

비밀번호, 실제 고객 정보, 운영 시크릿을 예시·커밋·터미널 기록에 넣지 않는다. `.env` 또는 로컬 환경변수 관리 도구를 사용한다.

## 스키마 설치와 이전

스키마는 Flyway V1(기존 업무 테이블), V2(Spring Batch 5.2), V3(사용자별 멱등성과 거래 감사 연결), V4(대사 증거) 순서다. `clean`과 자동 baseline은 금지한다. 런타임 앱은 DML 계정을 사용하며 prod에서는 `ddl-auto:validate`, Flyway 자동 실행은 꺼져 있다.

별도 마이그레이션 계정의 `FLYWAY_URL`, `FLYWAY_USER`, `FLYWAY_PASSWORD`를 설정한다. 기본적으로 MySQL URL은 정확한 대소문자의 `sslMode=VERIFY_IDENTITY`를 요구한다. 단일 호스트 URL과 허용된 연결 옵션만 받으며 중복 TLS 옵션·내장 자격증명·per-host 설정은 거절한다. `sslmode`나 `SSLMODE`는 드라이버에서 다른 의미가 되므로 허용하지 않는다. 격리 개발 DB에서만 `FLYWAY_REQUIRE_TLS=false`를 명시할 수 있다.

```powershell
java -Dloader.main=com.trading.account.common.config.DatabaseMigration -cp build/libs/account-0.0.1-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher migrate
```

빈 DB는 바로 migrate한다. 이미 운영한 DB에는 다음 순서가 필요하다.

1. 백업을 만들고 별도 DB에서 복원을 성공시킨다. 실제 DDL을 V1과 비교한다. Batch 테이블은 5.2.1과 호환되는지도 확인한다.
2. 모든 구버전 앱과 대사 실행기를 중지한다. 이번 변경은 구버전과 신버전의 동시 쓰기를 지원하지 않는다.
3. 정확한 DB URL과 스키마를 재확인하고 `FLYWAY_BASELINE_ACK=original-schema-reviewed`를 지정하여 명령 끝의 `migrate` 대신 `baseline`을 한 번 실행한다. baseline 버전은 1이다.
4. migrate 후 validate를 실행한다. 계좌 수·잔액 합계·이력 수·기존 키 수를 이전 값과 대조한다.
5. 신규 앱을 시작하고 `/readyz` 및 테스트 계좌의 거래·재시도를 확인한다.

기존 멱등성 키는 삭제하지 않고 actor 0 / LEGACY 범위에 보존한다. 소유자를 안전하게 복원할 수 없는 과거 키는 새 요청에도 충돌 오류로 거절한다. 이를 삭제하면 오래된 요청의 재시도가 새 거래로 처리될 수 있다. 새 키도 거래 이력의 감사 참조이므로 단순 TTL 삭제를 하지 않는다. 보존 기간·압축·아카이빙은 거래/키 조회 계약과 함께 결정한다.

MySQL DDL은 완전히 롤백되지 않을 수 있다. 마이그레이션 중간 실패 시 앱을 자동 재시작하거나 Flyway repair/clean을 실행하지 않는다. 실제 스키마와 이력을 확인하고 복원 또는 검토한 전진 수정으로 복구한다. 이전 앱 이미지만 되돌리는 방법을 DB 롤백으로 간주하지 않는다.

## 운영 환경 준비

- `account-runtime-secret`: DB_URL, DB_USERNAME, DB_PASSWORD, JWT_SECRET, REDIS_HOST, REDIS_PORT, REDIS_USERNAME(필요 시), REDIS_PASSWORD, REDIS_SSL_ENABLED. DB URL은 서버 인증서를 확인하는 TLS 옵션을 사용한다.
- `account-migration-secret`: FLYWAY_URL, FLYWAY_USER, FLYWAY_PASSWORD. 런타임 Secret과 분리하며 앱 파드에 마이그레이션 계정을 주입하지 않는다.
- `account-tls`: 실제 공개 호스트 인증서와 키. `ecr-secret`: 이미지 읽기 자격증명. 값은 소스에 넣지 않고 운영 시크릿 관리 절차로 공급한다.
- GitHub 변수: ECR_REPOSITORY, AWS_REGION, ACCOUNT_PUBLIC_HOST. AWS 권한은 해당 ECR 작업만 허용한다. `production` environment에 승인자와 보호 규칙을 설정한다.
- k3s Traefik의 namespace·pod 라벨과 CNI의 NetworkPolicy 지원, monitoring namespace를 실제 클러스터와 대조한다. 다른 ingress controller라면 해당 설정을 맞춘다.

NetworkPolicy는 앱 8080을 Traefik에서만, 관리 9090을 monitoring namespace에서만 허용한다. 공개 Ingress는 TLS websecure의 `/api`만 라우팅한다. 평문 HTTP 및 직접 Pod/Node 접근이 차단되는지 외부에서 확인한다. TLS 인증서 Secret 존재만으로 유효성과 만료 감시까지 보장되지 않는다.

앱은 전달 헤더를 IP 증거로 사용하지 않는다. 프록시 뒤에서는 연결 IP가 공유되어 IP 제한이 보수적으로 적용된다. 실제 고객 트래픽에서는 경계 프록시의 사용자/IP 제한과 로그인 계정 단위 정책을 별도로 설계·측정해야 한다.

## 릴리스 절차

master push는 같은 SHA의 전체 테스트 후 이미지를 빌드한다. 운영 변경은 workflow_dispatch의 승인된 maintenance_window가 true일 때만 진행한다. 워크플로 전체가 직렬화되고 현재 master가 아닌 SHA는 배포하지 않는다. latest를 적용한 뒤 교체하는 단계는 없다. 배포 매니페스트에는 SHA 태그에서 확인한 **이미지 digest**만 들어간다.

첫 스키마 이전의 호환성 위험 때문에 이번 릴리스 절차는 계획된 중단을 사용한다. 환경 승인과 백업 확인 → 구버전 파드 종료 → 마이그레이션 Job 성공 → 신규 digest 적용 → 실제 8080 포트 readiness 통과 순서다. 마이그레이션 실패 시 중단 상태를 유지하여 불명확한 스키마로 서비스를 재개하지 않는다. 무중단 전환이 필요하면 별도의 expand/contract 마이그레이션을 설계해야 한다.

GitHub Actions, AWS/ECR, 실제 클러스터에서의 실행은 별도 운영 검증이다. 로컬 테스트 결과를 배포 완료 증거로 사용하지 않는다.

## 관측과 복구

SQL 원문·바인딩과 Hibernate SqlExceptionHelper 로그는 민감값 보호를 위해 끈다. 제한된 오류 코드·종류와 requestId, 거래 ID로 진단하며 운영에서 해당 로그를 무심코 다시 켜지 않는다.

`/livez`는 JVM/애플리케이션 생존 상태를, `/readyz`는 앱·DB·Redis의 요청 수락 가능 여부를 확인한다. DB나 Redis 장애를 liveness에 넣어 전체 파드를 재시작시키지 않는다. Redis 요청 제한 의존성이 실패하면 503으로 거절한다. 복구 후 정상적인 429 한도를 재확인한다.

대사에서는 run ID, 실행 시각, 기준(CURRENT_LEDGER_CONSISTENCY), 상태와 불일치를 DB에 남긴다. 불일치는 자동 잔액 수정으로 처리하지 않는다. `reconciliation_mismatch`에 접근 가능한 담당자가 원장·요청·거래 식별자를 연결해 조사하고 resolution_status를 기록한다. 알림은 불일치와 실패, 장시간 실행 및 최근 성공/완료 누락을 감시한다.

중단된 Batch 실행을 단순 시간 초과로 FAILED로 바꾸면 살아 있는 작업과 이중 실행될 수 있다. `scripts/recover-reconciliation.ps1`의 운영 중지 검사와 명시적 확인을 거친 뒤 지정 실행만 복구한다. 스크립트 사용법과 추가 전제조건은 스크립트 및 대사 개선 노트를 참고한다. 임의 SQL로 실행 이력이나 불일치 기록을 삭제하지 않는다.

## 운영 승인에 필요한 추가 증거

코드 수정과 별개로 실제 DB 최소 권한, TLS 인증서/네트워크, 백업·PITR 복원, RTO/RPO, 알림 수신, 실사용 부하에서 p95/p99·오류율·락 대기, 취약 의존성/SBOM, 키 회전과 계정 폐기, 외부 입금 증빙 및 업무 한도 정책을 확인해야 한다. 본 프로젝트의 테스트만으로 실자금 운영 승인을 선언하지 않는다.
