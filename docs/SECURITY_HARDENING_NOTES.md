# 보안 경계 수정 설명

이 문서는 리뷰 R05(민감값 로그), R06(전달 헤더를 통한 요청 제한 우회), R07의 애플리케이션 장애 정책과 추가 인증 경계 수정을 설명한다. 실제 운영 인그레스나 외부 보안 장비를 검증했다는 뜻은 아니다.

## 1. 오류 로그에서 입력값을 제외한 이유

`MethodArgumentNotValidException.getMessage()`에는 거절된 값이 들어간다. 비밀번호가 짧아서 거절된 요청도 예외 원문을 기록하면 평문 비밀번호가 남는다. JSON 변환, 경로의 숫자 변환, 중첩 시스템 예외도 같은 문제가 생길 수 있다.

`GlobalExceptionHandler`는 서버가 선택한 오류 코드와 오류 종류만 기록한다. 예상 밖 오류는 예외 클래스 이름을 추가하되 예외 메시지, 원본 예외 객체, 중첩 원인의 메시지를 기록하지 않는다. 요청 추적은 기존 MDC의 requestId와 함께 한다. 응답은 기존의 구조화된 오류 코드와 검증 메시지를 유지한다.

단점은 원본 스택만 보고 바로 원인을 찾기가 어려워진다는 것이다. 운영에서 상세 진단이 필요하면 접근이 제한되고 민감값 제거가 검증된 별도 진단 경로를 설계해야 한다. 원본 예외를 일반 로그에 다시 붙이는 방식으로 해결하면 안 된다.

## 2. 요청 제한의 신뢰 기준

애플리케이션 코드는 `X-Forwarded-For`나 `Forwarded`를 직접 파싱하지 않고 `request.getRemoteAddr()`만 Redis 키로 쓴다. 이 주소를 누가 정하는지는 프로필이 결정한다.

- 기본(dev·test): `server.forward-headers-strategy=none`. 전달 헤더를 무시하므로 헤더 문자열을 바꿔도 같은 연결 주소의 버킷을 소비한다.
- prod: `native` + `server.tomcat.remoteip.internal-proxies=${TRUSTED_PROXIES}`. TCP 상대가 신뢰 프록시(Traefik)일 때만 Tomcat `RemoteIpValve`가 `X-Forwarded-For`를 **오른쪽부터** 읽어, 신뢰 프록시가 아닌 첫 주소를 `remoteAddr`로 쓴다. 프록시는 받은 값 뒤에 자기가 본 주소를 붙이므로, 클라이언트가 왼쪽에 넣은 위조값은 선택되지 않는다. 신뢰하지 않는 상대가 보낸 헤더는 무시된다.

`TRUSTED_PROXIES`는 기본값이 없어 누락 시 기동이 실패한다. 배포값은 k3s 기본 Pod CIDR(`10.42.0.0/16`)이고, 앱 포트 8080은 NetworkPolicy로 Traefik만 접근할 수 있어 이 대역이 곧 Traefik이다. 실제 클러스터 CIDR과, Traefik Service의 `externalTrafficPolicy: Local` 여부를 확인해야 한다(`Cluster`면 SNAT로 노드 주소가 클라이언트로 보여 다시 버킷을 공유한다). 공유 NAT 뒤 사용자는 여전히 한 버킷을 공유한다. 계정별 로그인 제한, 점진적 지연, MFA는 별도 개선 범위다.

로그인과 일반 API의 버킷은 계속 분리된다. Redis가 실패하면 두 종류 모두 무제한 허용 대신 `503 / C020`과 `Retry-After: 1`을 반환한다. 토큰이 소진된 정상 제한은 `429 / C016`이며, 한 토큰이 충전되는 시간에 맞춘 `Retry-After`를 반환한다. 이 정책은 보안 보호를 유지하는 대신 Redis 장애 때 일반 API 가용성도 낮춘다. 제한을 우회하는 로컬 fallback은 추가하지 않았다.

`/livez`, `/readyz`, 정확한 `/actuator/health`, `/actuator/prometheus`의 GET 요청은 제한 필터에서 제외한다. 이는 요청 제한을 건너뛰는 규칙이며 인증 허용과는 별개다.

## 3. Redis 버킷 수명과 동시성

Lua 한 번으로 조회, 충전, 소비, 저장을 처리하므로 여러 애플리케이션 인스턴스가 같은 예산을 공유한다. 시각은 Redis `TIME`을 사용한다. 애플리케이션 서버들의 시계 차이로 토큰이 더 충전되는 문제를 줄이기 위해서다.

버킷 만료 시간은 `ceil(capacity / refillTokens × refillPeriodSeconds)`초다. 예를 들어 최대 10개, 10초마다 1개 충전이면 빈 버킷이 가득 차려면 100초가 필요하다. 기존의 `refillPeriodSeconds × 2`는 20초 뒤 버킷을 지워 10개를 다시 지급했다. 수정 후에는 자연 충전보다 이른 초기화를 막는다. 만료 시각은 요청 때마다 갱신되므로 실제 저장 기간은 더 길 수 있다.

일반/로그인 버킷 설정은 둘 다 필요하며, 용량·충전 개수·충전 주기는 모두 양수여야 한다. 잘못된 설정을 기동 시점에 거절한다. Redis 접속 예외도 원문에 접속 문자열이나 암호가 들어갈 수 있어 오류 코드와 예외 종류만 기록한다.

## 4. 인증과 운영 엔드포인트

JWT는 서명, 만료, 사용자 ID를 한 번에 검사한다. 만료 없는 토큰, 숫자가 아닌 ID, 0/음수/범위 초과 ID, 변조·만료·잘못된 형식은 인증되지 않은 상태로 처리한다. 잘못된 subject 때문에 `NumberFormatException`이 500으로 나가는 경로를 없앴다. 보호된 API는 기존 인증 진입점에서 401을 반환한다. 새 토큰 폐기 저장소나 갱신 토큰 체계는 추가하지 않았다.

비밀번호는 bcrypt의 UTF-8 72바이트 한도를 회원가입과 로그인 입력에서 검사한다. 영문 72자와 한글 24자는 각각 72바이트여서 허용되고, 영문 73자와 한글 25자는 거절된다. 회원가입의 최소 8자 조건은 유지한다. 이름과 이메일도 DB 컬럼 길이에 맞춰 최대 255자로 제한한다. 길이 검증 오류를 예외 원문과 함께 기록하지 않는다.

공개 상태 확인은 정확한 GET `/livez`, `/readyz`만 허용한다. `/livez/details`, `/readyz/details`, Actuator health 상세 경로는 공개하지 않는다. Prometheus는 정확한 GET `/actuator/prometheus`이고 원시 로컬 포트가 별도 관리 포트일 때만 익명 접근할 수 있다. 메인 포트에서는 인증이 필요하다. 관리 포트 접근은 배포의 NetworkPolicy로 모니터링 주체에 제한해야 한다. 관리 포트와 앱 포트를 같게 설정하면 익명 메트릭 허용도 적용되지 않는다.

| 설정 | 동작 |
| --- | --- |
| `server.forward-headers-strategy=none` | 기본값. 전달 헤더로 연결 주소를 재작성하지 않음 |
| `server.forward-headers-strategy=native` (prod) | `TRUSTED_PROXIES`에 맞는 상대의 `X-Forwarded-For`만 오른쪽부터 반영 |
| `app.security.public-docs=false` | 기본값. API 문서 익명 접근 차단 |
| `app.security.public-docs=true` | 개발 환경에서 API 문서 GET 공개 |
| `management.server.port=9090` | 별도 관리 포트. 메트릭 허용은 실제 로컬 포트도 검사 |
| `server.port=8080` | 기본 메인 포트. Prometheus 익명 접근 차단 |
| `rate-limit.general.*`, `rate-limit.login.*` | 각 버킷의 양수 용량, 충전 개수, 충전 주기 |

## 5. 검증 근거와 한계

먼저 회귀 테스트를 추가한 뒤 수정 전 실패를 확인했다. 첫 실행은 18건 중 16건이 실패했고, 별도 Redis/보안 경계 실행은 6건 중 4건이 실패했다. 실제 Redis에서 잘못된 만료 시간은 20초로 확인됐다. 동시성 검증은 두 limiter 인스턴스로 50개 요청을 보내 정확히 5개만 허용되는지 검사한다.

수정 후 2026-09-29 대상 검증은 9개 테스트 클래스의 37건이 모두 통과했다(실패·오류·건너뜀 0). 기존 예외 응답, 요청 제한, 회원가입 컨트롤러 테스트도 함께 실행했다. 전체 저장소 통합 테스트와 배포 환경 검증은 이 보안 대상 실행과 별개다.

테스트별 역할:

- `ExceptionLoggingSafetyTest`: 실제 MVC 예외 처리와 Logback 캡처로 비밀번호, 이메일, 토큰, 계좌번호를 포함한 입력·예외 원문이 핸들러 로그에 없는지 검사.
- `RateLimitSecurityTest`: 전달 헤더 위조, Redis 접속 장애 주입, 503/429와 재시도 헤더, 정확한 제한 제외 경로, 잘못된 설정 검사.
- `RedisTokenBucketContractTest`: Testcontainers Redis에서 공유 동시 예산, 만료 시간, 실제 충전량 검사. Redis 테스트를 위해 MySQL이나 H2를 대신 쓰지 않음.
- `JwtAuthenticationFilterTest`: 실제 서명 토큰의 정상 인증, 변조, 만료, 잘못된/누락된 subject, 누락된 만료 검사.
- `CredentialInputTest`: 실제 Bean Validation으로 ASCII·한글 경계값과 DB 길이 제한 검사.
- `SecurityBoundaryTest`: 실제 Spring Security 필터 체인으로 정확한 probe 경로, health 상세 보호, 메인/관리 포트 메트릭 분리, 개발용 문서 설정 검사.

실제 인그레스 경유 헤더 정규화, NAT 사용자의 체감 한도, 운영 Redis 중단 시 p99 지연과 로그량, 관리 포트 네트워크 차단은 배포 환경에서 별도로 검증해야 한다. 현재 코드와 로컬 Testcontainers 검증만으로 이 운영 조건까지 보장할 수는 없다. Redis ACL을 제한하는 운영 환경에서는 스크립트의 `TIME`, 해시 조회/저장, 만료 명령도 허용해야 한다.

개별 확인 예시(Docker 실행 필요):

```powershell
.\gradlew.bat test --tests '*ExceptionLoggingSafetyTest' --tests '*RateLimitSecurityTest' --tests '*RedisTokenBucketContractTest' --tests '*JwtAuthenticationFilterTest' --tests '*CredentialInputTest' --tests '*SecurityBoundaryTest'
```
