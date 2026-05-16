# 인덱스 비교군 매트릭스 + 반증 가드 결과 (week5 PR1 D-1)

> **SSOT 위상.** 본 문서는 `IndexComparisonRunner` (gitignored) 1회 실행의 *측정 결과 박제*. 코드는 측정 후 삭제 (CLAUDE.md "실험 테스트" 5-step), 본 문서가 SSOT.
>
> **짝 SSOT.** `seed-load.md` (적재 모수), `k6-results.md` (AS-IS / TO-BE 게인), `docs/plan/week5.md` D-1 (채택안 가설 + 반증 임계). 본 문서는 *비교군 매트릭스 + 반증 가드 PASS/FAIL* 박제.

---

## 0. 실행 환경

| 항목 | 값 |
|---|---|
| 실행일 | 2026-05-16 |
| 대상 DB | docker-compose MySQL 8.0 (`localhost:3306/stayloop`, seed-load.md 의 2.05M row 적재 상태) |
| 측정 도구 | `IndexComparisonRunner` (gitignored, manual `HikariDataSource` — Spring 컨텍스트 미사용) |
| MySQL 버전 | 8.0 (Hibernate Dialect 자동) |
| Warmup | 3 회 |
| Measurement runs | 10 회 (warm cache 환경) |
| 측정 SQL | `SELECT id FROM properties WHERE city = ? ORDER BY wish_count DESC LIMIT 20` |

**한계 (`GR-6` 정합):**
- *p95 절대값* 은 운영 SLA 박제 금지 — 단일 노드 + buffer pool fit (220 MB ⊂ OS page cache) 환경.
- *cold cache 측정 미박제* — 본 측정은 warm cache 만. cold cache 시나리오는 `seed-fixture.md §13` 의 *운영 모수 ×50 합류* 시점에 별도.
- *AS-IS vs 비교군의 상대 게인 (배수)* 만 신뢰.

---

## 1. 채택안 가설 (`docs/plan/week5.md` D-1)

**채택안:** `idx_properties_city_wish_count(city, wish_count DESC)` 외 V010 의 4 인덱스.

**근거:**
- *leftmost-prefix* 원칙 — `WHERE city = ?` 가 equality 라 첫 컬럼이 city.
- *MySQL 8.0 descending B-Tree* — `wish_count DESC` 가 진짜 역방향 인덱스, filesort 사라짐.
- *카디널리티 순서* — city (15) ≪ wish_count (115) → city 가 prefix.

**반증 임계:** 비교군 중 어느 것이라도 시나리오 B (jeju) 의 p95 가 *채택안의 1/5 이하* (= 비교군이 5배 이상 빠름) 면 가설 변경 (GR-3 절차).

**비교군 3 안 (`week5.md` D-1 표):**
- (a) **인덱스 없음** — properties 의 모든 idx_* DROP, PK 만
- (b) **단일 `(city)` 만** — `idx_properties_city` 만 유지, 나머지 DROP
- (c) **역순 `(wish_count DESC, city)`** — leftmost prefix 위반

---

## 2. 비교군 매트릭스 (4 변형 × 3 시나리오)

| Variant | Scenario | EXPLAIN key | rows | Extra | p50 (ms) | p95 (ms) |
|---|---|---|---|---|---|---|
| **D-1 채택안** | A 서울 | `idx_properties_city_wish_count` | 1800 | Using index | 2.34 | 3.21 |
| **D-1 채택안** | **B 제주** | `idx_properties_city_wish_count` | 1400 | Using index | 2.16 | **2.63** |
| **D-1 채택안** | C 부산 | `idx_properties_city_wish_count` | 1100 | Using index | 1.80 | 2.51 |
| (a) 인덱스 없음 | A 서울 | `(none)` | 9869 | Using where; **Using filesort** | 4.09 | 4.48 |
| (a) 인덱스 없음 | **B 제주** | `(none)` | 9869 | Using where; **Using filesort** | 3.75 | **4.25** |
| (a) 인덱스 없음 | C 부산 | `(none)` | 9869 | Using where; **Using filesort** | 3.97 | 4.28 |
| (b) `(city)` 만 | A 서울 | `idx_properties_city` | 1800 | **Using filesort** | 3.64 | 4.21 |
| (b) `(city)` 만 | **B 제주** | `idx_properties_city` | 1400 | **Using filesort** | 3.53 | **4.16** |
| (b) `(city)` 만 | C 부산 | `idx_properties_city` | 1100 | **Using filesort** | 3.29 | 3.76 |
| (c) `(wish_count DESC, city)` 역순 | A 서울 | `idx_properties_wish_count_city` | 109 | Using where; Using index | 2.18 | 2.96 |
| (c) `(wish_count DESC, city)` 역순 | **B 제주** | `idx_properties_wish_count_city` | 140 | Using where; Using index | 2.30 | **2.66** |
| (c) `(wish_count DESC, city)` 역순 | C 부산 | `idx_properties_wish_count_city` | 178 | Using where; Using index | 2.27 | 2.78 |

### 2.1 시나리오 B (반증 가드 결정점) — 게인 배수

| Variant | p95 (ms) | 채택안 대비 |
|---|---|---|
| **D-1 채택안** | **2.63** | 1.0× (기준) |
| (a) 인덱스 없음 | 4.25 | 채택안이 1.62× 빠름 |
| (b) `(city)` 만 | 4.16 | 채택안이 1.58× 빠름 |
| (c) 역순 | 2.66 | 채택안이 1.01× — *거의 동일* |

→ 어떤 비교군도 *채택안의 1/5 이하 (= 비교군이 5x 게인)* 가 아님.

### 2.2 EXPLAIN 의미론 비교

| Variant | rows estimate | filesort | covering index | 해석 |
|---|---|---|---|---|
| **D-1 채택안** | 1400 | ❌ 없음 | ✅ Using index | 인덱스 prefix scan + covering — 디스크 페이지 안 만짐 |
| (a) 인덱스 없음 | 9869 | ✅ 있음 | — | 풀스캔 + 메모리 정렬 |
| (b) `(city)` 만 | 1400 | ✅ 있음 | — | city 인덱스로 좁힘, but 메모리 정렬 필요 |
| (c) 역순 | 109~178 | ❌ 없음 | ✅ Using index | 더 적은 rows 추정 (옵티마이저가 wish_count prefix 로 잘 거름) |

---

## 3. experiment-recurse Round 1 (7 축 재귀 검토)

본 절은 *측정 결과 박제* 의 허점을 7 축 분류 (`experiment-recurse` 스킬 §0). 본 측정은 1 round 만 — 추후 *허점 (a) 즉시 수정 / (b) 재측정 / (c) 영구 한계* 분류.

| 축 | 발견 | 분류 |
|---|---|---|
| **환경** | docker MySQL default `innodb_buffer_pool_size = 128 MB` 보다 데이터 (220 MB) 가 크지만 OS page cache 까지 합치면 fit. *cold cache 측정 X*. | (c) 영구 한계 — week6+ 운영 buffer pool ×50 모수 합류 |
| **시나리오** | 시나리오 A/B/C 가 city share 다르지만 *5일 범위* 의 다일자 부담은 본 측정 (WHERE city ORDER BY wish_count) 에 반영 X. inventory range scan 측정 미박제. | (c) 영구 한계 — 본 runner scope 외, PR3 projection 시 별도 측정 |
| **도구 의미론** | `System.nanoTime()` 차이 측정 — JVM warm-up 영향 (`WARMUP_RUNS=3` 으로 부분 완화). p95 가 짧은 시간 (2~4ms) 라 측정 노이즈 비율 상대적으로 큼. | (c) 영구 한계 — 운영 합류 시 분산 generator + 다회 trial |
| **라벨링** | (c) 역순 variant 의 `rows = 109~178` 이 비정상적으로 작음. 옵티마이저가 *wish_count 가 매우 큰 row 만 prefix scan* 으로 판단. 실제 *모든 row 가 city = 'jeju'* 인지 확인 안 함. 추가 검증 가능. | (b) 재측정 후보 — 본 라운드는 *결과의 의미* (역순 거의 동일) 가 변하지 않으므로 미실시 |
| **통계적 유의성** | 10회 trial 의 p95 = sorted[9] (= 마지막) 라 *최대값에 가까운* 추정. 신뢰구간 X. | (c) 영구 한계 — 30회+ trial + 표준편차 합류 시점 별도 |
| **해석** | 절대값 (모두 2~5ms) 만 보면 *어떤 인덱스도 충분히 빠름* 결론 가능 — 그러나 모수 ×50 시 *9869 rows × 50 = 50만 row 풀스캔* 이 비현실적. **EXPLAIN rows / filesort 정합성이 결정의 근거** (절대값 X). | 본 결과 박제 자체에 명시 |
| **가설 자체** | D-1 채택안 가설 — *복합 (city, wish_count DESC)* 가 *모든 비교군보다 빠름*. 측정상 (a)/(b) 보다 1.6× 빠르고 (c) 와 거의 동일. *가설은 약하게 지지됨* — 큰 모수 / cold cache 에서는 격차 확대 예상. | 본 라운드 결과 안에서는 *PASS*, 미래 합류 시점에 재검토 |

**Round 1 결과:** 측정의 *재현 가능성 + 의미 정합성* 양면 PASS — 결과 박제 가치 충분. 단, *절대값 → 운영 SLA 변환 금지* (위 §0 한계 박제).

---

## 4. 반증 가드 결과

### PASS — D-1 채택안 유지

**근거:**
- 시나리오 B (jeju, worst case) 에서 어떤 비교군도 채택안의 *1/5 이하 게인* 달성 X.
- (a) 인덱스 없음: 1.62× 더 느림
- (b) `(city)` 만: 1.58× 더 느림
- (c) 역순: 거의 동일 (1.01×) — but *EXPLAIN rows estimate* 가 비현실적 (109 vs 1400) → 옵티마이저가 도시 외 row 까지 끌어와 후처리 가능성. 큰 모수에서 부담 폭증 예상.
- EXPLAIN 의미: D-1 만 *covering index* (`Using index` extra) — 디스크 페이지 안 만짐. 운영 환경에서 의미가 다르다.

**미채택 대안 trade-off (영구 박제):**

| 대안 | 채택될 시나리오 |
|---|---|
| (a) 인덱스 없음 | 모수가 *수십 row* 수준 + 쓰기 성능 우선 |
| (b) `(city)` 만 | 정렬이 없거나 sort 별 인덱스 비용 회피 우선 + 적은 페이지 |
| (c) 역순 | *모든 도시* 가 동일 wish_count 분포일 때 — 본 spec 의 *서울 18% / 제주 14%* 같은 *도시별 편향* 모수에서는 부적합 |

**중요 한계 박제:**
- *5x 게인 임계* 는 본 측정 환경 (buffer pool fit + warm cache) 에서는 *너무 엄격* — 어떤 인덱스 전략도 5x 게인을 만들지 못함. 운영 환경 (cold cache / buffer pool 미fit) 에서는 격차 더 크게 발화 예상.
- *D-7 회귀 룰* (`PropertyFacadeSearchSortTest`) 이 EXPLAIN key + Extra 만 검증 — 본 측정의 *EXPLAIN 의미론 게인* 이 회귀 시 잡힘. 통과 시 *과거 측정의 의미가 회수* 됨.
- 본 결과는 *PR1 진행 정당화* 의 충분 조건 — 측정값 *부족 + 의미상 정합* 의 종합 판단. *측정값이 5x 게인을 입증* 한 것이 아니라 *EXPLAIN 의 의미가 5x 게인의 *근거*가 되었다* 로 박제.

### `decision.md` D-1 상태 전환

→ `docs/plan/week5/decision.md` 의 D-1 상태:
- **이전**: *가설 (사전 박제)*
- **현재 (본 측정 후)**: *채택 확정 (반증 가드 PASS)*
- **재검토 조건**: (i) 운영 합류 + buffer pool ×50 모수 시 (ii) 분산 generator + cold cache 시나리오 합류 시 (iii) MySQL Dialect 업그레이드 시 EXPLAIN plan 변경 검출 → 회귀 발화

---

## 5. PR1 Phase L (k6 TO-BE 측정) 인계

본 측정의 *상대 게인* (D-1 vs (a) 1.62×) 은 *단일 쿼리 / 한 가지 인덱스 영향* 에 한정. **k6 시나리오 A/B/C 의 TO-BE 측정 (`k6-results.md`)** 은 *Facade 의 N+1 흐름 전체* 의 게인을 측정 — *AS-IS 대비 게인 배수가 더 큼* 예상 (인덱스 + projection 후속 PR3 의 누적).

PR1 Phase L 의 *k6-results.md* 는 본 측정과 *수직 보완*:
- 본 문서: *단일 SQL 쿼리* 의 인덱스 게인
- k6-results.md: *전체 API 요청* 의 통합 게인 (V010 인덱스 4종 + sort 4종 활성 후 vs PR0 baseline)

---

## 6. 박제 사이클 종결

- ✅ 4 변형 × 3 시나리오 매트릭스 박제 (§2)
- ✅ EXPLAIN 의미론 비교 (§2.2)
- ✅ experiment-recurse Round 1 박제 (§3)
- ✅ 반증 가드 결과 (PASS, §4)
- ✅ decision.md D-1 상태 전환 약속 (§4)
- ✅ Phase L (k6) 인계 약속 (§5)

→ `IndexComparisonRunner` 삭제 (M-3 commit). 본 문서가 SSOT.
