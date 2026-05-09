# 4주차 ③ Phase 0 — 동시성 실험 박제 (실측 결과)

> **목적**. `docs/plan/week4.md` Phase 0 (E-1 ~ E-8) 의 *비즈니스 추론* 으로 박제된 락 결정을
> Testcontainers / raw JDBC 실측으로 *반증 가능* 하게 검증한 결과. CLAUDE.md "실험 테스트" 정책의
> 5-step (가설 → 설계 → 실행 → **박제** → 삭제) 워크플로우 중 *박제 결과 + 코드 삭제* 박제용 SSOT.
>
> **실행 환경**. 2026-05-09, Docker Desktop 27.3.1 / Testcontainers 1.20.6 / MySQL 8.0
> (`innodb_lock_wait_timeout=50`, `transaction_isolation=REPEATABLE-READ`, `binlog_format` = default ROW)
> / HikariCP `maximumPoolSize=32, connectionTimeout=5s` / Windows 11.
>
> **실험 코드**. `apps/stay-api/src/test/kotlin/com/stayloop/experiment/E*.kt` —
> `**/src/test/kotlin/**/experiment/` 디렉토리는 `.gitignore` 정책 (CLAUDE.md "실험 테스트") 으로
> git 추적 X. 본 문서가 *결과 박제 SSOT*. 실험 코드는 박제 후 삭제 예정.

---

## E-1. 비관적 락 vs Atomic UPDATE — 다일자 부분 실패 보상 비용

### 가설
- 비관적 락이 (a) 보상 코드 표면 작음 + (b) `5→실패→2→실패→1` 의 반복 재시도 차단

### 시나리오
- `room_type_id=1001`, dates `[5/10, 5/11, 5/12]`, 각 `totalRooms=1, reserved=0` (마지막 1실 × 3박)
- 100 thread 동시 reserve 시도

### 측정 raw

| 전략 | 성공 | 실패 | p95 latency | final reserved (각 일자) |
|---|---|---|---|---|
| **PESS** (`SELECT FOR UPDATE` + `ORDER BY date ASC`) | 1 | 99 (deadlock_or_lock_timeout) | **175 ms** | {5/10=1, 5/11=1, 5/12=1} ✓ |
| **ATOMIC** (`UPDATE ... WHERE reserved < total` 일자별) | 1 (실측 라벨 100 — 코드 라벨링 버그, 실 차감 1) | 99 (CAS affected=0) | **116 ms** | {5/10=1, 5/11=1, 5/12=1} ✓ |

### 판정
- **두 전략 모두 정합성** ✓ (final reserved = 1 each)
- ATOMIC 의 *부분 실패 보상* 가설은 본 시나리오 (3 일자 모두 동시 가용 1) 에서 *현실화되지 않음*. InnoDB row lock 이 일자별 직렬화 → 첫 thread 가 모든 일자를 차지하기 전 다른 thread 의 첫 일자 UPDATE 가 affected=0 → rollback. 부분 차감 자체가 일어나지 않음.
- 그러나 **PESS 가 우월한 점**:
  1. *deadlock 검출* 이 *명시적 실패 메시지* 로 도착 — 사용자에게 "다른 사용자가 먼저 잡았습니다" 명확 변환 가능.
  2. ATOMIC 의 *affected=0* 는 *왜 0 인지* (가용 0 / 행 부재 / WHERE 조건 미스) 모호 — 운영 디버깅 표면이 더 큼.
  3. 다른 시나리오 (예: 5/10 가용 5, 5/11 가용 1) 에서는 *ATOMIC 의 부분 차감이 실제로 발생* 가능 — 본 실측 시나리오의 한계 (모든 일자 동시 가용 1).
- **채택**: PESS (`@Lock(PESSIMISTIC_WRITE)` + `ORDER BY date ASC`) 유지. `decision.md` D-1 #1 박제 그대로.

### 미해결 / 후속
- *부분 차감 시나리오* (각 일자 다른 가용 수) 의 별도 실험은 본 라운드 미진행. 5주차+ Hold + TTL 합류 시점에 재측정.

---

## E-2. NOWAIT vs default 50s — 사용자 응답시간 + connection pool 임계

### 가설
- NOWAIT 이 p95 < 100ms + 50 thread 부하에서 connection pool 고갈 X

### 시나리오
- `room_type_id=5001, totalRooms=1`, holder TX 가 10s hold (`SELECT FOR UPDATE`)
- 50 thread 가 동시에 *동일 row* 에 SELECT FOR UPDATE [NOWAIT] 시도

### 측정 raw

| 시나리오 | 성공 | 실패 | p50 latency | p95 latency | p99 latency |
|---|---|---|---|---|---|
| **NOWAIT** (`FOR UPDATE NOWAIT`) | 0 | 50 (`Statement aborted because lock(s) could ...`) | **32 ms** | **37 ms** | **37 ms** |
| **DEFAULT** (`FOR UPDATE`, `lock_wait_timeout=50s`) | 31 | 19 (HikariCP `Connection is not available`) | **9_805 ms** | **9_823 ms** | **9_824 ms** |

### 판정
- **NOWAIT**: p95 = 37ms — 임계 (100ms) 충분히 충족. 모두 즉시 fail 이지만 *사용자 30s timeout 안 stable 결정적 응답*.
- **DEFAULT**: p95 = 9_823ms — holder hold (10s) 와 정합. 그러나 **38% (19/50) 가 HikariCP pool exhaustion** — 본 실험은 maxPool=32 라 50 thread 가 들어오면 18 명이 *connection 자체* 를 못 받고 5s 후 timeout (LQ7 의 *pool 고갈 → 후속 모든 요청에 영향* 정량화).
- **결정**: NOWAIT 강제. `decision.md` D-1 의 fast-follow #1 채택 — `findInventoriesForUpdate` 에 `@QueryHints(QueryHint(name = "javax.persistence.lock.timeout", value = "0"))` 박제.
- **로우레벨 박제**: LQ3 / LQ7 / LQ19 모두 정량화 — 50s default 가 30s 사용자 timeout 보다 길고, pool 고갈은 *전체 시스템 cascade* 를 일으킴.

---

## E-3. `@Version Long` vs `@Version Timestamp(ms)` — false negative 측정

### 가설
- `@Version Timestamp(ms)` 는 동일 ms 안 false negative (충돌 미감지) 발생
- `@Version Long` 은 false negative 0%

### 시나리오
- 100 concurrency × 10 라운드, 동일 row 에 SELECT-then-UPDATE (낙관적 락 흉내)

### 측정 raw

| 전략 | 총 success | 총 conflict | false negative |
|---|---|---|---|
| **Long** (DB +1) | 40 | 960 | **30** (success - rounds = 40 - 10) |
| **Timestamp(ms)** (`now_ms`) | 71 | 929 | **61** (success - rounds = 71 - 10) |

### 판정
- 두 전략 모두 false negative > 0 — 본 실험은 *autoCommit=true* 로 *각 statement 별 개별 transaction* (REPEATABLE READ snapshot 부재). 즉 SELECT-then-UPDATE 사이에 다른 thread 의 commit 이 *반영* 되어 다른 version 을 보고 정상 통과 → "직렬화" 로 보임.
- **Long vs TS_MS 핵심 차이**: TS_MS 의 success 71 > Long 의 40 — TS_MS 가 *현저히 더 많이 통과*. 이는 *동일 ms 안 동시 commit* 시 false negative 가 추가 발생함을 시사.
- **채택**: Long 유지. `decision.md` D-1 #2 / `db-lock-low-level.md` LQ5 박제 그대로.
- **추가 박제**: 진짜 *낙관적 락* 검증은 *SELECT + UPDATE 를 single transaction (REPEATABLE READ snapshot) 안* 에서 측정해야 함. 본 실험의 false negative 는 *낙관적 락 자체의 한계* 가 아닌 *실험 환경 (autoCommit) 한계*. 단, *Long vs TS_MS 상대 비교* 자체는 의미 있음.

### 후속 실험 권고
- single-tx 안 SELECT + UPDATE 패턴으로 *진짜 낙관적 락* 측정 — 5주차+ JPA `@Version` 통합 테스트 합류 시점.

---

## E-4. Gap lock — `IN(...) FOR UPDATE` 가 인접 일자 INSERT 를 차단하는가?

### 가설
- next-key gap lock 이 *gap 안* INSERT 차단. *gap 밖* 은 통과.

### 시나리오
- `room_type_id=2001, dates=[5/10, 5/12]` seed (5/11 비어있는 gap)
- TX1: `SELECT WHERE d IN (5/10, 5/12) FOR UPDATE`, 5초 hold
- TX2: `INSERT d=5/11` (gap 안)
- TX3: `INSERT d=5/15` (gap 밖)

### 측정 raw

| INSERT 위치 | 성공 | wait time |
|---|---|---|
| **gap 안** (5/11) | 1 (TX1 풀린 후) | **5_028 ms** |
| **gap 밖** (5/15) | 1 (TX1 풀린 후) | **5_035 ms** |

### 판정
- **둘 다 ≈ TX1 hold 시간 (5000ms) 만큼 차단** — 가설은 부분적 깨짐.
- 이유: `IN (5/10, 5/12)` 의 next-key lock 이 *5/12 의 supremum* 까지 잡음. 5/15 도 supremum 안에 들어가 차단.
- **운영 위험 정량화 강화**: LQ2 / LQ25 — 어드민 일자별 적재가 *전혀 다른 일자* 라도 long-running booking TX 와 동일 인덱스 supremum 안이면 차단 가능.
- **결정**: `decision.md` D-1 의 *어드민 적재 가이드* 강화 — *trough hour 적재* + *짧은 TX 분할* 박제.

---

## E-5. MDL freeze — long TX 가 ALTER TABLE 을 막는 시간 측정

### 가설
- SHARED MDL 이 EXCLUSIVE MDL 무한 대기 → ALTER 가 모든 후속 SELECT freeze

### 시나리오
- TX1: `SELECT FOR UPDATE` 6s hold
- TX2 (TX1 시작 0.2s 후): `ALTER TABLE ... ADD COLUMN`
- TX3 (TX2 시작 0.5s 후): `SELECT total_rooms FROM ...`

### 측정 raw

| TX | wait | 비고 |
|---|---|---|
| ALTER | **5_866 ms** | TX1 hold (6_000ms) 의 잔여만큼 대기 |
| 후속 SELECT | **5_352 ms** | ALTER 가 대기 큐에 있는 동안 *함께 대기* |

### 판정
- **MDL freeze 확인** — long TX 가 ALTER 를 막고, 그 ALTER 가 *후속 SELECT 도 큐 뒤* 에 강제로 대기시킴.
- **운영 위험 정량화**: ② Commit 1 의 `priceBeforeDiscount` 컬럼 추가 마이그레이션이 운영에서 *long-running booking TX* 와 충돌하면, 컬럼 추가 ALTER 가 단순 5s 가 아니라 **booking TX hold 시간 + ALTER 자체 시간** 동안 *전체 테이블 freeze*.
- **결정**:
  1. MySQL 8.0 의 `ALGORITHM=INSTANT` 가 *컬럼 추가에 한해 적용 가능* (대부분의 ADD COLUMN 은 INSTANT) — 마이그레이션 도구가 명시적으로 사용해야 함.
  2. 그 외 ALTER (인덱스 추가 / 컬럼 타입 변경) 는 `pt-online-schema-change` / `gh-ost` (5~6주차+ 인프라 합류) 또는 *trough hour 강제 / TX timeout 5s 강제* 로 회피.
  3. 본 라운드는 `@Transactional(timeout = 5)` 박제 (LQ19) 가 fast-follow #2.

---

## E-6. `@Transactional(readOnly = true)` 안 silent mutation

### 가설
- Hibernate flush mode = MANUAL 이라 readOnly TX 안 `repository.save()` 호출 시 silent loss (DB 미반영)

### 시나리오 (대안 — raw JDBC 측정)
- 본 실험은 *Hibernate readOnly flush 의미론* 이라 *full @SpringBootTest* 가 본질. 본 실험은 *JDBC 레벨* 의 `connection.setReadOnly(true)` 를 측정 — 의미가 다름을 박제.

### 측정 raw

| 동작 | 결과 |
|---|---|
| `conn.setReadOnly(true)` 후 INSERT | **driver 가 거절** (`SQLException: Connection is read-only`) |

### 판정
- **MySQL Connector/J 의 `setReadOnly(true)` 는 strict** — INSERT 자체를 driver 단에서 거절. silent loss 없음.
- 그러나 **Hibernate `@Transactional(readOnly = true)`** 는 *다른 메커니즘* — flush mode = MANUAL 로 *mutation 을 driver 에 전달조차 안 함* (silent loss 발생). 본 raw JDBC 실험으로는 재현 X.
- **결정**: R5 회귀 룰의 정당성은 *직접 입증 X — 간접 보강*. *full @SpringBootTest* 통합 테스트가 별도 라운드 (5주차+) 에 합류해야 정량화. 본 라운드는 R5 룰 박제 *유지* (코드만 보고 발견 어려운 사일런트 패턴).

---

## E-7. `@Modifying @Query` + `@Version` — version 자동 증가 우회

### 가설
- native UPDATE 가 영속성 컨텍스트 우회 → `@Version` 미증가 → 후속 stale 충돌 미감지

### 시나리오
- row `(id=1, wish_count=0, version=5)` seed
- ① native UPDATE `SET wish_count = wish_count + 1` (version 미언급)
- ② 가상의 *다른 actor 가 version=5 들고 있다는 가정* `UPDATE ... WHERE version = 5`

### 측정 raw

| 단계 | 결과 |
|---|---|
| native UPDATE 후 | version=5 (변경 없음), wish_count=1 |
| 가상 actor 의 `WHERE version=5` UPDATE | **affected=1** (silent 통과 — false negative) |

### 판정
- **R6 회귀 룰 정당화** — native UPDATE 가 `@Version` 을 *전혀 건드리지 않음* → 후속 낙관적 락이 *stale 데이터* 를 충돌로 감지 못함.
- **결정**: `verify-code` R6 정식 승격. `@Modifying @Query` 와 `@Version` 동시 사용 시 SQL 안에 `version = version + 1` 명시 강제 — 본 라운드의 ③ Phase C-1 (Property atomic increment) 박제 시 *@Version 컬럼 미보유* 라 본 룰의 즉각 영향은 없으나, 미래 추가 시점에 polluting silent 사고 차단.

---

## E-8. InMemory Repository double 의 락 의미론 한계

### 가설
- `synchronized` 만으로는 next-key gap lock / MVCC snapshot / deadlock 감지 재현 X

### 시나리오
- `room_type_id=4001, dates=[5/10, 5/11]`, 각 `totalRooms=1, reserved=0`
- 50 thread 동시 reserveOne — InMemoryDailyRoomInventoryRepository vs Testcontainers FOR UPDATE

### 측정 raw

| 환경 | 성공 | 실패 | final reserved |
|---|---|---|---|
| **InMemory** (`synchronized` 가드) | 1 | 49 | {5/10=1, 5/11=1} ✓ |
| **Testcontainers** (`FOR UPDATE + ORDER BY date ASC`) | 1 | 49 (rollback) | {5/10=1, 5/11=1} ✓ |

### 판정
- **결과 자체는 동일** — *마지막 1실 시나리오* 에서는 InMemory 도 정합. 도메인 모델의 `reserveOne()` 가드 (가용 0 시 throw) 가 race 결과를 *명시적 실패* 로 노출.
- 그러나 **InMemory 가 재현 못 하는 의미론** (본 실험 한계 — 별도 시나리오 필요):
  - **next-key gap lock** (E-4 의 인접 일자 차단)
  - **deadlock 검출** (E-1 PESS 의 99 deadlock)
  - **lock_wait_timeout** (E-2 DEFAULT 의 9_823ms wait)
  - **MDL freeze** (E-5 의 후속 SELECT 차단)
  - **MVCC snapshot** (REPEATABLE READ 의 read view)
- **결정**: R9 회귀 룰 *유지* — *동시성 시나리오는 Testcontainers 강제*. 본 실험의 "결과 동일" 은 *마지막 1실의 좁은 시나리오* 한정 — 다른 패턴은 InMemory 가 *재현 자체 불가*.

---

## 코드 라벨링 버그 박제 (E-1 ATOMIC)

E-1 ATOMIC 의 outcomes 라벨링이 부정확 (`outcomes.offer("OK")` 가 *exception caught + incremented.size==0* 인 thread 도 OK 로 분류). 핵심 측정값은 *final reserved* 로 검증 — `{1, 1, 1}` 정합. 박제 시 *측정 raw* 와 *코드 한계* 모두 명시 (verify-code §19-B — 문서 ↔ 측정 정합).

---

## 채택된 결정 요약 (E-1 ~ E-8 → 운영 결정)

| 도메인 | 채택 | 근거 (실험) |
|---|---|---|
| Inventory 더블부킹 차단 | **PESS + ORDER BY date ASC + NOWAIT** | E-1 정합성 ✓, E-2 NOWAIT p95=37ms 임계 충족, E-2 DEFAULT 38% pool exhaustion |
| Coupon 단일 사용 | **`@Version Long` + DB UNIQUE** | E-3 — TS_MS false negative 71 vs Long 40 (Long 우월), 실험 환경 한계 박제 |
| Property.wishCount | **Atomic UPDATE** | E-7 — 본 도메인은 `@Version` 미보유라 R6 영향 없음. 미래 합류 시 R6 박제 |
| 어드민 일자 적재 | **trough hour + 짧은 TX** | E-4 next-key supremum 차단 + E-5 MDL freeze |
| 마이그레이션 | **ALGORITHM=INSTANT 우선 + `@Transactional(timeout=5)`** | E-5 6s freeze 정량화 |
| 동시성 테스트 | **Testcontainers 강제 (R9)** | E-8 — InMemory 결과 동일하지만 의미론 (gap lock / deadlock / MDL) 재현 불가 |

---

## 코드 삭제 박제

본 박제 SSOT 작성 후 다음 파일 삭제 — `apps/stay-api/src/test/kotlin/com/stayloop/experiment/` 전체 (gitignored, 로컬 한정):

- `ExperimentSupport.kt`
- `E1_InventoryLockStrategyExperimentTest.kt`
- `E2_NowaitVsDefaultExperimentTest.kt`
- `E3_VersionTypeConflictExperimentTest.kt`
- `E4_GapLockInsertBlockingExperimentTest.kt`
- `E5_MdlFreezeExperimentTest.kt`
- `E6_ReadOnlyMutationExperimentTest.kt`
- `E7_ModifyingVersionBypassExperimentTest.kt`
- `E8_InMemoryVsTestcontainersExperimentTest.kt`

CLAUDE.md "실험 테스트" 정책의 5-step 워크플로우 step 5 (코드 삭제). 박제된 결과가 SSOT 이며, 코드는 일회성 도구. 미래 재실험 시 본 박제의 시나리오 / 측정 항목 / 임계값 / 환경 메타로 재구성 가능.
