# k6 AS-IS Baseline 측정 결과 (week5 PR0 L2)

> **SSOT 위상.** 본 문서는 *AS-IS baseline 측정의 실측 박제*. 인덱스 0 / N+1 / 캐시 0 / projection 0 / sort=RECOMMENDED 만 활성 상태의 *맨몸 검색* 응답 분포. PR1~PR4 의 *TO-BE vs AS-IS 게인 (배수)* 계산의 분모.
>
> **짝 SSOT.** `documents/feature/perf-experiment-infra/seed-load.md` 가 *모수 SSOT*, 본 문서가 *baseline 응답 분포 SSOT*. 측정 의미 한계는 `docs/plan/week5.md §GR-6`.

---

## 0. 실행 환경

| 항목 | 값 |
|---|---|
| 실행일 | 2026-05-16 |
| 측정 도구 | k6 v1.6.1 (windows/amd64), constant-arrival-rate executor |
| 대상 | `./gradlew :apps:stay-api:bootRun` (profile=local), `localhost:8080` |
| DB | docker-compose MySQL 8.0 (`stayloop` schema, seed-load.md 의 2.05M row 적재 상태) |
| Redis | docker-compose `redis-master:6379` / `redis-readonly:6380` (PR4 진입 전이라 *코드가 캐시 호출하지 않음*) |
| Tomcat | max threads 200 / accept-count 100 / connection-timeout 1m (jpa.yml default) |
| Hikari | `mysql-main-pool` max-pool-size 40 / min-idle 30 |
| JVM | Java 17 corretto, default heap |
| Schema | V001 적용 상태 — 인덱스 = `idx_properties_city` + `idx_properties_rating` + `idx_properties_wish_count` (단일 컬럼) + `idx_room_types_property_id` + `idx_property_images(property_id, display_order)`. 검색 패턴에 *맞춰진* 복합 인덱스는 없음 (PR1 D-1 의 비교군 (a) 인덱스 없음 baseline 보다 *좀 나은* 시작점 — 단일 컬럼은 있음). |

**중요 한계 (`week5.md GR-6`).** 본 측정의 *p95 절대값* 은 운영 SLA 박제 금지 — 단일 노드 CPU contention + buffer pool fit (≈ 220 MB ⊂ default 128 MB 보다 큼, OS page cache 까지 합치면 fit) 으로 *상한 추정* 만. **AS-IS vs TO-BE 게인 (배수)** 만 신뢰. 운영 SLA 박제는 운영 합류 시점 + 분산 generator + 모수 ×50 재상향 후 (week6+ 인계).

---

## 1. 시나리오 별 결과 (AS-IS, sort=RECOMMENDED)

### 1.1 시나리오 A — 서울 / 5/15~5/17 / 2명 / RPS=100 / 60s

| 메트릭 | 실측 | SLA |
|---|---|---|
| iterations 완료 | **3204** | — |
| iterations dropped | **2797** | (target 100 RPS × 60s = 6000 — 절반만 처리) |
| 실효 RPS | 48.6 /s | 100 /s |
| status 2xx | **548 (17.1%)** | — |
| status 5xx | **2656 (82.9%)** | — |
| `http_req_duration` p(90) | 7.62s | — |
| `http_req_duration` **p(95)** | **7.85s** | < 200ms (✗ 약 **39배** 초과) |
| `http_req_duration` p(95) — *expected_response=true* (2xx 만) | 8.16s | — |
| `http_req_failed` | 82.89% | < 1% (✗) |

**해석.** 서울 (1800 properties) 검색에서 *RPS 100 을 못 받아낸다*. 절반 이상 (2797/6000) 이 Tomcat queue (accept-count=100) + connection-timeout (1m) 안 들어가지도 못하고 drop. 들어간 요청도 *5xx 83%* — 처리 능력이 *RPS 50 미만*. 단일 호출 latency 가 ≈ 1s (k6 외 curl 단발 측정) 라 *직렬 호출 모델* 에 fit 안 함.

### 1.2 시나리오 B — 제주 / 7/30~8/4 / 2명 / RPS=50 / 60s ← **PR2 D-2 핵심 측정**

| 메트릭 | 실측 | SLA |
|---|---|---|
| iterations 완료 | **2155** | — |
| iterations dropped | **845** | (target 50 RPS × 60s = 3000 — 28% drop) |
| 실효 RPS | 31.4 /s | 50 /s |
| status 2xx | **318 (14.75%)** | — |
| status 5xx | **1837 (85.24%)** | — |
| `http_req_duration` p(90) | 11.11s | — |
| `http_req_duration` **p(95)** | **11.63s** | < 300ms (✗ 약 **38배** 초과) |
| `http_req_duration` p(95) — *expected_response=true* | 12.53s | — |
| `http_req_failed` | 85.24% | < 1% (✗) |

**해석.** 제주 (1400 properties) + *5일 범위* (5/15~5/17 의 2일 vs 7/30~8/4 의 5일) → `daily_room_inventories` JOIN 행 수 폭증. 시나리오 A 보다 RPS 가 절반 (100 → 50) 인데 p95 가 *1.5배 느림* (7.85s → 11.63s). 다일자 범위가 인덱스 부재의 *대표 케이스* — PR1 D-1 의 `daily_room_rates(room_type_id, date)` 보조 인덱스 게인이 *가장 크게 발화* 할 시나리오.

본 시나리오의 sort 는 RECOMMENDED — PR1 D-6 활성 후 sort=WISHES_DESC 로 전환 시 PR2 D-2 비정규화 게인 측정의 핵심.

### 1.3 시나리오 C — 부산 / 5/10~5/11 / 4명 / RPS=100 / 60s

| 메트릭 | 실측 | SLA |
|---|---|---|
| iterations 완료 | **3391** | — |
| iterations dropped | **2609** | (28.5% drop) |
| 실효 RPS | 53.5 /s | 100 /s |
| status 2xx | **2899 (85.5%)** | — |
| status 5xx | **492 (14.5%)** | — |
| `http_req_duration` p(90) | 3.91s | — |
| `http_req_duration` **p(95)** | **3.95s** | < 200ms (✗ 약 **20배** 초과) |
| `http_req_duration` p(95) — *expected_response=true* | 3.95s | — |
| `http_req_failed` | 14.50% | < 1% (✗) |

**해석.** 부산 (1100 properties) + 1일 + *4 guests* — `room_types.max_guests >= 4` 필터가 결과를 *대폭 좁힘* (대부분 PENSION/RESORT 만 통과). 부담이 작아 5xx 14% 까지 줄고 p95 도 4s 대. *맨몸 검색이 가장 잘 견디는 케이스* — PR1 D-1 게인이 다른 시나리오보다 *작게 나올 위험* 박제 (게인 측정의 *분모* 가 이미 낮음).

---

## 2. 시나리오 간 비교 + 핵심 발견

| 시나리오 | 도시 | 일수 | guests | p95 (s) | 5xx | dropped | 부담 요인 |
|---|---|---|---|---|---|---|---|
| A | 서울 | 2 | 2 | **7.85** | 82.9% | 47% | 도시 share 18% (1800 properties) + 검색 path 전체 부담 |
| B | 제주 | **5** | 2 | **11.63** | 85.2% | 28% | 도시 share 14% × *5일 범위* — inventory JOIN 폭증 |
| C | 부산 | 1 | **4** | **3.95** | 14.5% | 28% | guests 필터로 결과 좁아짐 |

**핵심 관찰 (L2 닫힘):**

1. ✅ "느림이 보인다" — 모든 시나리오에서 p95 가 SLA 의 **20~39배** 초과. seed 모수 (10k properties + 2M inventory) 가 *분포 학습 + 측정 신뢰성* 양면에서 정합.
2. ✅ **시나리오 B 가 worst** — *다일자 범위 + Pareto wish_count 분포 + 미인덱스* 가 정확히 누적. PR1 D-1 의 `daily_room_rates(room_type_id, date)` 인덱스 게인이 *시나리오 B 에서 가장 큼* 가설 사전 박제.
3. ✅ **시나리오 C 가 best** — guests 필터가 결과를 좁힘 → JOIN 부담 ↓. PR1 D-1 의 게인이 *시나리오 C 에서 가장 작음* 가설 사전 박제 (D-1 반증 임계 *5배 게인* 의 결과 시나리오 간 차이 박제).
4. ⚠️ **5xx 의 정체** — bootRun 로그에서 정확한 stack trace 미발견 (디버그 로그 미활성). 후보:
   - **(a)** Tomcat thread pool exhaustion (max=200, p95=8~12s × 100 RPS = 800~1200 동시 요청 → 200 threads 부족)
   - **(b)** Hikari connection pool exhaustion (max=40, 평균 connection hold ≈ 3.5s × 50 RPS = 175 동시 connection 필요)
   - **(c)** 두 가지 동시 발화
   - → 본 baseline 의 *5xx 원인 분리* 는 본 라운드 scope 외 (PR1 의 인덱스 게인 측정이 본질, error 자체가 indicator). week6+ 의 *부하 patterns / APM 합류* 시점 (week5.md §13 인계).

---

## 3. 측정 의미론 (반복 박제 — `GR-6`)

> **본 p95 절대값은 운영 SLA 가 아니다.** 본 측정의 의미는 *AS-IS 분포 박제* — PR1~PR4 의 *TO-BE 측정값* 과 *나누어서* (배수) 게인 계산하는 것이 전부. 운영 SLA 박제는:
> - 분산 generator (k6 cloud / 다 노드)
> - 모수 ×50 재상향 (seed-fixture.md §13)
> - 다회 trial 평균 + 표준편차
> - 운영 buffer pool / connection pool / Tomcat threads 정합
>
> 이 4 조건 정합 후 운영 합류 시점에 *재박제* (week6+).

**환경 한계 박제 (`seed-fixture.md §13` 정합):**
- 본 측정의 buffer pool 거동 — 200만 row × 220 MB 가 default docker MySQL buffer pool (128 MB) 보다 *크지만* OS page cache 까지 합치면 cold cache 가 *부분적으로만* 발화. cold/warm 분리 측정 (1차 호출 vs 10회 평균) 은 본 라운드 *미박제* — PR1 Phase L 의 측정에서 분리.
- *5xx 14~85%* — 본 baseline 자체가 *서비스 상태 외* 라 게인 계산 시 *2xx 만의 p95* 를 분모로 사용 (위 표의 `expected_response=true` 행 — A: 8.16s / B: 12.53s / C: 3.95s).

---

## 4. PR1+ 인계 가설 (사전 박제)

본 baseline 으로부터 *측정 전* 박제 (PR1 의 D-1 반증 가드 (5배 게인) 의 기준):

| # | 가설 | PR1+ 의 어디서 검증 | AS-IS 기준 |
|---|---|---|---|
| H-A | 시나리오 B (다일자 + wishes_desc) 가 PR1 D-1 의 게인 *5배 이상* 시현 | `documents/feature/property-search-perf-index/k6-results.md` (PR1 L-1) | B p95 = 11.63s → 1/5 = 2.33s 이하 시 PASS |
| H-B | 시나리오 C (4-guest 필터) 의 게인이 *작음* — 게인 가드 (5배) 가까스로 통과 또는 미달 | 동일 위치 | C p95 = 3.95s → 1/5 = 0.79s 이하 시 PASS |
| H-C | 시나리오 A (서울 / 2일) 의 게인은 B/C 사이 | 동일 위치 | A p95 = 7.85s → 1/5 = 1.57s 이하 시 PASS |
| H-D | 5xx 비율이 PR1 인덱스 + projection 적용 후 **< 1%** 로 떨어짐 (Tomcat / Hikari 가 단일 요청 latency ↓ 로 안정화) | PR3 L-1 (projection 측정) 또는 PR5 E-1 통합 | 현 14~85% → < 1% 시 PASS |

---

## 5. 박제 사이클 — L2 닫힘

- ✅ k6 시나리오 a/b/c 작성 + AS-IS 측정 (`k6/local/search-scenario-{a,b,c}.js`, gitignored)
- ✅ 본 문서 박제 — 인덱스 0 / N+1 / 캐시 0 / sort=RECOMMENDED only 상태의 p95 + error rate
- ✅ 측정 의미 한계 박제 (`GR-6` — 절대값 X, 게인 (배수) 만)
- ✅ PR1+ 인계 가설 사전 박제 (반증 임계 기준)
- ⏳ **나머지 4 k6 시나리오** (`property-detail-cache.js` / `search-result-cache.js` / `availability-dynamic-cache.js` / `reservation-concurrency.js`) — *PR4/PR5 진입 시점에 작성* (week5-b.md 의 *commit-script 의존 그래프 깨끗* 원칙).

➡️ `week5-b.md §17` L2 ✅ 닫힘 박제. L3 (EXPLAIN 으로 발견) 진입 조건 충족 — 시나리오 B 의 worst p95 가 *어디서* 가는지가 PR1 의 첫 EXPLAIN 진입 동기.
