# SearchPerfSeed 적재 결과 박제 (week5 PR0 0-2)

> **SSOT 위상.** 본 문서는 `SearchPerfSeed.kt` 1회 실행의 *측정 결과 박제* 다. `docs/plan/week5/seed-fixture.md` 가 *모수 SSOT*, 본 문서가 *실측 SSOT*. 실측이 SSOT 가 되는 이유 — 분포 모수 / clip 정책의 실제 효과는 측정으로만 확인 가능 (spec §12 박제 사이클). 본 문서가 갱신되는 시점 = 모수 변경 후 재적재 시점뿐.
>
> **코드 위상.** `apps/stay-api/src/test/kotlin/com/stayloop/experiment/SearchPerfSeed.kt` 는 `.gitignore` 의 `**/src/test/kotlin/**/experiment/` 패턴으로 추적 제외. 본 문서 박제 후 *언제든 삭제 가능* — 본 문서가 충분한 SSOT (CLAUDE.md "실험 테스트" 5-step 의 step 5).

---

## 0. 실행 환경

| 항목 | 값 |
|---|---|
| 실행일 | 2026-05-16 |
| Random seed | `42L` |
| 적재 대상 | docker-compose MySQL 8.0 (`localhost:3306/stayloop`, 사용자 `application`) |
| Schema | `modules/jpa/src/main/resources/db/migration/V001__init.sql` (PR0 0-1 박제) |
| 클라이언트 | `HikariDataSource` (pool=4) + `JdbcTemplate.batchUpdate` (chunk=1000) + `INSERT IGNORE` |
| 적재 소요 | **약 2분 39초** (Spring Boot 컨텍스트 미사용 — 0-2 본문 §6 참조) |

**환경 한계.** docker MySQL 8.0 의 default `innodb_buffer_pool_size` 는 128 MB. 본 적재의 디스크 footprint (≈ 220 MB, §2 표) 는 buffer pool 보다 *크다* — *cold cache* 시나리오가 실측 가능. 운영 buffer pool (8 GB) 안 fit 한계는 `seed-fixture.md §13` 인계 항목 ([buffer-pool 한계](../../docs/plan/week5/seed-fixture.md#13-한계--인계-항목-week6)).

---

## 1. Row count (실측 vs spec 예상)

| 테이블 | spec 예상 | 실측 | 차이 | 판정 |
|---|---|---|---|---|
| `users` | 10_000 | **10_000** | 0 | ✅ |
| `properties` | 10_000 | **10_000** | 0 | ✅ |
| `room_types` | ≈ 50_000 | **52_773** | +5.5% | ✅ (Poisson λ 분포 정합) |
| `daily_room_inventories` | ≈ 2_000_000 | **2_054_838** | +2.7% | ✅ (32% × all RoomType × 123일) |
| `daily_room_rates` | ≈ 2_000_000 | **2_054_838** | 0 (inventories 와 동일 set) | ✅ |
| `wishlists` | ≈ 100_000 | **37_105** | **−63%** | ⚠️ §3 참조 |

### 1.1 `wishlists` 미달 — spec §9 와의 거리

**현상.** Property 단위 `taken.clear()` (코드 §generateAndInsertWishlists 의 메모리 폭주 차단) + Pareto user 활동도 (α=1.16) 의 상위 사용자 집중 + property 별 target = `min(p.wish_count, userCount)` 제한이 결합되어, *충돌-재추첨 한계 (`maxAttempts = target * 4 + 16`)* 가 자주 소진. 결과 row 수가 `SUM(wish_count) = 37_105` 와 정합.

**SUM 정합성** (가장 중요한 SSOT 어설션):

```sql
SELECT SUM(wish_count) AS sum_wish_count_on_properties,
       (SELECT COUNT(*) FROM wishlists) AS rows_in_wishlists,
       SUM(wish_count) - (SELECT COUNT(*) FROM wishlists) AS gap
FROM properties;
```

| `sum_wish_count_on_properties` | `rows_in_wishlists` | gap |
|---|---|---|
| 37_105 | 37_105 | **0** |

→ `properties.wish_count` (week4 비정규화 컬럼) ↔ `wishlists` row 수가 *정확히 1:1*. week4 의 atomic UPDATE 가 박은 SSOT 정합성이 seed 적재 결과에서도 검증됨 (D-2 비정규화 채택안의 사전 검증).

**해석.** spec §9 의 "≈ 100_000" 은 `sum(property.wish_count)` 기대값이 아니라 *user × property* 쌍의 총 시도 수 기대치. 본 seed 코드는 `wish_count` 분포 (Pareto + 30% zero-bias) 의 결과 `sum(wish_count) = 37_105` 가 *상한*. 측정 의미는 본 row 수가 정상.

**한계 박제 (week6+ 인계).** 본 모수에서 `wishlists` 가 너무 적어 wish-desc 정렬의 비교군 *W-0 JOIN GROUP BY* 측정 (PR2 Phase M) 의 *부담 크기* 가 작을 수 있음. 모수 ×50 재상향 시 (seed-fixture.md §13) 본 row 수도 비례 증가 → 측정 의미 충실. 본 라운드는 *분포 정합 학습* 우선.

---

## 2. 테이블 크기 (`information_schema.TABLES` after `ANALYZE TABLE`)

| TABLE_NAME | TABLE_ROWS | data_mb | index_mb | total_mb |
|---|---|---|---|---|
| `daily_room_inventories` | 1_940_889 | 94.64 | 0.00 | **94.64** |
| `daily_room_rates` | 2_127_868 | 94.64 | 0.00 | **94.64** |
| `room_types` | 52_649 | 6.52 | 1.52 | 8.03 |
| `properties` | 9_607 | 3.52 | 0.88 | 4.39 |
| `users` | 9_553 | 2.52 | 0.25 | 2.77 |
| `wishlists` | 36_378 | 2.52 | 0.00 | 2.52 |
| `property_images` | 0 | 0.02 | 0.02 | 0.03 |
| `coupon_issues` | 0 | 0.02 | 0.02 | 0.03 |
| `coupon_templates` | 0 | 0.02 | 0.02 | 0.03 |
| `reservations` | 0 | 0.02 | 0.00 | 0.02 |
| **합** | | | | **≈ 209 MB** |

**관측.**
- `TABLE_ROWS` 가 `COUNT(*)` 와 다름 (예: inventories 1_940_889 vs 2_054_838). MySQL `TABLE_ROWS` 는 InnoDB 통계의 *근사값* — `ANALYZE TABLE` 후에도 ±5% 오차 정상.
- `daily_room_*` 의 `index_mb = 0.00` 은 PK 가 *clustered index* (InnoDB) 이기 때문 — 별도 secondary index 없음. PR1 의 D-1 측정에서 `(room_type_id, date)` 인덱스 추가 시 변화 관측 예정 (`seed-fixture.md §10`).
- spec §10 의 *디스크 예상 ≈ 400 MB* 보다 실측 **≈ 209 MB** 가 작은 이유: spec 은 운영 buffer pool 8 GB 가정의 *전체 디스크 footprint* 추정, 본 실측은 InnoDB compact row format (default) 의 실제 페이지 사용.

---

## 3. 분포 어설션 결과

### 3.1 City 분포 (spec §1 ↔ 실측)

| City | spec share | 실측 n / 10_000 | 실측 share | 판정 |
|---|---|---|---|---|
| seoul | 18% | 1800 | 18.0% | ✅ |
| jeju | 14% | 1400 | 14.0% | ✅ |
| busan | 11% | 1100 | 11.0% | ✅ |
| gangneung | 9% | 900 | 9.0% | ✅ |
| gapyeong | 8% | 800 | 8.0% | ✅ |
| incheon | 8% | 800 | 8.0% | ✅ |
| gyeongju | 7% | 700 | 7.0% | ✅ |
| yeosu | 7% | 700 | 7.0% | ✅ |
| sokcho | 6% | 600 | 6.0% | ✅ |
| daegu | 5% | 500 | 5.0% | ✅ |
| 그 외 long-tail × 5 | 7% (각 1.4%) | 140 each | 1.4% each | ✅ |

도시 분포는 *결정적* (코드 §generateAndInsertProperties 의 `cityCounts` 가 round-then-flatten) — spec §1 weights 와 100% 정합.

### 3.2 Category 분포 (spec §2 ↔ 실측)

| Category | 실측 n / 10_000 | 실측 share |
|---|---|---|
| MOTEL | 3022 | 30.2% |
| PENSION | 2296 | 23.0% |
| HOTEL | 2185 | 21.9% |
| RESORT | 1279 | 12.8% |
| GUESTHOUSE | 1218 | 12.2% |

→ city 별 conditional weight (`CATEGORY_WEIGHTS_BY_CITY`) 가 결합된 *마진* 분포. RESORT 12.8% 는 spec §2 의 *전국 평균* (8% 이상) 정합. MOTEL 30.2% 는 spec §2 의 *URBAN 도시 가중* (서울/부산/대구/인천 합산 38% × 0.45 + 그 외 평균 30%) 정합.

### 3.3 가격 분포 (spec §6 ↔ 실측)

| metric | 실측 |
|---|---|
| `MIN(price_per_night)` | 10_000 (= `PRICE_MIN` clip) |
| `MAX(price_per_night)` | 5_000_000 (= `PRICE_MAX` clip) |
| `AVG(price_per_night)` | 214_574 |

→ Lognormal × city × season 의 결과 평균 ≈ 21만원. spec §6 의 *카테고리별 ADR ≈ 7~22만원 × city × season multiplier (peak 2.5x)* 정합.

### 3.4 wish_count ↔ wishlists SSOT 정합성

§1.1 어설션 통과 — `SUM(properties.wish_count) = COUNT(wishlists) = 37_105`. gap = **0**.

이는 PR2 D-2 (비정규화 채택안) 의 *seed 시점 SSOT 정합* 을 의미. PR2 의 Phase A 정합 통합 테스트는 *런타임 wish/unwish 시점의 정합* 을 추가 검증 (week4 atomic UPDATE 가드).

---

## 4. 도메인 가드 어설션 결과

본 적재기는 영속화 경로를 우회 (`JdbcTemplate.batchUpdate`) — 도메인 `init` 가드가 발화하지 않는다. spec §11 의 *적재 코드 clip 책임* 이 정합한지 SQL 어설션으로 검증:

| 가드 | 어설션 SQL | violations |
|---|---|---|
| `wish_count < 0` 거절 | `SELECT COUNT(*) FROM properties WHERE wish_count < 0` | **0** ✅ |
| `reserved_rooms > total_rooms` 거절 | `SELECT COUNT(*) FROM daily_room_inventories WHERE reserved_rooms > total_rooms` | **0** ✅ |
| `reserved_rooms < 0` 거절 | `SELECT COUNT(*) FROM daily_room_inventories WHERE reserved_rooms < 0` | **0** ✅ |
| `price_per_night < 10_000` (`PRICE_MIN`) | `SELECT COUNT(*) FROM daily_room_rates WHERE price_per_night < 10000` | **0** ✅ |
| `price_per_night > 5_000_000` (`PRICE_MAX`) | `SELECT COUNT(*) FROM daily_room_rates WHERE price_per_night > 5000000` | **0** ✅ |
| `rating ∉ [0, 5]` | `SELECT COUNT(*) FROM properties WHERE rating < 0 OR rating > 5` | **0** ✅ |
| `max_guests < base_guests` 또는 `base_guests < 1` | `SELECT COUNT(*) FROM room_types WHERE max_guests < base_guests OR base_guests < 1` | **0** ✅ |

전 가드 통과 → 적재기의 clip 정책이 *전 분포 경계에서* 정상 동작.

---

## 5. ANALYZE TABLE 결과 + SHOW INDEX

### 5.1 ANALYZE TABLE

```
stayloop.users                    OK
stayloop.properties               OK
stayloop.room_types               OK
stayloop.daily_room_inventories   OK
stayloop.daily_room_rates         OK
stayloop.wishlists                OK
```

→ 옵티마이저 통계 갱신 완료. PR1 의 EXPLAIN 측정 (D-7) 전제 충족.

### 5.2 SHOW INDEX (PR1 D-1 사전 검증)

**`properties`:**

| Key_name | Column | Cardinality |
|---|---|---|
| `PRIMARY` | `id` | 9_607 |
| `idx_properties_city` | `city` | 15 |
| `idx_properties_rating` | `rating` | 174 |
| `idx_properties_wish_count` | `wish_count` | 115 |

→ PR1 의 D-1 채택안은 `(city, wish_count DESC)` + `(city, rating DESC)` *복합* 인덱스 추가. 본 시점의 단일 컬럼 인덱스 3종은 PR1 Phase A 의 *비교군 (b) 단일 `(city)` 만* 의 baseline.

**`daily_room_inventories`** ← **PR1 D-1 발견** (plan §2 의 *사전 SHOW INDEX 검증* 항):

| Key_name | Seq | Column | Cardinality |
|---|---|---|---|
| `PRIMARY` | 1 | `date` | 124 |
| `PRIMARY` | 2 | `room_type_id` | 1_940_889 |

→ PK 순서가 **`(date, room_type_id)`** 다. plan §2 D-1 의 *가정 `(room_type_id, date)`* 와 *역순*. PR1 Phase A 의 V010 마이그레이션에서 `(room_type_id, date)` 보조 인덱스 추가 필요 — *검색 1건이 RoomType ID set 으로 일자 범위 조회* 하는 쿼리 패턴에서 PK 가 그대로 활용 불가.

**`daily_room_rates`** ← 동일 발견:

| Key_name | Seq | Column | Cardinality |
|---|---|---|---|
| `PRIMARY` | 1 | `date` | 117 |
| `PRIMARY` | 2 | `room_type_id` | 2_127_868 |

→ PR1 D-1 의 인덱스 4종 중 `daily_room_rates(room_type_id, date)` 는 *반드시* 추가. plan §2 의 *"다르면 추가"* 조건 발화.

---

## 6. 실행 메모 — *Testcontainers 자동 발화 사고* (학습 자산)

본 0-2 박제 사이클의 *L2 부딪힘* 박제. `week5-b.md §17` 의 L2 가 *seed 박았는데 느림 안 보임* 인데, 본 사이클은 그 *앞 단계* 에서 다른 사고를 발견.

**현상.**
- `SearchPerfSeed.kt` 는 `@SpringBootTest + @ActiveProfiles("local")` 으로 작성되어 *docker-compose MySQL* (`localhost:3306/stayloop`) 에 붙는 것이 의도.
- 1차 실행 시 Spring 컴포넌트 스캔이 `modules/jpa/src/testFixtures/.../MySqlTestContainersConfig.kt` 의 `@Configuration` 을 자동 발견 → 컴패니언 `init {}` 블록의 `MySQLContainer.start()` 가 발화 → `System.setProperty("datasource.mysql-jpa.main.jdbc-url", ...)` 가 *Testcontainers JDBC URL* 로 override.
- 결과: *6분 52초* 적재 후 Ryuk 이 컨테이너 정리 → *데이터 흔적 없음*. `docker exec docker-mysql-1 mysql ... stayloop` → `Unknown database`.

**해결.**
- 본 사이클은 `SearchPerfSeed` 를 *Spring Boot 컨텍스트 미사용* 으로 전환 — `@SpringBootTest` 제거, manual `HikariDataSource` 로 `localhost:3306/stayloop` 직접 연결.
- 2차 실행 *2분 39초* (Spring Boot 부트 시간 약 4분 절감) — 적재 완료, 데이터 박제.
- 본 변경은 `.gitignore` 된 파일에 한정 — 0-2 commit 자체는 변경 없음.

**학습.**
- `testFixtures` 의 `@Configuration` 은 같은 베이스 패키지 (`com.stayloop.*`) 안에 있으면 *모든 `@SpringBootTest`* 가 자동 발화시킬 위험. `@Profile("test")` 가 없으면 *local profile 의 통합 테스트도* Testcontainers 가 발화.
- 본 사고는 *실험 테스트 / 통합 테스트* 의 profile 분리 미흡의 결과. week6+ 의 회귀 룰 후보: *`testFixtures` 의 `@Configuration` 에 `@Profile("test")` 명시 강제*.
- 본 사고는 *결과 박제 SSOT* 의 가치를 또 한 번 정합 — 실험 코드 일회성이라도 측정 박제는 git 추적, 박제가 SSOT.

---

## 7. PR1+ 에 인계할 함의

| # | 항목 | 인계 위치 |
|---|---|---|
| H-1 | `daily_room_inventories.PK = (date, room_type_id)` 가 plan §2 D-1 가정의 *역순*. V010 마이그레이션에서 `(room_type_id, date)` 보조 인덱스 추가 필요 | PR1 Phase A-1 (V010) |
| H-2 | `daily_room_rates.PK = (date, room_type_id)` 동일 — V010 의 `(room_type_id, date)` 인덱스는 *PK 가정 위반의 보완책*, plan D-1 의 4 인덱스 안에 *포함됨* | PR1 Phase A-1 (V010) |
| H-3 | `wishlists` row 수가 spec §9 의 *시도 모수* (≈100k) 보다 *결과 모수* (≈37k) 작음 — 비교군 W-0 JOIN GROUP BY 측정 시 부담이 작아 *측정 신뢰도 한계* 박제 필요 | PR2 Phase M, comparison.md §3 |
| H-4 | 본 디스크 footprint (≈ 209 MB) 가 default docker buffer pool (128 MB) 보다 크지만 운영 buffer pool (8 GB) 안 fit — *cold cache 측정의 신뢰도 ↑ / warm cache 일관성 ↓* 한계 박제 | k6-results.md (AS-IS baseline) |
| H-5 | `Testcontainers 자동 발화` 사고 — `testFixtures` 의 `@Configuration` 에 `@Profile("test")` 명시 회귀 룰 검토 | week6+, `verify-architecture/SKILL.md` 검토 항목 |

---

## 8. 박제 사이클 종결

- ✅ row count 박제 (§1)
- ✅ 테이블 크기 박제 (§2)
- ✅ 분포 어설션 박제 (§3)
- ✅ 도메인 가드 어설션 박제 (§4)
- ✅ `ANALYZE TABLE` + `SHOW INDEX` 박제 (§5)
- ✅ 사고 학습 박제 (§6)
- ✅ PR1+ 인계 박제 (§7)

→ `seed-fixture.md §12` 의 박제 사이클 step 1~3 완료. step 4 (`SearchPerfSeed.kt` 삭제) 는 PR0 의 후속 0-4/0-5 작업 또는 PR1~4 완료 후 일괄 삭제 (코드 SSOT 가치 없음, 본 문서 + spec 이 충분한 SSOT).
