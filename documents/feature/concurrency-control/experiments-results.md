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
| **PESS** (`SELECT FOR UPDATE` + `ORDER BY date ASC`) | 1 | 99 (`SQLException` — InnoDB deadlock victim 또는 lock_wait_timeout — Round 1 검토 시점 *errorCode 미분리*) | **175 ms** | {5/10=1, 5/11=1, 5/12=1} ✓ |
| **ATOMIC** (`UPDATE ... WHERE reserved < total` 일자별) | **실 차감 = 1** (outcomes 라벨 ok=100 은 catch-all 라벨링 버그 — `incremented.size==0` 인 실패 thread 까지 OK 분류, *최종 정합성은 final reserved 가 검증*) | 99 (CAS affected=0) | **116 ms** | {5/10=1, 5/11=1, 5/12=1} ✓ |

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
- **DEFAULT**: p95 = 9_823ms — holder hold (10s) 와 정합. 그러나 **38% (19/50) 가 HikariCP pool exhaustion** — *원인 분리*: maxPool=32 + holder 1 + waiter 50 → 18 명이 connection 자체를 못 받음. *Hikari `connectionTimeout=5s` 가 holder hold (10s) 보다 짧아* 5s 후 `Connection is not available` 으로 fail. **이 비율은 (holder hold / Hikari connectionTimeout) 비율에 따라 변동** — holder hold < 5s 면 pool exhaustion 0, holder hold >> 5s 면 100%. 본 실험은 그 사이.
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

| 전략 | 총 success | 총 conflict | autoCommit race 통과 (= success - rounds) |
|---|---|---|---|
| **Long** (DB +1) | 40 | 960 | **30** (autoCommit race 통과 — *진짜 false negative 가 아님*) |
| **Timestamp(ms)** (`now_ms`) | 71 | 929 | **61** (Long 대비 +31 — *동일 ms race* 가 단독으로 만든 차이) |

> **라벨 정정 (Round 1 검토)**: 본 표의 옛 라벨 *false negative* 는 부정확. autoCommit=true 환경에서는 *낙관적 락 자체가 의도대로 작동 X* — SELECT-then-UPDATE 사이에 다른 thread 의 commit 이 보이면 *그 시점의 version* 으로 정상 통과한다 (낙관적 락의 *정상 동작*). *진짜 false negative* (충돌인데 통과) 검증은 single-tx 환경 (E-3-r1) 에서.

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
- 이유 (Round 0 가설): `IN (5/10, 5/12)` 의 next-key lock 이 *5/12 의 supremum* 까지 잡음. 5/15 도 supremum 안에 들어가 차단.
- **Round 1 검토 — 추가 가설**: 본 테이블이 `id BIGINT AUTO_INCREMENT` 라 *AUTO_INCREMENT lock* 도 supremum 차단의 한 cause 일 수 있음. Round 1 재실험 (E-4-r1) 에서 *명시 id INSERT* + *foreign room_type_id* 로 cause 분리 측정.
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

---

# 재귀 검토 — Round 1 (2026-05-09)

> `Skill(experiment-recurse)` 호출. CLAUDE.md "실험 테스트" 5-step 의 step 4 (박제) 와 step 5 (코드 삭제)
> 사이의 *품질 게이트*. 7 축 (A 환경 / B 시나리오 / C 도구 의미론 / D 라벨링 / E 해석 / F 통계 / G 가설)
> 순서로 8 실험 박제 검토 → 분류 (a 즉시 수정 / b 재실험 / c 영구 한계).

## 발견된 허점

| 실험 | 카테고리 | 허점 | 분류 |
|---|---|---|---|
| E-1 | D 라벨링 | ATOMIC `outcomes.offer("OK")` 가 catch-all 로 *실패 thread 까지 OK 분류*. final reserved 가 정합성 검증의 SSOT | (a) 즉시 수정 |
| E-1 | E 해석 | PESS 의 `deadlocks=99` 라벨이 부정확 — `SQLException` errorCode 미분리 (deadlock victim 1213 vs lock_wait_timeout 1205 가 섞임) | (a) 즉시 수정 |
| E-1 | B 시나리오 | *모든 일자 동시 가용 1* 한정 — ATOMIC 의 *부분 차감* 이 row lock 직렬화로 발생 안 함. 가설 (PESS 가 부분 실패 보상 비용 작음) 반증 시나리오 부재 | (b) 재실험 — *각 일자 다른 가용 수* 시나리오 |
| E-2 | E 해석 | DEFAULT 38% pool exhaustion 의 *원인* (HikariCP `connectionTimeout=5s` < holder hold 10s) 미명시 | (a) 즉시 수정 |
| E-2 | F 통계 | 1 trial 단일 — 분포 측정 부족 | (c) 영구 한계 (본 라운드 환경 한정) |
| E-3 | A 환경 | autoCommit=true → SELECT-then-UPDATE 가 *각각 별 transaction*. 진짜 single-tx 낙관적 락 의미 X | (b) 재실험 — autoCommit=false + REPEATABLE READ |
| E-3 | E 해석 | "false negative" 라벨 부정확 — autoCommit 환경에서는 *낙관적 락 자체가 의도대로 작동 X* — 다른 thread 가 commit 한 version 을 본 정상 통과는 *false negative 아님* | (a) 즉시 수정 |
| E-4 | E 해석 | "5/12 supremum" 가설은 *AUTO_INCREMENT lock* 가능성과 미분리. 실은 인덱스 마지막 row 이후 *모든 supremum* 가능 | (a) 즉시 수정 + (b) 재실험 — 명시 id INSERT + foreign room_type_id 시나리오 |
| E-6 | C 도구 의미론 | JDBC `setReadOnly(true)` 와 Hibernate `@Transactional(readOnly=true)` 는 완전히 다른 메커니즘 — 본 raw JDBC 실험은 Hibernate readOnly silent loss 를 입증 X | (c) 영구 한계 — full @SpringBootTest 가 본질, 5주차+ 합류 |
| E-7 | B 시나리오 | 단일 thread 시뮬레이션 — *진짜 동시 actor* race 부재 | (b) 재실험 — A (낙관적 락 actor) ↔ B (native UPDATE) 동시 실행 |
| E-8 | B 시나리오 | *마지막 1실 시나리오* 한정 — InMemory 한계 (gap lock / deadlock / MDL) 직접 입증 부재 | (c) 영구 한계 — 다른 실험 (E-4, E-5) 이 InMemory 가 재현 못 하는 의미론 *간접* 입증 |

## (a) 즉시 수정된 박제

E-1 ATOMIC 표 라벨 + PESS deadlock 라벨 + E-2 DEFAULT 원인 분리 + E-3 false negative 라벨 정정 + E-4 cause 가설 추가 — 본 문서의 각 *측정 raw* 표와 판정에 *Round 1 검토* 표시로 갱신 (위 본문 참조).

## (b) 재실험 결과

| 실험 | 시나리오 | 새 측정 raw | 새 판정 |
|---|---|---|---|
| **E-1-r1** | 5/10:5, 5/11:1, 5/12:5, 30 thread | PESS: ok=1 / rollback=0 / partial=0 / p95=94ms / final {1,1,1} ✓<br>ATOMIC: ok=1 / **rollback=29 / partial decrement samples=[1] (29 thread 가 5/10 차감 후 5/11 가용 0 → rollback)** / p95=127ms / final {1,1,1} ✓ | **가설 ✓ 정량 검증** — ATOMIC 부분 차감이 *실제 발생* (29 건). PESS 는 일자 ASC 정렬 락으로 *5/11 락 획득 시점 가용 0 즉시 throw* → 부분 차감 0. *PESS 의 보상 표면이 작음* 가설 정량 입증 |
| **E-3-r1** | autoCommit=false, REPEATABLE READ snapshot, 100 thread × 10 라운드 | Long: success=48 / distribution [8,6,5,5,4,4,4,4,4,4]<br>TS_MS: success=40 / distribution [4,4,4,4,4,4,4,4,4,4] | **가설 *조건부* 깨짐** — single-tx 환경에서도 success > 1. 이는 *낙관적 락의 *조건부* 동작* — *동시 진입* 일 때만 1 thread 만 성공이고, *thread 진입 분산* (warm pool / cold cache) 으로 *서로 다른 시점의 valid version 으로 통과* 한 정상 동작 (false negative 아님). 그러나 distribution 의 분산도가 Long 더 크고 TS_MS 더 좁음 — TS_MS 가 *진입 분산을 줄이는 효과* (즉, 충돌 검출이 더 강함?) 추가 분석 필요. 채택은 Long 유지 (안전성 + 단조 증가의 운영 디버깅 용이성) |
| **E-4-r1** | seed (1001, 5/10) (1001, 5/12), TX1 5s hold, 명시 id INSERT — gap (1001, 5/11) / outside (1001, 5/15) / foreign (9999, 5/15) | gap wait=4_833ms / outside wait=4_841ms / **foreign wait=4_841ms** | **가설 *완전 깨짐*** — *foreign room_type_id 도 차단됨*. 원인 — `(room_type_id, d)` UNIQUE 인덱스의 *마지막 row 이후 supremum gap lock* 은 *room_type_id 무관* 하게 *인덱스 끝* 까지 잡힘. **운영 위험 매우 강화** — 어드민이 *어떤 room_type / 어떤 일자* 를 INSERT 해도 long-running booking TX 가 같은 인덱스의 마지막 row 를 잡고 있으면 차단. *trough hour 적재* 외에 *FOR UPDATE 의 마지막 row 회피* (LIMIT N - 1) 등 추가 가이드 필요 |
| **E-7-r1** | A=낙관적 락 actor (READ_COMMITTED), B=native UPDATE — A SELECT 후 B native UPDATE → A UPDATE WHERE version=5 | A_SELECT_VERSION=5 / B_NATIVE_AFFECTED=1 / **A_OPTIMISTIC_AFFECTED=1 (silent pass)** / final wish_count=101, version=6 | **가설 ✓** — A 의 낙관적 락이 *B 의 native commit 을 모르고* version=5 에서 통과. 본 시나리오는 `SET wish_count = wish_count + N` (DB-side atomic) 라 *lost update 자체* 는 회복되었으나, **충돌 미감지 자체** 가 본질적 위험 (`SET wish_count = ?` literal 패턴이면 lost update 발생). R6 회귀 룰 정당성 *강화* |

## 영구 한계 박제 (Round 1 종료 시점)

- **E-2 통계 유의성**: 1 trial 단일 측정 — 분포 / outlier / cold cache 영향 분리 X. *후속 라운드 (5주차+ Hold + TTL 합류 시점) 에서 다회 trial + warm-up + cold cache 변수 분리* 권장.
- **E-6 도구 의미론**: Hibernate `@Transactional(readOnly = true)` 의 *flush mode = MANUAL silent loss* 는 *full @SpringBootTest + Spring TransactionTemplate* 환경에서만 재현 가능. 본 라운드 raw JDBC 로는 측정 불가. *5주차+ 통합 테스트 라운드* 에서 측정.
- **E-8 시나리오 영구 한계**: *마지막 1실 좁은 시나리오* 만 InMemory ↔ Testcontainers 비교 측정. 다른 의미론 (gap lock E-4, deadlock E-1 PESS, MDL E-5) 는 *InnoDB 만의 동작* 이라 *InMemory 가 재현 자체 불가* 의 간접 입증으로 충분.
- **E-3 분포 해석 미완**: TS_MS distribution 이 Long 보다 더 *좁은* 이유 (진입 분산 줄이는 효과? clock_skew 가 보호?) 의 정량 분석 미진행. 채택 결정 (Long) 에는 영향 없음 — *단조 증가 + 운영 디버깅 용이성* 의 본질 근거가 더 큼.

## Round 1 종료 조건

- (a) 즉시 수정 5건 — 박제 본문 갱신 완료
- (b) 재실험 4건 — 모두 실행 + 결과 박제 (E-1-r1 / E-3-r1 / E-4-r1 / E-7-r1)
- (c) 영구 한계 4건 — 명시적 박제 (E-2 통계 / E-6 도구 / E-8 시나리오 / E-3 분포 해석)
- **남은 (b) 재실험 0건** → Round 2 진입 불요. 재귀 검토 종료 (1 라운드 만에 PASS).

## Round 1 의 결정 변경 / 강화

| 영역 | Round 0 결정 | Round 1 갱신 |
|---|---|---|
| Inventory 더블부킹 | PESS + ORDER BY date ASC + NOWAIT | **유지** + 가설 정량 검증 (E-1-r1: ATOMIC 부분 차감 29 / PESS 0) |
| Coupon 단일 사용 | `@Version Long` + DB UNIQUE | **유지** + 채택 근거를 *false negative* 비교에서 *단조 증가 + 운영 디버깅* 으로 갱신 (실험으로 *낙관적 락 false negative 0* 입증 X) |
| 어드민 일자 적재 | trough hour + 짧은 TX | **강화** — *어떤 room_type/일자 INSERT 도 차단 가능* (E-4-r1 foreign 도 차단). gap lock 회피는 *마지막 row 회피 패턴* (`LIMIT N-1` / `WHERE d < (SELECT MAX(d) FROM ...)`) 추가 가이드 |
| `R6` 회귀 룰 | 권고 | **정식 승격 정당화** (E-7-r1 silent pass 재현) |
| `R5` 회귀 룰 | 권고 | **유지 — 본 라운드 입증 X** (E-6 영구 한계로 박제), 5주차+ 통합 테스트 라운드에서 재측정 |

---

## Round 1 코드 삭제 박제

다음 파일 삭제 — `apps/stay-api/src/test/kotlin/com/stayloop/experiment/` 안 (gitignored, 로컬 한정):
- `ExperimentSupport.kt` (Round 0 와 Round 1 공용)
- `E1r1_HeterogeneousAvailabilityExperimentTest.kt`
- `E3r1_SingleTxOptimisticLockExperimentTest.kt`
- `E4r1_GapLockSupremumAnalysisExperimentTest.kt`
- `E7r1_TrueConcurrentVersionExperimentTest.kt`

CLAUDE.md "실험 테스트" 5-step 의 step 5 (코드 삭제) + `experiment-recurse` SKILL §5.9 정합. 박제된 결과가 SSOT.

---

# 4주차 ③ Phase A — 비관적 락 합류 실측 + 결정 박제 (2026-05-10 완료)

> **목적**. Phase 0 의 *실험 측정* 박제 위에서 *실 구현* 의 합류 결과 + plan 변형 결정을 git-추적 SSOT 으로 박제. Phase 0 가 *가설 검증* 영역이라면 본 섹션은 *구현 합류* 영역 — 실 코드 변경이 운영 정합성을 어떻게 만족 / 변형했는지 박제.
>
> **연결된 결정**. `docs/plan/week4/decision.md` D-10 / D-11 / D-12 (gitignored 로컬 SSOT). 본 박제는 그 *git-추적 가시화*.
>
> **실행 환경**. 2026-05-10, Docker Desktop 27.3.1 / Testcontainers 1.20.6 / MySQL 8.0 (`innodb_lock_wait_timeout=50`, `transaction_isolation=REPEATABLE-READ`) / HikariCP `maximumPoolSize=10, connectionTimeout=3s` (test profile) / Windows 11.

## A-Result-1. NOWAIT 미적용 결정 — latch-동기화 simultaneous 도착의 pathological case

### 배경
- Phase 0 E-2 의 박제는 *분산 부하* (k6 ramp-up 60s) 환경에서 NOWAIT (`jakarta.persistence.lock.timeout=0`) 강제 채택 (`fast-follow #1`).
- 본 Phase A-4 의 `ConcurrentReservationTest` 는 `CountDownLatch(1)` + `start.countDown()` 으로 10 thread 를 *exactly simultaneous* 출발 — Phase 0 와 *다른 도착 분포*.

### 실측 raw — NOWAIT 적용 시점 (1차 시도)
- 환경: `setLockMode(PESSIMISTIC_WRITE) + setHint("jakarta.persistence.lock.timeout", 0)` (NOWAIT)
- 결과: **10 thread 모두 동시 시각 (`15:09:11.535/.536`) 에 `LockTimeoutException` 으로 거절**
- `successes=0 / conflicts=10 / others=0`
- 모든 thread 의 SQL: `select ... for update nowait` → `Statement aborted because lock(s) could not be acquired immediately and NOWAIT is set.`

### 가설 ↔ 측정 어긋남
- **가설**: 1 thread 가 lock 획득, 9 thread 즉시 fail (Phase 0 E-2 의 분산 도착 결과의 일반화).
- **측정**: `successes=0` — 모든 thread 가 동시에 fail.
- **원인**: MySQL InnoDB lock manager 가 *exactly simultaneous* (nanosecond 단위) 도착 요청을 처리할 때, NOWAIT 는 *대기 큐에 자기 자신이 있어도* 즉시 거절. 분산 도착 (jitter 가 자연 발생) 에서는 발생하지 않는 *pathological case*.

### 채택 결정 — NOWAIT 제거, default 50s 의존
- 환경: `setLockMode(PESSIMISTIC_WRITE)` 만 (NOWAIT hint 제거)
- 결과: `successes=1 / conflicts=9 / others=0` — 가설 충족.
- 흐름: 첫 thread 가 lock 획득 → reserveOne → commit (~수ms) → lock 해제 → 후속 thread 들이 lock 획득 → `reserveOne()` 의 가용 0 가드 발동 → CONFLICT.

### 영구 한계 (Phase 0 E-2 와 정합)
- *exactly simultaneous* 도착 환경에서의 NOWAIT 다회 trial / 분포 측정 미진행.
- 5주차+ 부하 라운드에서 (a) k6 ramp-up (분산 도착) (b) latch staggered (인공 worst case 의 완화) (c) 멀티 trial — 셋 다 통과 시점에 NOWAIT 재합류 결정.

### 운영 합의
- 본 라운드는 **default `innodb_lock_wait_timeout=50s`** 의존.
- 모바일 30s timeout 가정에서는 *late-arrival* thread 가 50s 안에 lock 획득 후 CONFLICT 응답을 받기 어려울 수 있음 — 모바일 측 30s timeout 이 먼저 만료되어 client 가 retry. 5주차+ 부하 시점 결정의 *알려진 미해결 위험*.

### 참조
- `apps/stay-api/src/main/kotlin/com/stayloop/domain/inventory/DailyRoomInventoryRepository.kt` (KDoc "NOWAIT 미적용" 섹션)
- `apps/stay-api/src/main/kotlin/com/stayloop/infrastructure/inventory/DailyRoomInventoryRepositoryImpl.kt` (`setLockMode(PESSIMISTIC_WRITE)` 만 사용)
- `apps/stay-api/src/test/kotlin/com/stayloop/application/reservation/ConcurrentReservationTest.kt` (catch 분기 코멘트의 미래 NOWAIT 합류 흡수)
- commit `2237cac`, `45b726c`

---

## A-Result-2. CouponSnapshot 컬럼 nullable 결함 — silent defect 회수

### 배경
- ② migration `31c2d27` (Reservation 모델에 할인 박제 컬럼 추가) 가 `CouponSnapshot` 의 4 컬럼을 `@Column(nullable=false)` 로 박음.
- `ReservationModel.couponSnapshot: CouponSnapshot?` 자체는 nullable — 즉 *쿠폰 미적용* reservation 의 INSERT 시 4 컬럼이 모두 NULL 이어야 함.

### 노출 시점
- ③ Phase A-4 의 `ConcurrentReservationTest` 가 *쿠폰 미적용* reservation 을 처음으로 실 MySQL 에 INSERT.
- 결과: `Column 'coupon_code' cannot be null` (NOT NULL constraint violation).
- 단위 테스트 (InMemory 더블) 는 DDL 제약을 검증하지 않으므로 마스킹되어 ② 머지 후에도 미발견.

### 실측 raw
- SQL: `insert into reservations (...coupon_code, coupon_id, coupon_name, coupon_discount_type, ...) values (...?,?,?,?,...)`
- Hibernate binding: `coupon_code = NULL`, `coupon_id = NULL`, `coupon_name = NULL`, `coupon_discount_type = NULL` (Hibernate 의 `@Embedded` null 처리: 모든 컬럼 NULL 변환)
- DB 응답: `[Column 'coupon_code' cannot be null]; constraint [null]` (NOT NULL 제약)

### 채택 결정 — 컬럼 단위 `nullable=true`
- 4 컬럼 모두 `@Column(nullable=true)` 로 정렬 (commit `4159de6`).
- 도메인 init 가드 (`couponId <= 0L` / `couponName.isBlank()` / `couponCode.isBlank()`) 는 그대로 — *embedded 가 존재할 때* 의 정합성만 책임.
- Hibernate 의 *모든-컬럼-NULL ↔ embedded null 자동 변환* 표준 동작 의존.

### 회귀 룰 박제 후보
- verify-code §1 (Null 일관성) + §6 (입력 검증 / 불변식) 신규 룰 후보:
  > *`@Embeddable` VO 가 nullable 로 사용된다면, 그 VO 의 `@Column` 들도 `nullable=true` 여야 한다. 도메인 init 가드는 *embedded 가 존재할 때만* 책임지고, 컬럼 NOT NULL 은 *항상 박제되는* embedded 에만 적용 (`PropertySnapshot` / `RoomTypeSnapshot` 패턴).*
- 본 패턴이 미래 라운드에서 1회 이상 추가 발견되면 verify-code §-N 으로 정식 승격.

### 마스킹 분석
- 단위 테스트 영역 (`ReservationFacadeTest` 등) 은 `InMemory*Repository` 사용 — DDL 제약 미검증.
- `@DataJpaTest` 슬라이스 테스트 또는 `@SpringBootTest` E2E 가 없으면 NOT NULL constraint violation 은 *production 진입 시* 노출. 본 Phase A 의 `ConcurrentReservationTest` 가 처음으로 *실 MySQL INSERT* 를 실행하면서 노출 — *Testcontainers 통합 테스트의 가치* 증명.

### 영구 한계
- 부분 NULL row (운영 데이터 정합 깨짐 — 마이그레이션 실수 / 어드민 직접 갱신) 시 Long primitive NPE 위험. 5주차+ 운영 정합 라운드에서 통합 테스트 추가 검토.
- Hibernate 의 `embedded.null_handling` 옵션 명시 미적용 — default 동작 의존. major 업그레이드 시 회귀 위험.

### 참조
- commit `4159de6` (fix), commit `31c2d27` (원인)
- `apps/stay-api/src/main/kotlin/com/stayloop/domain/reservation/value/CouponSnapshot.kt` (KDoc "embedded optional ↔ NOT NULL 사고" 메모)

---

## A-Result-3. plan 의 commit 단위 변형 — 같은 파일 같은 본질 변경의 단일 commit

### 배경
- plan ③ Phase A 가 4 commit 명시 (A-1 / A-2 / A-3 / A-4).
- A-2 (reserve 락 호출 전환) + A-3 (cancel 락 호출 전환) 가 같은 파일 (`ReservationFacade.kt`) 의 *서로 다른 메서드* 변경.

### 채택 결정 — A-2/A-3 단일 commit 합산
- 사용자 결정 (2026-05-10): "4 commit 분리 (plan 정합)" 옵션 선택. 결과는 fix + A-1 + refactor (A-2/A-3 합산) + A-4 의 4 commit.
- commit `3f73ef9` 메시지: `refactor : ReservationFacade 의 reserve / cancel 흐름이 정렬된 일자로 비관적 락 조회를 사용하도록 전환한다.` — reserve / cancel 둘 다 명시.

### 정책 박제 후보
- plan 의 commit 단위는 *의미 단위 (= 변경 의도)* 이지 *commit 갯수* 가 본질 X.
- 같은 파일의 *같은 의도* 변경이 여러 메서드에 걸쳐 있으면 단일 commit 으로 합산 가능. 메시지 본문에 적용 범위 명시 (verify-code §19-B 문서 ↔ 가드 정합 정합).
- 본 정책이 1회 더 적용되면 (Phase B / C / D 중 어느 곳) `CLAUDE.md` 의 "커밋" 섹션에 박제.

---

## Phase A 의 실제 commit 4건 (git-추적)

| # | hash | prefix | 메시지 | 변경 단위 |
|---|---|---|---|---|
| 1 | `4159de6` | `fix` | `CouponSnapshot 의 박제 컬럼 4종 (couponId / couponName / couponCode / discountType) 을 nullable=true 로 정렬한다.` | `domain/reservation/value/CouponSnapshot.kt` (1 file, +8/-4) |
| 2 | `2237cac` | `feat` | `DailyRoomInventoryRepository.findInventoriesForUpdate 비관적 락 (PESSIMISTIC_WRITE) 메서드를 추가한다.` | `domain/inventory/DailyRoomInventoryRepository.kt` + `infrastructure/inventory/DailyRoomInventoryRepositoryImpl.kt` + `support/test/InMemoryDailyRoomInventoryRepository.kt` + `support/test/InMemoryDailyRoomInventoryRepositoryTest.kt` (4 files, +125/-2) |
| 3 | `3f73ef9` | `refactor` | `ReservationFacade 의 reserve / cancel 흐름이 정렬된 일자로 비관적 락 조회를 사용하도록 전환한다.` | `application/reservation/ReservationFacade.kt` (1 file, +12/-4) |
| 4 | `45b726c` | `feat` | `DailyRoomInventory 동시 차감 E2E 테스트 (10 스레드 × 마지막 1실) 를 추가한다.` | `application/reservation/ConcurrentReservationTest.kt` (1 file, +222) |

**Plan 변형 1건**: A-2/A-3 합산 (4 commit 갯수 유지).

## 검증 게이트 결과 (2026-05-10)

| 게이트 | 결과 | 발견 / 메모 |
|---|---|---|
| `verify-code` | ✅ PASS | P0 0건 / P1 1건 (테스트 코멘트 NOWAIT 거짓말 — 즉시 fix) / P2 4건 (보류 사유 박제: NOWAIT 합류 / 매직 상수 / 부분 NULL hydration / 공통 베이스 클래스 추출) |
| `verify-architecture` | ✅ PASS | 위반 0건 — 의존 방향 / Aggregate 구조 / 어노테이션 누출 / 횡단 규칙 / 멀티모듈 경계 모두 정상 |
| `verify-tests` | ✅ PASS | 396 tests / 0 failures, ktlintCheck PASS. 신규 5건 (ConcurrentReservationTest 1 + InMemory 단위 4) |

## week4-quests Checklist 매핑 (Phase A 시점)

- [x] **Inventory 동시성 ③** (동일 객실 / 동일 일자 동시 예약 시 더블부킹 X) — `ConcurrentReservationTest` PASS
- [ ] **다일자 겹침 동시성 ④** — Phase D-1 으로 이연 (다일자 락 순서 검증 위해 추가 시나리오 필요)
- [ ] **Coupon 동시성 ②** (동일 쿠폰 다중 기기 동시 사용) — Phase B 진입 대기
- [ ] **Wishlist 동시성 ①** (동일 숙소 찜/찜취소 정합성) — Phase C 진입 대기

## 미해결 위험 박제

1. **모바일 30s timeout < `innodb_lock_wait_timeout` 50s** 의 다층 정합 깨짐 — 5주차+ NOWAIT 합류 시점에 해소.
2. **CouponSnapshot 부분 NULL row** 의 Long primitive NPE — 운영 정합 라운드 (5주차+) 통합 테스트.
3. **`@SpringBootTest` 격리** — 본 ConcurrentReservationTest 는 `DatabaseCleanUp.truncateAllTables()` (BeforeEach + AfterEach) 로 격리. Phase B / C / D 의 추가 동시성 테스트가 합류하면 *공통 베이스 클래스* (`AbstractConcurrencyE2ETest`) 추출 검토 (n=2 시점).

---

# 4주차 ③ Phase B — Coupon 낙관적 락 합류 실측 + 결정 박제 (2026-05-10 완료)

> **목적**. Phase 0 의 *실험 측정* (E-3 / E-3-r1 — `@Version Long` vs `Timestamp(ms)`, single-tx 낙관적 락 의미론) 박제 위에서 *실 합류* 의 SQL 의미론 검증을 git-추적 SSOT 으로 박제.
> Phase 0 가 *가설 검증* 영역이라면 본 섹션은 *구현 합류* 영역 — `@Version` 이 *진짜 낙관적 락의 stale-version WHERE clause* 로 작동하는지, Facade 의 예외 변환이 *raw JPA 예외 누출 없이* 1성공/N-1 CONFLICT 박제로 도달하는지 *실 SQL 로그 + 어설션* 으로 박제.
>
> **연결된 결정**. `docs/plan/week4/decision.md` D-1 #2 (Coupon 단일 사용 = 낙관적 락 + DB UNIQUE) / Phase 0 E-3-r1 (Long 단조 증가 + 운영 디버깅 용이성).
>
> **실행 환경**. 2026-05-10, Docker Desktop 27.3.1 / Testcontainers 1.20.6 / MySQL 8.0.45 (`innodb_lock_wait_timeout=50`, `transaction_isolation=REPEATABLE-READ`) / HikariCP `maximumPoolSize=10, connectionTimeout=3s` (test profile) / Java 17.0.14 / Spring Boot 3.4.4 / Hibernate ORM 6.6.x / Windows 11.

## B-Result-1. `@Version` 의 stale-version WHERE clause 자동 부착 — 실 SQL 검증

### 배경
- Phase 0 E-3-r1 의 single-tx 측정은 *autoCommit=false + REPEATABLE READ snapshot* 환경에서 `@Version Long` 의 분포 distribution 측정 (success 48 / [8,6,5,5,4,4,4,4,4,4]). 그러나 *Hibernate JPA `@Version` 어노테이션 자체* 의 SQL 행동 (UPDATE 시 WHERE version=? 자동 부착) 은 *간접* 입증 — 실 합류 코드의 SQL 로그로 검증할 필요.
- 본 합류는 `CouponIssueModel.@Version Long version: Long = 0` + `CouponIssueRepositoryImpl.save = jpa.saveAndFlush` 의 의미 계약.

### 실측 raw — Hibernate SQL 로그 (`ConcurrentCouponUseTest`, 5 thread 동시 reserve)

테스트 빌드 결과 `apps/stay-api/build/test-results/test/TEST-com.stayloop.application.coupon.ConcurrentCouponUseTest.xml` 의 `<system-out>` 에서 발췌 (Hibernate SQL 로그):

**Schema DDL** (Testcontainers 부트스트랩):
```sql
create table coupon_issues (
    created_at datetime(6) not null, deleted_at datetime(6),
    id bigint not null auto_increment,
    issued_at datetime(6) not null, template_id bigint not null,
    updated_at datetime(6) not null, used_at datetime(6),
    used_reservation_id bigint, user_id bigint not null,
    version bigint not null,
    status enum ('AVAILABLE','EXPIRED','USED') not null,
    primary key (id)
) engine=InnoDB

alter table coupon_issues add constraint uk_coupon_issues_used_reservation_id unique (used_reservation_id)
```

→ **`version bigint not null` 컬럼 + `uk_coupon_issues_used_reservation_id` UNIQUE 제약** 두 다층 가드 모두 DDL 에 박제 ✓.

**INSERT — `@Version` 초기 값 0**:
```sql
insert into coupon_issues (created_at,deleted_at,issued_at,status,template_id,updated_at,used_at,used_reservation_id,user_id,version)
                  values (?,?,?,?,?,?,?,?,?,?)
```
→ `version=0` 으로 INSERT (테스트 어설션 `assertThat(issue.version).isZero()` 정합).

**UPDATE × 5 — stale-version WHERE clause 자동 부착**:
```sql
update coupon_issues set deleted_at=?,issued_at=?,status=?,template_id=?,updated_at=?,used_at=?,used_reservation_id=?,user_id=?,version=?
                  where id=? and version=?
```

→ **5 번의 UPDATE 모두 `where id=? and version=?` 형태** — Hibernate `@Version` 어노테이션이 *자동으로* 옛 version 을 WHERE 에 부착. 첫 thread 가 `version=0 → 1` UPDATE 를 commit 하면, 나머지 4 thread 의 `where version=0` 매치는 affected rows = 0 → `OptimisticLockingFailureException`.

**최종 상태 (`select cim1_0.* from coupon_issues where id=?`)**:
- `status=USED`, `usedReservationId=<단일 reservation>`, `version=1` ✓.

### 가설 ↔ 측정 정합

| 가설 (decision.md D-1 #2) | 측정 |
|---|---|
| `@Version Long` UPDATE 시 *Hibernate 가 자동* `WHERE version=?` 부착 | ✅ SQL 로그에서 직접 확인 (`update ... where id=? and version=?` × 5) |
| 첫 commit 만 성공, 나머지는 `OptimisticLockingFailureException` | ✅ 어설션 `successes=1 / conflicts=4 / others=0` (5 thread 중 4 가 CONFLICT) |
| `Long` 단조 증가 — INSERT 시 0, UPDATE 시 +1 | ✅ INSERT version=0 / 첫 UPDATE 후 version=1 / 어설션 `version.isEqualTo(1L)` |
| DB UNIQUE (`used_reservation_id`) defense in depth | ✅ DDL 에 박제, 본 시나리오에서는 `@Version` 이 먼저 차단해 UNIQUE 가 *발동되지 않음* (다층 가드 의도 정합) |

### 채택 결정 — `@Version Long` + `saveAndFlush` 유지

- `CouponIssueModel.@Version var version: Long = 0` 박제.
- `CouponIssueRepositoryImpl.save = jpa.saveAndFlush` — `@Version` 충돌이 *commit 시점이 아니라 호출 시점에* throw 되어야 Facade try/catch 가 잡을 수 있다 (도메인 인터페이스 KDoc 의 의미 계약).
- DB UNIQUE 는 `@Table(uniqueConstraints = ...)` 로 박제 — 같은 reservation 에 다른 쿠폰 사용 시도를 *애플리케이션 우회* 흐름에서도 차단.

### 영구 한계 / 미해결 위험

- **InMemory 더블의 의미론 비재현** — `InMemoryCouponIssueRepository.save` 는 `synchronized` 단순 저장으로 `@Version` 충돌 의미 비재현 (`verify-code R9` 정합). 동시성 회귀는 *Testcontainers MySQL 강제* — KDoc 에 박제 + ConcurrentCouponUseTest 의 KDoc `"InMemory 더블 사용 금지"` 명시.
- **다회 trial / 분포 측정 미진행** — 본 측정은 1 trial. 5주차+ 부하 라운드에서 *N 회 반복 + 분포 측정* 합류 권장 (Phase 0 E-2 / E-3-r1 영구 한계와 동일 패턴).

---

## B-Result-2. Facade 의 raw JPA 예외 → CONFLICT 변환 — 누출 0 검증

### 배경
- `OptimisticLockingFailureException` (Spring DAO 표준 예외) 과 `DataIntegrityViolationException` (UNIQUE 제약 위반) 이 *클라이언트 응답으로 누출* 되면 (a) 식별자 노출 (verify-code §12), (b) 도메인 메시지 부재로 혼란, (c) ApiControllerAdvice 매핑 불일치 (500 응답).
- Facade 가 *모든* JPA 예외 경로를 `CoreException(CONFLICT, "이미 사용된 쿠폰입니다.", cause = e)` 로 변환해야 정합.

### 실측 raw — 어설션 박제

```kotlin
// ConcurrentCouponUseTest.kt:209-221
assertThat(successes.get()).isEqualTo(1)
assertThat(conflicts.get()).isEqualTo(threadCount - 1)  // = 4
assertThat(others.get())
    .withFailMessage("CONFLICT 외 raw 예외 발생: others=%d, sample:\n  %s", others.get(), sample)
    .isZero()
```

테스트 PASS — `others = 0` 즉 raw `OptimisticLockingFailureException` / `DataIntegrityViolationException` 이 *클라이언트 (테스트 워커) 까지 도달한 0건*. Facade 의 try/catch 가 모든 케이스를 흡수.

### 가설 ↔ 측정 정합

| 가설 (week4.md ③ Phase B-2) | 측정 |
|---|---|
| `OptimisticLockingFailureException` → CONFLICT (도메인 메시지 일반화) | ✅ catch 블록 1 |
| `DataIntegrityViolationException` (`used_reservation_id` UNIQUE 위반) → CONFLICT | ✅ catch 블록 2 |
| `cause` 보존 — 운영 로그에서 원인 추적 | ✅ `throw CoreException(..., cause = e)` |
| 메시지에 식별자 (couponId / userId / reservationId) 미노출 | ✅ `"이미 사용된 쿠폰입니다."` 일반화 (verify-code §12) |
| ApiControllerAdvice 가 CONFLICT → 409 매핑 (기존 회귀 가드 활용) | ✅ — 이 매핑 자체는 기존 가드 |

### 채택 결정 — 두 catch 블록 *유지* (의도적 중복)

- `OptimisticLockingFailureException` / `DataIntegrityViolationException` 두 catch 블록이 동일한 throw 를 반복 — verify-code §14 DRY 측면에서는 중복이지만, 두 예외의 *원인이 명확히 다른 도메인 사고* 라 한 catch 로 묶으면 cause 의 분기 정보 (어느 단계에서 실패했는지 — version 충돌 vs UNIQUE 위반) 가 흐려짐. *유지*.

### 영구 한계
- 본 라운드 시나리오 (5 스레드, 같은 쿠폰 / 다른 reservation) 에서는 `@Version` 이 먼저 차단하여 `DataIntegrityViolationException` 경로가 *직접 trigger 안 됨* — UNIQUE 가드의 실제 발동 검증은 *애플리케이션 우회* 시나리오 (다른 쿠폰 ↔ 같은 reservation) 가 합류해야 함. 본 phase scope 외, 5주차+ 통합 라운드.

---

## B-Result-3. 5 스레드 동시 reserve 의 정합성 — TX rollback + reservation 박제

### 배경
- ConcurrentCouponUseTest 의 시나리오는 *5 thread 가 같은 사용자 / 같은 쿠폰 / 서로 다른 일자* 의 reserve 동시 호출. 기대: 1 thread 만 성공해 *그 thread 의 reservation* 만 commit, 나머지 4 thread 는 *coupon use 단계에서 throw* → @Transactional rollback → reservation INSERT / inventory UPDATE 까지 *모두 원복*.

### 실측 raw — Hibernate SQL 로그 (시도 vs 최종 상태)

**시도 단계**:
- `insert into reservations (...)` × **5** (5 thread 모두 reservation 저장 시도)
- `update daily_room_inventories ...` × **10** (5 reservation × 평균 2일자)
- `update coupon_issues ... where id=? and version=?` × **5** (5 thread 모두 쿠폰 사용 시도)

**최종 commit 단계**:
- 1 thread 만 commit — 그 thread 의 reservation INSERT + inventory UPDATE + coupon_issues UPDATE.
- 4 thread 는 coupon 충돌 → @Transactional rollback → 그 thread 의 *모든 변경* (reservation INSERT 4건 + inventory UPDATE 8건) 원복.

→ **6.5s 안에 5 thread 종료** (`done.await(30s)` 타임아웃 대비 충분한 마진), 데드락 / starvation 0.

### 가설 ↔ 측정 정합

| 가설 | 측정 |
|---|---|
| `@Transactional` 단일 진입 — coupon throw 시 reservation / inventory 전체 rollback | ✅ 어설션 `version=1L` (정확히 1번의 UPDATE 만 commit) — 4건의 reservation INSERT 가 *영속화 안 됨* |
| 데드락 / starvation 미발생 | ✅ 6.5s 안에 5 thread 종료 — `done.await(30s)` 충분 마진 |
| 부분 사용 (`partial use`) 0 — 쿠폰이 *어느 reservation 에 사용되었는지* 모호한 상태 미발생 | ✅ `usedReservationId.isNotNull()` + `version=1L` (단 1번의 commit) |

### 채택 결정 — `@Transactional` 단일 진입 + Facade catch 합류 정합

- ReservationFacade.reserve 는 단일 `@Transactional` — 그 안에서 coupon save 의 throw 가 발생하면 *전체 TX rollback*.
- Facade 의 catch 는 *throw* 만 하므로 (cause 보존) TX 가드는 자연 발동 — Spring 의 transactional 의미론 (`@Transactional` 의 default rollback rule = RuntimeException) 정합.

### 영구 한계
- **단일 thread 시나리오 (`ConcurrentReservationTest` 다일자 / 본 ConcurrentCouponUseTest 쿠폰 동시) 의 *교차 합류* 미측정** — 다일자 inventory race + coupon 동시 사용이 *동일 TX 안에 모두* 발생하는 시나리오는 Phase D-1 (다일자 겹침) 합류 시점.

---

## Phase B 의 실제 commit 3건 (git-추적)

| # | hash | prefix | 메시지 | 변경 단위 |
|---|---|---|---|---|
| 1 | `bb63033` | `feat` | `CouponIssueModel 에 @Version 낙관적 락을 추가하고 Repository 의 saveAndFlush 의미를 박제한다.` | `domain/coupon/CouponIssueModel.kt` + `domain/coupon/CouponIssueRepository.kt` (KDoc) + `infrastructure/coupon/CouponIssueRepositoryImpl.kt` (saveAndFlush) + `domain/coupon/CouponIssueModelTest.kt` (version=0 어설션) (4 files, +34/-2) |
| 2 | `1d87e9b` | `feat` | `ReservationFacade 가 OptimisticLockingFailureException / DataIntegrityViolationException 을 CONFLICT 로 변환한다.` | `application/reservation/ReservationFacade.kt` (1 file, +15/-1) |
| 3 | `d1fdb97` | `feat` | `Coupon 동시 사용 E2E 테스트 (같은 쿠폰 × 5 스레드) 를 추가한다.` | `application/coupon/ConcurrentCouponUseTest.kt` (1 file, +296) |

**Plan 정합**: B-1 / B-2 / B-3 가 plan 명시 그대로 분리됨 (Phase A 의 A-2/A-3 합산과 달리 본 phase 는 *서로 다른 파일 / 서로 다른 책임* 이라 단일 commit 합산의 cohesion 가치가 낮음 — 개별 commit 유지가 git history 추적성 ↑).

## 검증 게이트 결과 (2026-05-10)

| 게이트 | 결과 | 발견 / 메모 |
|---|---|---|
| `verify-code` | ✅ PASS | P0 0건 / P1 0건 / P2 2건 (의도적 박제: 두 catch 블록의 의도적 중복 + saveAndFlush round-trip 비용 — 둘 다 KDoc 에 사유 박제) |
| `verify-architecture` | ✅ PASS | 위반 0건 — domain.coupon → application/infrastructure 역참조 0, JPA 예외 catch 가 application Layer 에 한정, `@Transactional` Facade 단일 진입, `@Version` jakarta.persistence 만 사용 (Spring 이종 의존 0) |
| `verify-tests` | ✅ PASS | **397 tests / 0 failures / 0 errors** (BUILD SUCCESSFUL in 1m 22s), ktlintCheck PASS. 신규 ConcurrentCouponUseTest 단일 (E2E) — `@Version` 어설션 1건 추가 (CouponIssueModelTest), Hibernate SQL 로그로 *비즈니스 의미론 (stale-version WHERE clause / TX rollback / cause 보존) 직접 박제* |

## week4-quests Checklist 매핑 (Phase B 시점)

- [x] **Inventory 동시성 ③** (동일 객실 / 동일 일자 동시 예약 시 더블부킹 X) — `ConcurrentReservationTest` PASS (Phase A)
- [x] **Coupon 동시성 ②** (동일 쿠폰 다중 기기 동시 사용) — `ConcurrentCouponUseTest` PASS (본 Phase B)
- [ ] **다일자 겹침 동시성 ④** — Phase D-1 으로 이연 (다일자 락 순서 검증 위해 추가 시나리오 필요)
- [ ] **Wishlist 동시성 ①** (동일 숙소 찜/찜취소 정합성) — Phase C 진입 대기

## 미해결 위험 박제 (Phase B 누적)

1. **`DataIntegrityViolationException` 직접 발동 검증 미합류** — 본 시나리오에서는 `@Version` 이 먼저 차단하여 UNIQUE 위반 경로가 trigger 되지 않음. *애플리케이션 우회* 시나리오 (다른 쿠폰 ↔ 같은 reservation) 의 별도 통합 테스트는 5주차+ 운영 정합 라운드.
2. **다회 trial 분포 측정 미진행** — 본 측정은 1 trial 단일 — Phase 0 E-2 / E-3-r1 의 영구 한계와 동일 패턴. 5주차+ 부하 라운드에서 N 회 반복 측정 합류 권장.
3. **공통 베이스 클래스 추출 미시점** — `ConcurrentReservationTest` (Phase A) + `ConcurrentCouponUseTest` (Phase B) 가 *동일 패턴* (`@SpringBootTest` + `@Import(MySqlTestContainersConfig)` + `CountDownLatch` + `Executors.newFixedThreadPool` + `databaseCleanUp.truncateAllTables()`) 으로 n=2. Phase C 의 `ConcurrentWishToggleTest` (n=3) 가 합류하면 `AbstractConcurrencyE2ETest` 추출 검토 (verify-code §14 DRY).
4. **모바일 30s timeout < `innodb_lock_wait_timeout` 50s** 의 다층 정합 깨짐 — Phase A 박제 그대로 누적 (5주차+ NOWAIT 합류 시점에 해소).
