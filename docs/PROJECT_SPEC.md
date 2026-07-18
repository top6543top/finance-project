# 금융권 Java 백엔드 개발자 개인 프로젝트 전체 요약

## 프로젝트 개요

총 3개의 독립 프로젝트로 구성된 금융 도메인 백엔드 포트폴리오.
트랜잭션 처리 → 실시간 데이터 분배 → 주문 체결 엔진 순서로 난이도를 높여가며
금융 백엔드 핵심 역량을 단계별로 쌓는 것이 목표.

**이 문서는 학습/포트폴리오용 스펙입니다. 코드를 짤 때 동시성 처리, 트랜잭션 경계,
예외 처리는 절대 생략하지 마세요 — 면접에서 설명 가능해야 합니다.**

## 공통 아키텍처

레이어드 아키텍처 (Layered Architecture) 사용.

`Controller (API 진입점) → Service (비즈니스 로직) → Repository (DB 접근) → DB`

Phase 2, 3에서는 위 구조에 WebSocket Handler, Redis Pub/Sub, Kafka Producer/Consumer 추가.

---

## Phase 1 · 계좌 이체 & 잔고 관리 API

**기간**: 4~6주
**기술 스택**: Spring Boot 3, Spring Data JPA, MySQL, JUnit 5, Docker

### 목표
트랜잭션 무결성과 동시성 문제를 직접 해결하며 금융 백엔드 기본기 습득.

### Spring Initializr 의존성
- Spring Web
- Spring Data JPA
- MySQL Driver
- Lombok
- Validation
- H2 Database (테스트용)
- spring-retry (낙관적 락 재시도용, 심화 단계)

### ERD
- **Member**: id, name, email(UNIQUE), created_at
- **Account**: id, account_number(UNIQUE), balance, version(낙관적 락용), member_id(FK), created_at
- **TransactionHistory**: id, from_account_id(FK), to_account_id(FK), amount, type(DEPOSIT/WITHDRAW/TRANSFER), created_at

### 이슈 목록

**EP-1 프로젝트 초기 세팅**
- IS-1: Spring Boot 프로젝트 생성 및 기본 설정
- IS-2: 공통 응답 형식 및 예외 처리 구조 설계 (ApiResponse, ErrorCode, GlobalExceptionHandler)

**EP-2 회원 도메인**
- IS-3: Member 엔티티 및 Repository 작성
- IS-4: 회원가입 API 구현 (POST /api/members)

**EP-3 계좌 도메인**
- IS-5: Account 엔티티 및 Repository 작성 (@Version 필드 포함)
- IS-6: 계좌 개설 API 구현 (POST /api/accounts)
- IS-7: 잔고 조회 API 구현 (GET /api/accounts/{accountNumber})

**EP-4 거래 도메인 (핵심)**
- IS-8: TransactionHistory 엔티티 작성
- IS-9: 입금 / 출금 API 구현
- IS-10: 계좌 이체 API 구현 ⭐ (@Transactional, REPEATABLE_READ)
- IS-11: 거래 이력 조회 API 구현 (페이지네이션)

**EP-5 동시성 처리 (차별화)**
- IS-12: 동시성 버그 재현 테스트 (ExecutorService + CountDownLatch로 100개 스레드 동시 출금)
- IS-13: 낙관적 락으로 동시성 문제 해결 (@Version + @Retryable 최대 5회 재시도)

**EP-6 마무리**
- IS-14: 전체 통합 테스트 및 시나리오 테스트 작성
- IS-15: README 작성 (트러블슈팅 기록 포함)

### API 명세

| 이름 | Method | Endpoint | 설명 |
|---|---|---|---|
| 회원가입 | POST | /api/members | 신규 회원 등록 |
| 계좌 개설 | POST | /api/accounts | 신규 계좌 생성 |
| 잔고 조회 | GET | /api/accounts/{accountNumber} | 계좌 잔고 확인 |
| 입금 | POST | /api/accounts/{accountNumber}/deposit | 계좌에 입금 |
| 출금 | POST | /api/accounts/{accountNumber}/withdraw | 계좌에서 출금 |
| 계좌 이체 | POST | /api/transfer | 계좌 간 이체 |
| 거래 이력 조회 | GET | /api/accounts/{accountNumber}/transactions | 거래 내역 페이지 조회 |

### 핵심 트러블슈팅
- **문제**: 100개 스레드 동시 출금 시 잔고 마이너스 발생 (Race Condition)
- **원인**: 여러 트랜잭션이 동시에 잔고를 조회한 뒤 각자 차감 시도
- **해결**: @Version 낙관적 락 적용 + @Retryable 최대 5회 재시도
- **결과**: 100개 스레드 동시 출금 후 최종 잔고 정확히 0원 확인

### 패키지 구조
```
src/main/java/com/example/bank/
├── common/
│   ├── exception/ (CustomException, ErrorCode, GlobalExceptionHandler)
│   └── response/ (ApiResponse)
├── domain/
│   ├── member/ (Member, MemberRepository, MemberService, MemberController, dto/)
│   ├── account/ (Account, AccountRepository, AccountService, AccountController, dto/)
│   └── transaction/ (TransactionHistory, TransactionType, TransactionHistoryRepository, TransferService, TransferController, dto/)
└── BankApplication.java
```

### 면접 예상 질문
- @Transactional을 왜 붙이셨나요? → 출금+입금+이력저장이 하나의 원자적 작업이기 때문
- 낙관적 락을 선택한 이유는? → 동시 충돌 빈도 낮음, 비관적 락보다 throughput 유리
- 트랜잭션 롤백이 안 되는 케이스? → checked exception은 기본 롤백 안 됨, rollbackFor 설정 필요
- 격리 수준을 REPEATABLE_READ로 한 이유는? → 이체 중 다른 트랜잭션이 잔고 읽어도 일관된 값 보장

---

## Phase 2 · 실시간 주식 시세 Push 서버

**기간**: 4~5주
**기술 스택**: Spring Boot 3, Spring WebSocket (STOMP), Redis Pub/Sub, Apache Kafka, MySQL, Docker

### 목표
수천 명 클라이언트에게 실시간 시세를 안정적으로 분배하는 WebSocket 서버 구현.
단일 서버를 넘어 다중 서버 환경에서도 동작하는 확장 가능한 아키텍처 설계.

### Spring Initializr 의존성
- Spring Web
- Spring WebSocket
- Spring Data JPA
- MySQL Driver
- Spring Data Redis (Access+Driver)
- Lombok
- Spring Kafka (심화 단계)

### ERD
- **Stock**: id, stock_code(UNIQUE), stock_name, market(KOSPI/KOSDAQ), created_at
- **StockPriceHistory**: id, stock_id(FK), price, recorded_at

### 이슈 목록

**EP-1 프로젝트 초기 세팅**
- IS-1: Spring Boot 프로젝트 생성 및 docker-compose 구성 (MySQL + Redis)
- IS-2: 공통 응답 형식 및 예외 처리 구조 설계

**EP-2 종목 도메인**
- IS-3: Stock 엔티티 및 Repository 작성 + 초기 데이터 INSERT
- IS-4: 종목 목록 조회 API 구현 (GET /api/stocks)

**EP-3 시세 도메인**
- IS-5: StockPriceHistory 엔티티 및 Repository 작성
- IS-6: Mock 시세 생성기 구현 (@Scheduled 1초마다 랜덤 시세 생성)
- IS-7: 시세 조회 REST API 구현 (현재가 조회, 히스토리 조회)

**EP-4 WebSocket 서버 구축**
- IS-8: WebSocket (STOMP) 서버 설정 (WebSocketConfig)
- IS-9: 종목 구독 / 취소 핸들러 구현 (/pub/subscribe, /pub/unsubscribe)
- IS-10: 시세 생성 시 WebSocket 브로드캐스트 연동 (SimpMessagingTemplate)

**EP-5 Redis Pub/Sub 적용**
- IS-11: Redis 설정 및 Publisher 구현
- IS-12: Redis Subscriber 구현 및 WebSocket 연동

**EP-6 Kafka 연동 (선택 심화)**
- IS-13: Kafka 환경 설정 (Zookeeper + Kafka docker-compose 추가)
- IS-14: Kafka Producer / Consumer 구현

**EP-7 부하 테스트**
- IS-15: JMeter 동시 접속 1000명 부하 테스트 및 성능 측정

**EP-8 마무리**
- IS-16: 전체 통합 테스트 작성
- IS-17: README 작성

### API 명세

**REST API**

| 이름 | Method | Endpoint | 설명 |
|---|---|---|---|
| 종목 목록 조회 | GET | /api/stocks | 전체 종목 리스트 조회 |
| 종목 현재 시세 조회 | GET | /api/stocks/{stockCode}/price | 특정 종목 현재가 조회 |
| 시세 히스토리 조회 | GET | /api/stocks/{stockCode}/history | 종목별 시세 기간 조회 |

**WebSocket (STOMP)**

| 이름 | 타입 | Endpoint | 설명 |
|---|---|---|---|
| 시세 구독 연결 | CONNECT | /ws/stocks | WebSocket 최초 연결 |
| 종목 구독 요청 | SEND | /pub/subscribe | 특정 종목 시세 구독 시작 |
| 종목 구독 취소 | SEND | /pub/unsubscribe | 특정 종목 시세 구독 취소 |
| 시세 수신 | SUBSCRIBE | /sub/stocks/{stockCode} | 실시간 시세 수신 |

### 핵심 트러블슈팅
- **문제**: 서버 2대 이상일 때 A 서버 클라이언트가 B 서버에서 발생한 시세를 받지 못함
- **원인**: 각 서버가 자신에게 연결된 WebSocket 세션만 관리
- **해결**: Redis Pub/Sub 도입. 시세 생성 시 Redis 채널에 Publish, 모든 서버가 Subscribe하여 각자 클라이언트에게 전달

### 패키지 구조
```
src/main/java/com/example/stockpush/
├── common/
├── config/ (WebSocketConfig, RedisConfig, KafkaConfig)
├── domain/
│   ├── stock/ (Stock, StockRepository, StockService, StockController, dto/)
│   └── price/ (StockPriceHistory, StockPriceRepository, StockPriceService, MockPriceGenerator)
├── websocket/ (StockSubscribeController, SubscriptionManager, StockPriceBroadcaster, dto/)
├── redis/ (RedisPublisher, RedisSubscriber)
├── kafka/ (StockPriceKafkaProducer, StockPriceKafkaConsumer)
└── StockPushApplication.java
```

### 면접 예상 질문
- WebSocket을 선택한 이유? → HTTP 폴링 대비 연결 유지 비용 낮음, 실시간 Push 가능
- 서버 여러 대일 때 WebSocket은? → Redis Pub/Sub으로 서버 간 메시지 브로드캐스트
- Redis Pub/Sub과 Kafka의 차이? → Redis는 빠르지만 유실 가능, Kafka는 저장으로 유실 방지
- 1000명 동시 접속 시 병목은? → 메시지 직렬화, 네트워크 I/O, Redis 연결 수

---

## Phase 3 · 주문 체결 엔진 (Order Matching Engine)

**기간**: 5~7주
**기술 스택**: Spring Boot 3, Apache Kafka, Redis (Redisson), PostgreSQL, Spring WebSocket, Prometheus, Grafana, Docker

### 목표
증권사 핵심 시스템인 주문 체결 엔진 직접 구현.
가격·시간 우선 매칭 알고리즘, 부분 체결 처리, 분산 환경 동시성 제어까지 경험.

### Spring Initializr 의존성
- Spring Web
- Spring WebSocket
- Spring Data JPA
- PostgreSQL Driver
- Spring Data Redis (Access+Driver)
- Spring Kafka
- Lombok
- Spring Boot Actuator
- Micrometer Prometheus (모니터링)
- Redisson (분산 락, build.gradle 직접 추가)

### ERD
- **Member**: id, name, email, balance(현금 잔고), created_at
- **Stock**: id, stock_code(UNIQUE), stock_name, market, created_at
- **Order**: id, member_id(FK), stock_id(FK), order_type(BUY/SELL), price, quantity, filled_quantity, status(PENDING/PARTIAL/FILLED/CANCELLED), created_at
- **Fill**: id, buy_order_id(FK), sell_order_id(FK), stock_id(FK), filled_price, filled_quantity, filled_at
- **Holding**: id, member_id(FK), stock_id(FK), quantity, avg_price(평균 매수가)

### 이슈 목록

**EP-1 프로젝트 초기 세팅**
- IS-1: Spring Boot 프로젝트 생성 및 docker-compose 구성 (PostgreSQL + Redis + Kafka + Zookeeper)
- IS-2: 공통 응답 형식 및 예외 처리 구조 설계

**EP-2 도메인 설계**
- IS-3: Member / Stock 엔티티 작성 + 초기 데이터 INSERT
- IS-4: Order / Fill / Holding 엔티티 작성 (OrderStatus, OrderType enum 포함)

**EP-3 주문 접수 API**
- IS-5: 주문 접수 API 구현 (POST /api/orders, 잔고/보유주식 체크)
- IS-6: 주문 취소 API 구현 (DELETE /api/orders/{orderId})
- IS-7: 미체결 / 체결 내역 조회 API 구현
- IS-8: 호가창 조회 API 구현 (GET /api/stocks/{stockCode}/orderbook)

**EP-4 체결 엔진 구현 (핵심)**
- IS-9: OrderBook 자료구조 구현 (PriorityQueue 기반, 가격·시간 우선 정렬)
- IS-10: 매칭 엔진 로직 구현 ⭐ (완전체결 / 부분체결 / 미체결 처리)
- IS-11: Kafka 주문 이벤트 발행 연동 (order-event-topic)

**EP-5 Kafka Consumer (체결 후처리)**
- IS-12: 체결 이벤트 Consumer 구현 (잔고 차감, 주식 수량 변경, Fill DB 저장, 멱등성 처리)

**EP-6 Redis 분산 락**
- IS-13: Redisson 분산 락 적용 (종목 코드 단위 락, 타임아웃 3초)

**EP-7 WebSocket 체결 결과 전송**
- IS-14: WebSocket (STOMP) 서버 설정
- IS-15: 체결 결과 실시간 전송 (/sub/orders/{memberId}, /sub/orderbook/{stockCode})

**EP-8 모니터링**
- IS-16: Prometheus + Grafana 구성 (TPS, 체결 지연시간, Kafka Consumer Lag, JVM 메모리)

**EP-9 마무리**
- IS-17: 전체 통합 테스트 및 시나리오 테스트 작성
- IS-18: README 작성 (아키텍처 다이어그램, 트러블슈팅 기록)

### API 명세

**REST API**

| 이름 | Method | Endpoint | 설명 |
|---|---|---|---|
| 주문 접수 | POST | /api/orders | 매수/매도 주문 접수 |
| 주문 취소 | DELETE | /api/orders/{orderId} | 미체결 주문 취소 |
| 미체결 주문 조회 | GET | /api/orders/pending | 내 미체결 주문 목록 조회 |
| 체결 내역 조회 | GET | /api/orders/filled | 내 체결 완료 주문 목록 조회 |
| 호가창 조회 | GET | /api/stocks/{stockCode}/orderbook | 매수/매도 호가창 조회 |

**WebSocket (STOMP)**

| 이름 | 타입 | Endpoint | 설명 |
|---|---|---|---|
| 체결 결과 구독 연결 | CONNECT | /ws/orders | WebSocket 최초 연결 |
| 내 주문 체결 결과 구독 | SEND | /pub/orders/subscribe | 내 주문 체결 결과 실시간 수신 |
| 호가창 실시간 구독 | SEND | /pub/orderbook/subscribe | 특정 종목 호가창 실시간 수신 |
| 체결 결과 수신 | SUBSCRIBE | /sub/orders/{memberId} | 내 체결 결과 수신 |
| 호가창 수신 | SUBSCRIBE | /sub/orderbook/{stockCode} | 호가창 변경 수신 |

### 핵심 트러블슈팅
- **문제**: 동일 종목 동시 주문 폭주 시 OrderBook 데이터 정합성 깨짐
- **원인**: 여러 스레드가 동시에 OrderBook 수정 → 체결 수량 중복 처리
- **해결**: Redisson 분산 락 적용 (종목 코드 단위 락, 타임아웃 3초)
- **선택 이유**: Phase 1 낙관적 락과 달리 체결 엔진은 충돌 빈도 높고 멀티 서버 환경 고려 필요

### 패키지 구조
```
src/main/java/com/example/orderengine/
├── common/
├── config/ (WebSocketConfig, RedisConfig, KafkaConfig, RedissonConfig)
├── domain/
│   ├── member/
│   ├── stock/
│   ├── order/ (Order, OrderType, OrderStatus, OrderRepository, OrderService, OrderController, dto/)
│   ├── fill/ (Fill, FillRepository)
│   └── holding/ (Holding, HoldingRepository)
├── engine/ (OrderBook, OrderMatchingEngine, OrderBookManager, OrderBookService)
├── kafka/ (OrderEventProducer, OrderEventConsumer, FillEventConsumer)
├── redis/ (DistributedLockService)
├── websocket/ (FillResultBroadcaster, OrderBookBroadcaster)
└── OrderEngineApplication.java
```

### 면접 예상 질문
- 체결 우선순위를 어떻게 구현했나요? → PriorityQueue, 가격우선 → 시간우선 Comparator
- 부분 체결은 어떻게 처리했나요? → filled_quantity 필드, PARTIAL 상태, 루프 매칭
- Kafka 메시지 유실 상황은? → acks=all, 재시도 설정, DLT(Dead Letter Topic)
- 낙관적 락 대신 Redis 분산 락을 쓴 이유? → 충돌 빈도 높음 + 멀티 서버 환경 고려
- OrderBook을 메모리에 두면 서버 재시작 시? → Redis 스냅샷 저장 or DB 복구 로직

---

## 기술 선택 비교 요약

| 상황 | 선택 | 이유 |
|---|---|---|
| Phase 1 동시성 | 낙관적 락 (@Version) | 충돌 빈도 낮음, throughput 유리 |
| Phase 2 멀티 서버 브로드캐스트 | Redis Pub/Sub | 서버 간 메시지 중계, 구현 간단 |
| Phase 2 메시지 유실 방지 | Kafka | 디스크 저장으로 유실 없음 |
| Phase 3 동시성 | Redis 분산 락 (Redisson) | 충돌 빈도 높음, 멀티 서버 환경 |
| Phase 3 체결 후처리 분리 | Kafka 이벤트 드리븐 | 체결과 후처리 느슨하게 연결 |
