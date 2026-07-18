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
