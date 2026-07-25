# account — 계좌이체 & 잔고관리 API

Phase 1 (계좌이체 & 잔고관리) 진행 중. 전체 스펙은 `docs/PROJECT_SPEC.md` 참고.

> 이 README의 나머지 섹션(설치, API 사용법 등)은 IS-15에서 채울 예정. 지금은 트러블슈팅 기록만 우선 남겨둠.

## 트러블슈팅

### 동시성 출금 데드락 트러블슈팅 정리 (IS-12 → IS-13)

> 100 스레드 동시 출금 테스트에서 `success=12, failure=88`이 나온 원인을 추적한 과정 정리.

---

#### 1. 문제 상황

`TransactionConcurrencyTest`에서 워커 20개 + `CountDownLatch`로 계좌 1개에 100건 동시 출금.

```
success=12, failure=88, finalBalance=8800.00
```

로그를 까보니 대부분 `ObjectOptimisticLockingFailureException`(버전 충돌)이 아니라 진짜 MySQL 데드락이었음.

```
ERROR ... Deadlock found when trying to get lock; try restarting transaction
```

---

#### 2. 락의 기본 개념 (기초부터)

락 = 여러 스레드가 같은 데이터를 동시에 건드릴 때 순서를 정리해주는 자물쇠. 두 종류가 있음.

| 종류 | 별명 | 동작 |
|---|---|---|
| S-lock (공유락) | "보기 락" | 여러 명이 동시에 가질 수 있음 (열람은 같이 해도 안 싸움) |
| X-lock (배타락) | "편집 락" | 딱 한 명만 가질 수 있음, 그동안 아무도 못 끼어듦 |

**핵심 규칙**: 보기끼리는 동시에 여럿 가능 / 편집은 하나뿐, 그리고 아무도 안 보고 있어야 시작 가능.

---

#### 3. PK vs FK 개념

| 용어 | 비유 | 설명 |
|---|---|---|
| PK (`id`) | 서가에 꽂힌 책 실물 | 데이터가 실제로 저장되는 위치. `WHERE id = ?`는 바로 실물로 감 |
| `account_number` (유니크 인덱스) | 카드목록함의 색인 카드 | PK가 아님. "이 값은 서가 몇 번 칸"이라고 적힌 안내 카드일 뿐 |
| FK 제약 | 참조 스티커 | 자식 테이블(`transaction_history`)이 부모(`account`)를 참조할 때, "이 계좌 진짜 있나?" 확인 → 자동으로 부모 row에 **S-lock(보기 락)**을 걺. 코드에 안 썼는데도 FK 제약만으로 자동 실행됨 |

---

#### 4. 처음에 틀렸던 설명 (정정 기록)

**처음 설명**: "`account_number` 보조 인덱스 락 → PK 락, 2단계 손동작이 데드락 원인"

**→ 틀림.** 이유:
- 일반 `SELECT`(FOR UPDATE 아닌)는 REPEATABLE_READ에서 MVCC 스냅샷 읽기라서 **락을 아예 안 걺**
- JPA 더티체킹이 만드는 UPDATE 문은 조회 방법과 무관하게 **항상 `WHERE id = ?`(PK 기준)**로 나감
- 따라서 보조 인덱스는 애초에 락 경합과 무관한 지점이었음

---

#### 5. 진짜 원인: "코드 순서 ≠ DB 실행 순서"

```java
Account account = getAccount(accountNumber);
account.withdraw(amount);                          // ① 자바 메모리에서 balance만 변경 (SQL 아님)
transactionHistoryRepository.save(new TransactionHistory(...)); // ② 이 순간 즉시 INSERT 발생
```

**왜 코드 순서와 실행 순서가 어긋나는가:**

| 엔티티 | 메커니즘 | 언제 SQL이 나가나 |
|---|---|---|
| `Account` | 더티체킹 (JPA 기본 동작, 설정 아님) | **커밋 시점**에 몰아서 UPDATE |
| `TransactionHistory` | `@GeneratedValue(strategy = IDENTITY)` (명시적 설정) | id를 DB AUTO_INCREMENT가 만들어주므로, id를 받으려면 **`save()` 즉시** INSERT 실행 |

→ 결과: 코드에는 UPDATE 로직이 먼저 쓰여있어도, **실제 DB에는 INSERT가 먼저, UPDATE가 나중(커밋 시점)에 도착**함.

---

#### 6. 왜 이 순서 역전이 데드락을 만드나

```
T1: INSERT (FK 체크) → account에 "보기 락(S)" 획득  ✅
T2: INSERT (FK 체크) → account에 "보기 락(S)" 획득  ✅  (보기끼리는 공존 가능)

T1: 커밋 시점, UPDATE → "편집 락(X)" 필요 → T2가 보기 락 쥐고 있어서 대기
T2: 커밋 시점, UPDATE → "편집 락(X)" 필요 → T1이 보기 락 쥐고 있어서 대기

→ 서로 상대방이 보기 락 놓기를 기다리는데, 둘 다 편집 락을 못 얻어서 못 놓음
→ 데드락 (순환 대기)
```

이건 InnoDB에서 "자식 테이블 INSERT(FK 체크) 먼저, 부모 UPDATE 나중"일 때 흔히 나는 정석 패턴. (MySQL 공식 버그 트래커에도 동일 패턴 사례 다수 보고됨 — bug #48652, #52020, #90210 등)

---

#### 7. 해결책: UPDATE를 먼저 실행되게 강제

```java
public TransactionResDto withdraw(String accountNumber, BigDecimal amount) {
    Account account = getAccount(accountNumber);
    account.withdraw(amount);
    accountRepository.flush();   // ← UPDATE를 여기서 강제로 먼저 실행

    TransactionHistory history = transactionHistoryRepository.save(
            new TransactionHistory(account, null, amount, TransactionType.WITHDRAW));
    ...
}
```

**순서를 뒤집으면 왜 해결되나:**

```
T1: UPDATE 먼저 → 편집 락(X) 획득 (아무도 안 쥐고 있으니 바로 성공)
T1: INSERT → FK 체크로 보기 락 필요하지만, 이미 자기가 편집 락 쥔 계좌라 그냥 통과
T1: 커밋, 락 반납

T2: UPDATE 시도 → T1이 편집 락 쥐고 있으니 대기 (단순 줄서기, 데드락 아님)
T1 끝나면 T2 진행
```

"보기 락 두 개가 동시에 깔리는" 상황 자체가 사라짐 → 승격 경쟁(데드락)이 아니라 단순 대기열(느리지만 안전)로 바뀜.

**주의**: `@Retryable` 재시도는 이 수정 후에도 그대로 유지 필요. 이 수정은 데드락 *빈도*를 줄이는 것이지 100% 박멸이 아님 (극단적 동시성에서는 여전히 경합 가능).

---

#### 8. `save()` 동작 방식 정리 (헷갈렸던 부분)

`save()` 한 번 = INSERT **또는** UPDATE 둘 중 하나. insert-then-update를 순차로 하는 게 아님. **엔티티가 새 것이냐 기존 것이냐**로 결정됨.

```java
if (엔티티가 새로운 것) {  // id 없음, new로 막 생성
    em.persist(entity);   // → INSERT
} else {                  // id 있음, DB에서 조회해온 상태
    em.merge(entity);     // → UPDATE
}
```

| | 엔티티 상태 | save() 결과 |
|---|---|---|
| `Account` | 이미 존재 (조회해온 것) | UPDATE |
| `TransactionHistory` | 새로 생성 (`new`) | INSERT |

→ 우리가 조정한 것은 "이 두 개의 서로 다른 `save()` 호출 중 **어느 것을 먼저 DB로 내보낼지**"이지, `save()` 자체의 동작 방식을 바꾼 게 아님.

---

#### 9. 헷갈렸던 지점 Q&A

**Q. `save()`는 INSERT 하나만 한다며, 근데 왜 T1이 보기 락도 잡고 편집 락도 필요하다는 거야?**

A. "`save()` 한 번"과 "트랜잭션(T1) 하나"는 다른 개념. `withdraw()` 메서드 한 번 실행 = 트랜잭션 하나(T1)인데, 그 안에 서로 다른 엔티티에 대한 동작이 두 개 들어있음:

| | 무엇에 대한 것 | SQL | 발생 시점 |
|---|---|---|---|
| T1의 1번째 동작 | `TransactionHistory` (새 엔티티) | INSERT | `save()` 호출 즉시 |
| T1의 2번째 동작 | `Account` (기존 엔티티) | UPDATE | 커밋 시점 (더티체킹, `save()` 호출 없이 자동) |

T1 = 이 두 동작을 순서대로 실행하는 하나의 트랜잭션. 그래서 INSERT용 보기 락(S)을 먼저 잡았다가, 나중에 UPDATE용 편집 락(X)을 다시 요청하는 흐름이 나옴.

---

**Q. "커밋 시점"이라는 게 함수(메서드)가 끝날 때야?**

A. 거의 맞지만 정확히는 **"`@Transactional`이 걸린 메서드가 정상적으로 끝날 때"**.

Spring은 `@Transactional` 메서드를 아래처럼 감싸는 프록시를 자동으로 만듦 (개념적 구조):

```java
public void withdraw_프록시(...) {
    트랜잭션_시작();
    try {
        실제_withdraw_메서드_실행(...);  // 여러분이 짠 코드
        커밋();   // ← 정상 리턴되는 순간 여기서 자동 커밋
    } catch (Exception e) {
        롤백();
        throw e;
    }
}
```

- **정상 종료(정상 리턴)** → 함수 끝 = 커밋. 이 순간 미뤄뒀던 `Account` UPDATE가 나감
- **예외 발생** → 커밋이 아니라 **롤백**
- **다른 메서드 안에서 호출된 경우(트랜잭션 전파)** → 실제 커밋은 "가장 바깥쪽 `@Transactional` 메서드"가 끝날 때 일어남, 안쪽 메서드가 끝나도 바로 커밋 안 될 수 있음

→ 그래서 `flush()`가 의미 있는 이유: 원래 이 커밋(return) 시점까지 기다려야 나갈 UPDATE를, 메서드 중간에 강제로 지금 당장 내보내는 것. flush = "커밋 기다리지 말고 지금 SQL 실행해"라는 명령.

---

#### 10. 버전 충돌 vs 데드락 — 비교 정리

| | 버전 충돌 (Optimistic Lock) | 데드락 |
|---|---|---|
| 발생 위치 | 애플리케이션(JPA)이 UPDATE 결과(0건)를 보고 판단 | DB 엔진 내부에서 락 대기 그래프를 보고 판단 |
| 원인 | "나보다 먼저 커밋한 사람이 있음" (명확) | 여러 트랜잭션의 락 획득 순서가 우연히 꼬임 (타이밍 문제) |
| 예측 가능성 | 예측 가능 (동시 쓰기 있으면 당연히 남) | 예측 어려움 (트래픽 패턴, 실행 타이밍에 따라 확률적) |
| 예외 타입 | `ObjectOptimisticLockingFailureException` | `DeadlockLoserDataAccessException` (`Deadlock found...`) |
| `@Version`으로 막히나 | 이게 바로 그 도구 | **못 막음** — 버전은 "누가 먼저"만 판정, 락 잡는 순서는 통제 못 함 |
| 재시도 시 결과 | 다시 조회하면 최신 버전으로 재시도 → **논리적으로 항상 해결됨** | 타이밍이 겹쳤을 뿐이라 대부분 통과되지만 **또 겹칠 수도 있음** (그래서 backoff 필요) |

둘 다 "일시적 실패"라서 `@Retryable`에 같이 걸어두지만, 성격은 완전히 다름.

---

#### 11. 헷갈렸던 지점: T1 하나가 INSERT/UPDATE를 두 번씩 하는 게 아님

데드락 타임라인 설명에서 "T1: INSERT... T1: UPDATE..."가 나오니까 "T1 혼자서 INSERT도 두 개, UPDATE도 두 개 하는 건가?" 헷갈릴 수 있음. **아님.**

**T1, T2는 각각 독립적인 출금 요청(서로 다른 스레드)이다.** 100개 스레드 중 두 개를 뽑아서 이름 붙인 것뿐, 둘 다 `withdraw()`를 처음부터 끝까지 각자 한 번씩 통째로 실행함.

```java
@Transactional
public void withdraw(String accountNumber, BigDecimal amount) {
    Account account = getAccount(accountNumber);
    account.withdraw(amount);                    // 메모리에서만 변경
    transactionHistoryRepository.save(...);       // INSERT 1개 (이 트랜잭션 것만)
    // 메서드 끝 → 커밋 → UPDATE 1개 (이 트랜잭션 것만)
}
```

| | T1이 실행하는 것 | T2가 실행하는 것 |
|---|---|---|
| INSERT | 자기 자신의 출금 이력 INSERT (1개) | 자기 자신의 출금 이력 INSERT (1개) |
| UPDATE | 자기 자신의 계좌 UPDATE (1개, 커밋 시점) | 자기 자신의 계좌 UPDATE (1개, 커밋 시점) |

→ "INSERT가 왜 두 개냐" = T1의 INSERT 1개 + T2의 INSERT 1개를 합쳐서 둘. "UPDATE가 왜 두 개냐"도 마찬가지 (T1 것 하나 + T2 것 하나).

**타임라인으로 보면:**

```
T1 스레드 흐름:  [INSERT 실행] ────────────── [메서드 끝 → 커밋 → UPDATE 시도]
T2 스레드 흐름:       [INSERT 실행] ────────────── [메서드 끝 → 커밋 → UPDATE 시도]
```

1. T1이 INSERT(자기 이력) → account에 보기 락
2. 거의 동시에 T2도 INSERT(자기 이력) → account에 보기 락 (공존 가능)
3. T1 먼저 메서드 끝나서 커밋 시도 → UPDATE 필요 → T2가 아직 보기 락 쥔 채 안 끝나서 대기
4. T2도 커밋 시도 → UPDATE 필요 → T1도 아직 안 끝났음(3번에서 대기 중) → 대기

3번과 4번이 서로 물려서 데드락.

**정리**: 더티체킹이냐 IDENTITY냐는 "엔티티 종류"에 대한 규칙이지 "T1이냐 T2냐"의 차이가 아님. T1도 T2도 **똑같이** `Account`는 더티체킹, `TransactionHistory`는 IDENTITY를 따름 — 둘 다 같은 규칙을 따르는 두 개의 독립된 실행일 뿐. `save()`는 여전히 "INSERT 아니면 UPDATE 하나만"이 맞고, 그게 T1 안에서 한 번, T2 안에서 또 한 번 일어나서 전체로 보면 2개씩 되는 것.

---

#### 12. 실제 반영 결과 (측정치)

`accountRepository.flush()`를 `deposit`/`withdraw`/`transfer`에 적용 후 재현 테스트를 4회 반복 실행:

- **데드락(`Deadlock found`)**: 4회 모두 **0건** — 완전히 사라짐
- **전체 성공률**: 87~88/100 (수정 전과 큰 차이 없음)

수정 전/후 실패의 **종류**가 바뀐 것이 핵심이다: 수정 전엔 실패 대부분이 데드락이었지만, 수정 후엔 남은 실패가 전부 **순수 낙관적 락 버전 충돌**(20 스레드가 같은 `version` 컬럼을 놓고 경쟁하는, 락 순서와 무관한 구조적 충돌)이다. 데드락 제거는 "실패의 질"을 개선한 것이지 "실패의 양"을 줄인 게 아니다 — 이 둘은 서로 다른 실패 메커니즘이라 하나를 없애도 다른 하나가 그대로 남아있으면 합계는 안 바뀐다. `@Retryable`(IS-13)이 남은 버전 충돌을 흡수하는 역할을 계속 담당한다.

이체(`transfer`) 로직도 같은 순서 문제 적용 완료. 다만 이체는 계좌 두 개가 얽혀서 `from`/`to` 조회·락 순서 자체가 별도 데드락 요인이 될 수 있음 — 이건 `docs/PRODUCTION_HARDENING_SPEC.md`의 IS-22(락 순서 고정)에서 별도로 다룸.
