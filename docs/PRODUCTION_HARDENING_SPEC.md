# EP-7 프로덕션 하드닝 (인증/인가, 멱등성, 감사/관측, 체크디지트)

> `PROJECT_SPEC.md` Phase 1(EP-1~EP-6)이 끝난 뒤, "실무 코드리뷰에서 진짜로 걸리는" 4가지를
> 추가로 보강하는 스펙. Phase 1 스코프(트랜잭션 무결성/동시성)에서 의도적으로 뺐던 항목들이라
> 별도 EP로 분리. 형식은 `PROJECT_SPEC.md`와 동일.

## 목표
"동작은 하는데 아무나 남의 계좌를 털 수 있는 API"에서, 실제 서비스에 올려도 되는 수준의
안전장치(인증/인가, 재시도 안전성, 추적 가능성, 입력 방어)를 갖춘 API로 보강.

## 추가 Spring Initializr 의존성

| 의존성 | 용도 |
|---|---|
| `spring-boot-starter-security` | 인증/인가 (Spring Security 필터체인, BCryptPasswordEncoder 포함) |
| `io.jsonwebtoken:jjwt-api` / `jjwt-impl` / `jjwt-jackson` | JWT 발급·검증 |
| `spring-boot-starter-actuator` | 헬스체크, 메트릭 엔드포인트 |
| `io.micrometer:micrometer-registry-prometheus` | Prometheus 포맷 메트릭 노출 |
| `net.logstash.logback:logstash-logback-encoder` | 구조화(JSON) 로깅 |
| `commons-validator` (선택) | Luhn 체크디지트 — 직접 구현해도 10줄 안팎이라 선택 사항 |

Redis는 아직 Phase 1 스택에 없음 — 멱등성 키는 DB 유니크 제약으로 구현 (Phase 2에서 Redis 도입 시 전환 여지 남김).

---

## ERD 변경사항

**Member** (변경)
- `password` (BCrypt 해시, not null) — 이전 리팩터에서 뺐던 인증 필드 복원

**IdempotencyKey** (신규)
- id, `idempotency_key`(UNIQUE), `response_body`(직렬화된 응답), `status`(IN_PROGRESS/COMPLETED), `created_at`

**Account** (스키마 변경 없음)
- `account_number` 생성 알고리즘만 변경 — 끝자리에 Luhn 체크디지트 추가

---

## 이슈 목록

**EP-7 프로덕션 하드닝**
- IS-16: Spring Security + JWT 인증 도입 (`POST /api/auth/login`, BCrypt 비밀번호 해싱)
- IS-17: 계좌 소유자 검증(인가) — 이체/입출금/잔고조회 API에 "요청자 == 계좌 소유자" 체크 추가
- IS-18: Idempotency-Key 기반 멱등성 처리 (입금/출금/이체 API)
- IS-19: Correlation ID + 구조화 로깅 (MDC 기반 요청 추적, JSON 로그)
- IS-20: Actuator + Micrometer + Prometheus 메트릭 노출
- IS-21: 계좌번호 체크디지트(Luhn) 검증 도입 — 생성 시 부여, 조회 시 사전 검증

## API 변경사항

| 이름 | Method | Endpoint | 설명 |
|---|---|---|---|
| 로그인 | POST | /api/auth/login | email/password → JWT 발급 (신규) |

기존 계좌·거래 API 전체(`/api/accounts/**`, `/api/transfer`)는 `Authorization: Bearer {token}` 필수로 전환.
`/api/accounts/{accountNumber}/deposit`, `/withdraw`, `/api/transfer`는 `Idempotency-Key` 헤더 필수로 전환.

## 항목별 설계 메모

### IS-16/17. 인증/인가
- 세션이 아니라 JWT: REST API는 무상태가 기본이고, 세션 스토어 없이 서버를 수평 확장할 수 있어야 함.
- `SecurityFilterChain`에서 `/api/auth/login`만 permitAll, 나머지는 인증 필요.
- 인가는 `@PreAuthorize`보다 서비스 메서드 안에서 명시적으로 체크 — "이 계좌의 memberId == 인증된 사용자 id" 비교. 도메인 규칙이라 필터/애노테이션보다 서비스 계층에 두는 게 명확함.
- 기존 `Member`에서 인증 필드를 뺐던 리팩터(`3ababa0`)를 이번에 되돌려야 함 — 비밀번호 컬럼 재추가 + 마이그레이션 고려.

### IS-18. 멱등성
- 이체 API 재시도 시 중복 이체가 실무에서 제일 무서운 사고 유형.
- `IdempotencyKey` 테이블에 유니크 제약 → 같은 키로 동시에 두 요청이 들어와도 하나만 INSERT 성공, 나머지는 "처리 중" 응답.
- 완료되면 응답 바디를 저장해뒀다가, 같은 키로 또 오면 재실행 없이 저장된 응답을 그대로 반환.

### IS-19/20. 감사/관측
- `OncePerRequestFilter`에서 `X-Request-Id` 헤더를 읽거나 없으면 생성 → MDC에 심고 응답 헤더로 되돌려줌 → 로그 한 줄만 봐도 요청 하나를 끝까지 추적 가능.
- `TransactionHistory`(비즈니스 기록)와 감사 로그(누가 언제 호출했는지의 보안·컴플라이언스 기록)는 목적이 다르므로 분리해서 남김.
- Actuator `/actuator/prometheus`로 TPS, 에러율, 지연시간 노출 — Phase 3의 Prometheus/Grafana 스택과도 이어짐.

### IS-21. 체크디지트
- 지금은 완전 랜덤 계좌번호 → 오타여도 형식만 맞으면 DB까지 감. Luhn 체크디지트를 끝자리에 붙이면 컨트롤러 진입 시점에 "형식은 맞는데 존재할 수 없는 번호"를 걸러낼 수 있음 (신용카드 번호 검증과 동일한 원리).

## 면접 예상 질문
- 세션 대신 JWT를 쓴 이유는? → REST API 무상태 원칙, 서버 확장 시 세션 동기화 불필요
- 인가 체크를 왜 Security 애노테이션이 아니라 서비스 로직에 뒀나요? → "계좌 소유자 검증"은 프레임워크 관심사가 아니라 도메인 규칙이라 서비스 계층에서 명시적으로 표현하는 게 리뷰하기 쉬움
- 멱등성 키를 Redis 대신 DB에 둔 이유는? → Phase 1엔 아직 Redis 스택이 없어서 DB 유니크 제약으로 동일 효과를 냄 (Phase 2 Redis 도입 시 전환 후보)
- Correlation ID는 어디서 심었나요? → 요청 진입 필터에서 MDC에 심고, 응답 헤더로 클라이언트에도 돌려줘서 클라이언트-서버 로그를 하나의 ID로 연결

---

## EP-8 동시성 심화 (IS-12/13 후속)

> IS-12(재현)/IS-13(해결) 과정에서 확인한, 낙관적 락+재시도만으로는 못 채우는 부분.
> "100 스레드가 계좌 1개에 몰리면 5회 재시도로도 전부 성공은 못 한다"는 한계에 대한
> 실전 대응책 — 원리적으로 재시도 횟수를 늘려서 풀 문제가 아니라, 동시성 자체를 줄이거나
> 구조적으로 없애는 방향. Phase 1 스코프 밖이라 설계만 남기고 착수는 보류.

### 이슈 목록

- IS-22: 이체 시 락 순서 고정 (Deadlock 예방)
- IS-23: 잔고를 원장(append-only) 기반으로 전환 검토
- IS-24: Kafka 계좌별 파티셔닝으로 동시성 자체 제거 (설계 메모, Phase 2/3 스택 도입 후 재검토)

### 항목별 설계 메모

**IS-22. 락 순서 고정**
- 지금 `TransactionService.transfer()`는 `from`, `to`를 요청받은 순서 그대로 조회/락. A→B 이체와 B→A 이체가 동시에 들어오면 서로 반대 순서로 락을 잡아 데드락 가능 — IS-12에서 실제로 관측한 데드락과 같은 유형.
- 해결: 항상 `accountNumber`(또는 `id`)가 작은 쪽부터 조회·락을 걸도록 고정. 모든 트랜잭션이 같은 순서로 잠그면 순환 대기(데드락) 자체가 생길 수 없음.
- 비즈니스 순서(누가 보내는 쪽/받는 쪽)와 락을 거는 순서는 별개로 다뤄야 함 — 응답 DTO는 요청받은 from/to 순서를 그대로 유지하고, 내부 조회·락 순서만 바꾼다.

**IS-23. 원장 모델 전환**
- 지금은 `Account.balance` 컬럼을 매 거래마다 직접 UPDATE — 같은 행을 여러 트랜잭션이 놓고 경합하는 구조 자체가 여기서 나옴.
- append-only로 바꾸면: 거래는 `TransactionHistory`에 INSERT만 하고(행 경합 없음), `balance`는 그 기록의 합계로 실시간 계산하거나 별도 단일 프로세스가 캐시 갱신.
- 트레이드오프: 잔고 조회가 무거워짐(합계 계산 비용) vs 감사·재구성이 쉬워짐(모든 거래가 불변 기록). Phase 1에서 바로 전환하기엔 스코프가 커서 설계만 문서화.

**IS-24. Kafka 파티셔닝 (설계 메모, Phase 1 미착수)**
- `PROJECT_SPEC.md` Phase 2/3에서 이미 Kafka를 도입하므로, 그 시점에 "계좌 ID를 파티션 키로 써서 같은 계좌의 거래는 항상 같은 컨슈머가 순서대로 처리"하는 방식으로 재검토.
- 이렇게 되면 DB 레벨 락/재시도가 아니라 애플리케이션 레벨에서 동시성 자체가 사라짐. 지금의 낙관적 락+재시도는 이 구조가 없는 Phase 1 한정의 최후 방어선일 뿐.

### 면접 예상 질문
- 이체에서 데드락을 근본적으로 막으려면? → 두 리소스를 잠글 때 항상 같은 순서로 잠그는 lock ordering. 우리 경우 계좌 ID 오름차순으로 고정.
- 낙관적 락+재시도로는 왜 100% 성공을 못 보장하나요, 그럼 실전 답은 뭔가요? → 재시도는 유한하므로 원리적으로 불가능. 진짜 답은 동시성 자체를 줄이는 것 — 멱등성 키(IS-18)로 중복 요청을 걸러내고, Kafka 파티셔닝(IS-24)으로 원천적으로 직렬화. 낙관적 락은 그걸 다 거치고도 남는 저빈도 충돌을 처리하는 최후 수단일 뿐.
- balance 컬럼을 직접 갱신하는 대신 원장 모델을 쓰면 뭐가 좋아지나요? → INSERT는 행 경합이 없어 동시성 문제 자체가 줄고, 모든 거래가 불변 기록으로 남아 감사·재구성이 쉬움. 대신 잔고 조회 성능은 트레이드오프.
