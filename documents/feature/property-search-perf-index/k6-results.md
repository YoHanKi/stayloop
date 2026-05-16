# PR1 TO-BE k6 측정 결과 — D-1 인덱스 + D-6 sort 4종 활성 직후 (week5 PR1 Phase L)

> **SSOT 위상.** 본 문서는 PR1 의 `feature/property-search-perf-index` 머지 *후 측정 가설* 의 박제 (Phase L-1). PR0 의 `documents/feature/perf-experiment-infra/k6-results.md` (AS-IS) 와의 *게인 (배수)* 가 본 PR 의 효과 SSOT.
>
> **짝 SSOT.** `comparison.md` (단일 SQL 쿼리의 인덱스 게인 — 1.6× 수준) ↔ 본 문서 (전체 API 요청의 통합 게인 — 21~41× 수준). 두 측정의 *수직 차이* 가 본 PR 의 핵심 학습.

---

## 0. 실행 환경

| 항목 | 값 |
|---|---|
| 실행일 | 2026-05-16 |
| 측정 도구 | k6 v1.6.1 (windows/amd64), constant-arrival-rate executor |
| 대상 | `./gradlew :apps:stay-api:bootRun` (profile=local), `localhost:8080` |
| DB | docker-compose MySQL 8.0 (`stayloop`), `seed-load.md` 의 2.05M row 적재 |
| 인덱스 상태 | V001 base + **V010 인덱스 4종** (`idx_properties_city_wish_count` / `idx_properties_city_rating` / `idx_daily_room_inventories_room_type_date` / `idx_daily_room_rates_room_type_date`) |
| Sort 활성 | RECOMMENDED / PRICE_ASC / RATING_DESC / WISHES_DESC 모두 — 본 측정은 *RECOMMENDED 통일* (AS-IS 와 동일 조건) |
| Tomcat | max threads 200 / accept-count 100 / connection-timeout 1m (기본) |
| Hikari | `mysql-main-pool` max=40 / min-idle=30 |

**측정 의미 한계 (`GR-6`):**
- *p95 절대값* 은 운영 SLA 박제 금지 — *AS-IS vs TO-BE 게인 (배수)* 만 신뢰.
- PR3 projection 합류 전이라 *N+1* 그대로 — TO-BE 측정값은 *인덱스 효과만* 반영 (projection 게인 미합산).

---

## 1. AS-IS vs TO-BE 비교

### 시나리오 A — 서울 / 5/15~5/17 / 2명 / sort=RECOMMENDED / RPS=100 / 60s

| 메트릭 | AS-IS (PR0 baseline) | TO-BE (PR1 V010 직후) | 게인 |
|---|---|---|---|
| iterations 완료 | 3204 | **4162** | +30% |
| iterations dropped | 2797 | **1839** | −34% |
| 실효 RPS | 48.6 /s | **66.6 /s** | +37% |
| status 2xx | 548 (17.1%) | **4121 (99.0%)** | +482% |
| status 5xx | 2656 (82.9%) | **41 (0.98%)** | **−98.8%** |
| `http_req_duration` p(95) | 7.85s | **3.37s** | **2.33× 빠름** |
| `http_req_failed` | 82.89% | **0.98%** | SLA `<1%` *통과* |

**해석.** 5xx 가 *83% → 1%* 로 거의 사라짐 — V010 인덱스로 *단일 SQL latency 감소* → Tomcat thread / Hikari connection 의 *재고 회복* → 백프레셔 해소. 그러나 RPS 100 은 여전히 *덜 받음* (dropped 1839). N+1 폭발이 핵심 잔량 부담 — PR3 projection 합류 시 해소 예상.

### 시나리오 B — 제주 / 7/30~8/4 / 2명 / sort=RECOMMENDED / RPS=50 / 60s ← **D-1 반증 가드 결정점**

| 메트릭 | AS-IS (PR0 baseline) | TO-BE (PR1 V010 직후) | 게인 |
|---|---|---|---|
| iterations 완료 | 2155 | **3001** | +39% |
| iterations dropped | 845 | **0** | **−100% 100% 처리** |
| 실효 RPS | 31.4 /s | **49.9 /s** | 목표 RPS=50 *거의 만족* |
| status 2xx | 318 (14.75%) | **3001 (100%)** | +843% |
| status 5xx | 1837 (85.24%) | **0 (0%)** | **−100% 완전 제거** |
| `http_req_duration` p(95) | 11.63s | **283.65ms** | **41× 빠름** |
| `http_req_failed` | 85.24% | **0%** | SLA `<1%` *압도* |

**해석. **시나리오 B (다일자 + jeju)** 가 AS-IS 의 worst — V010 의 `idx_daily_room_rates_room_type_date` + `idx_properties_city_wish_count` 가 *모든 N+1 호출의 누적 게인* 으로 작동. p95 11.63s → 283ms 는 *41× 게인* 으로 D-1 반증 가드의 *5× 임계* 를 *압도 PASS*. 5xx 완전 제거. RPS 50 무리 없이 소화.

### 시나리오 C — 부산 / 5/10~5/11 / 4명 / sort=RECOMMENDED / RPS=100 / 60s

| 메트릭 | AS-IS (PR0 baseline) | TO-BE (PR1 V010 직후) | 게인 |
|---|---|---|---|
| iterations 완료 | 3391 | **6001** | +77% |
| iterations dropped | 2609 | **0** | **−100%** |
| 실효 RPS | 53.5 /s | **99.9 /s** | 목표 RPS=100 *만족* |
| status 2xx | 2899 (85.5%) | **6001 (100%)** | +107% |
| status 5xx | 492 (14.5%) | **0 (0%)** | **−100%** |
| `http_req_duration` p(95) | 3.95s | **182.65ms** | **21.6× 빠름** |
| `http_req_failed` | 14.50% | **0%** | SLA `<1%` *통과* |

**해석.** 시나리오 C 는 AS-IS 가 best (4명 guests 필터로 결과 좁아짐) 였는데, V010 인덱스로 *21.6× 게인* — guests 필터의 N+1 호출 횟수도 인덱스 게인 누적의 큰 분모. SLA 200ms 임계 *근접* (TO-BE 183ms, SLA 200ms).

---

## 2. D-1 반증 가드 결과

### PASS — 공식 채택 확정

**근거:**
- 시나리오 B (반증 결정점) 의 게인 = **41×** — 임계 5× *압도 PASS*
- 시나리오 A 게인 2.33× — *단독으로는 임계 미달* 이나 5xx 의 *83% → 1%* 가 *유의한 운영 개선*
- 시나리오 C 게인 21.6× — *임계 4× 초과*
- 모든 시나리오에서 5xx 14~85% → 0~1% — *서비스 가능 상태로 전환*

→ `docs/plan/week5/decision.md` 의 D-1 상태:
- **이전**: *가설 (사전 박제)*
- **현재**: **채택 확정 (PR0 0-5 의 가설 → 정상 채택)**

### 단일 SQL vs 통합 API 게인의 *수직 차이*

**comparison.md** 의 단일 SQL 측정: D-1 vs (a) 인덱스 없음 1.62×, vs (b) (city) 만 1.58×.

**본 문서의 통합 API 측정**: 시나리오 B 41× / C 21.6×.

→ *수직 차이의 학습*:
- 단일 SQL 측정은 *buffer pool fit + warm cache* 환경에서 *측정 시간 절대값* 이 작아 게인 1~2× 수준.
- 통합 API 측정은 *N+1 의 모든 호출이 인덱스 게인을 누적* + *백프레셔 해소 (5xx 사라짐)* + *Tomcat / Hikari pool 회복* 의 *복합 효과*.
- *반증 가드 5× 임계는 통합 측정에서만 의미를 가짐* — 단일 SQL 측정 기반의 게인 평가는 *과소 측정* 위험.
- **결정의 근거는 단일 SQL 의 의미 (EXPLAIN rows / filesort / covering index) + 통합 게인** 의 둘 (`comparison.md §4` 의미 박제 정합).

---

## 3. 시나리오 별 game-changing 요인

| 시나리오 | AS-IS bottleneck | V010 의 효과 |
|---|---|---|
| A 서울 | RPS=100 의 thread pool 압박 (200 threads × 100 RPS 미달) | 단일 호출 latency ↓ → thread 회수 빨라짐 → RPS 처리 가능량 ↑ |
| **B 제주** | 다일자 (5일) × jeju 1400 properties × N+1 inventory range scan 폭발 | `idx_daily_room_rates_room_type_date` 가 *각 range scan 17ms → 0.01ms* → N+1 의 *누적 게인 1700×* (`comparison.md` 의 EXPLAIN 정합), 단일 호출 latency 폭락 |
| C 부산 | guests=4 필터로 결과 좁지만 여전히 N+1 흐름 | 동일 인덱스 게인 + 적은 결과 수 → 가장 안정적 |

→ **B 가 핵심 검증 시나리오** 인 이유: 다일자 + 도시 중간 share + sort 인덱스 의존 *모두* 발화. PR0 baseline 의 worst 가 PR1 의 *largest gain* 으로 직결.

---

## 4. 미반영 한계 (week6+ 인계)

- *Cold cache 측정* — 본 측정은 warm cache (k6 60s × 100 RPS = 6000 iterations 중 warm) 만. Cold cache 시점의 인덱스 게인은 *훨씬 더 큼* 예상.
- *N+1 잔량 부담* — 시나리오 A 의 RPS 100 미달은 *projection 미합류* 잔량. PR3 의 *projection 1쿼리* 합류 시 추가 게인 예상.
- *PRICE_ASC / RATING_DESC / WISHES_DESC sort* — 본 측정은 RECOMMENDED 통일. PR2 (D-2 비정규화) 의 sort=WISHES_DESC 측정과 PR3 의 projection 합류 시 별도 측정 (PR2 L-1 / PR5 E-1).
- *PR3 / PR4 / PR5 의 통합 게인* — 본 측정은 PR1 단독. 4 PR 누적 게인 (`E-1 통합 게인`) 은 PR5 E-1 에서 별도 박제 (`docs/plan/week5.md` Phase E).
- *부분 조합 16개 게인* — D-1 / D-2 / D-3 / D-4 의 *부분 조합* 게인은 본 라운드 측정 X (`week5.md` Q20 영구 한계 박제).
- *분산 generator 합류* — 본 측정은 단일 노드. 운영 SLA 박제는 분산 generator + 모수 ×50 (`seed-fixture.md §13`) 합류 시점.

---

## 5. EXPLAIN 어설션 회귀 (`PropertyFacadeSearchSortTest`)

본 측정의 *옵티마이저 plan 정합* 은 `PropertyFacadeSearchSortTest` (PR1 A-4) 가 *회귀 가드*. Testcontainers MySQL 8.0 의 EXPLAIN 결과를 매 빌드에서 검증:
- WISHES_DESC → `idx_properties_city_wish_count` + filesort 없음
- RATING_DESC → `idx_properties_city_rating` + filesort 없음
- PRICE_ASC → `idx_daily_room_rates_room_type_date` (JOIN row)

→ 미래 변경 (다른 인덱스 추가 / Hibernate 업그레이드 / SQL dialect 변경) 이 plan 을 *다른 인덱스로 옮기면* 통합 테스트가 fail → 본 측정 결과의 *근거가 사라짐* 을 즉시 발견.

---

## 6. 박제 사이클 종결

- ✅ AS-IS vs TO-BE 게인 매트릭스 (§1)
- ✅ D-1 반증 가드 PASS 박제 + decision.md 상태 전환 약속 (§2)
- ✅ 단일 SQL vs 통합 API 게인의 수직 차이 학습 박제 (§2)
- ✅ 시나리오 별 game-changing 요인 (§3)
- ✅ 미반영 한계 + 인계 박제 (§4)
- ✅ EXPLAIN 회귀 가드 (§5)

PR1 Phase L 완료. *PR2 (D-2 찜 수 정렬 비정규화) 진입 조건 충족*.
