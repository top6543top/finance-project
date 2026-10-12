# Account - 계좌·이체 서비스

Spring Boot / MySQL / Redis 기반 모의 계좌 서비스입니다. 잔액과 거래 이력, 멱등성 결과를 같은 DB 트랜잭션으로 확정합니다. 운영 프로필에서는 임의 모의 입금을 차단합니다.

## 먼저 읽을 문서

| 문서 | 내용 |
|---|---|
| [개선 결과](docs/HARDENING_REPORT.md) | 기존 19개 지적 사항의 수정 내용, 검증과 재평가 |
| [실행·배포·복구](docs/OPERATIONS_RUNBOOK.md) | 환경변수, DB 이전, 배포, 장애 대응 |
| [API 변경 계약](docs/API_CHANGES.md) | 키 재사용, 금액, 응답 ID, 조회 커서, 오류 |
| [대사 상세](docs/RECONCILIATION_HARDENING_NOTES.md) | 현재 잔액 점검의 의미와 안전한 복구 |
| [과거 동시성 기록](docs/HISTORICAL_CONCURRENCY_NOTES.md) | 개선 전 README 학습 기록 보존 |

기존 PROJECT_SPEC / PRODUCTION_HARDENING_SPEC은 설계 이력입니다. 현재 구현과 충돌하면 위 개선 문서와 실제 코드를 기준으로 확인합니다.

## 개발 및 테스트

Java 17과 실행 중인 Docker가 필요합니다. 테스트는 일회용 MySQL 8.0 / Redis 7 컨테이너에서 실행합니다.

```powershell
./gradlew.bat test
./gradlew.bat bootJar
python scripts/test_render_manifests.py
```

개발 실행에는 DB_URL, DB_USERNAME, DB_PASSWORD, JWT_SECRET을 지정합니다. SPRING_PROFILES_ACTIVE=dev, Redis 기본 호스트는 localhost입니다. DB URL은 jdbc:mysql://<host>:3306/<schema> 형식이며 실제 자격증명은 환경변수로 주입합니다. ./gradlew.bat bootRun으로 시작합니다. dev만 앱 시작 시 Flyway로 스키마를 설치합니다. 기존 DB는 먼저 운영 절차의 명시적 baseline/이전 단계를 따릅니다.

모의 입금을 시험할 때만 SIMULATED_DEPOSITS_ENABLED=true를 추가합니다. prod는 항상 이 기능을 끕니다. ignored application-local.yaml은 자동 변환하지 않으므로 과거 설정을 그대로 운영에 사용하지 않습니다.

## 거래가 확정되는 과정

1. JWT 주체와 요청 금액·키를 검증합니다.
2. 사용자 ID + 키에 대한 기존 응답을 확인합니다. 같은 키의 다른 요청은 409입니다.
3. 새 요청은 키 선점, 조건부 잔액 UPDATE, 이력 INSERT, 응답 저장을 한 트랜잭션에 묶습니다.
4. 커밋 후 성공 로그를 남깁니다. 일시적 DB 충돌은 전체 트랜잭션을 새로 시작해 제한 횟수만 재시도합니다.

현재 대사는 **현재 원장과 현재 잔액의 정합성 검사**입니다. 과거 영업일의 확정 마감, 외부 은행과의 자금 정산, 복식 회계는 별도 업무 범위입니다. 개선된 코드를 실자금 운영 승인으로 해석하지 않습니다.
