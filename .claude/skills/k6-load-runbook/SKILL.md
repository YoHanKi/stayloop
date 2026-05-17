---
name: k6-load-runbook
description: |
  k6 부하 테스트를 *안전하고 학습 가능하게* 진행하는 runbook. Grafana/Prometheus 모니터링 환경
  구축 + 사용자 확인 대기 절차 + 측정 *중* 유의 깊게 봐야 할 메트릭 + red flag 패턴 + 측정 *후*
  박제 항목을 단계별로 정의한다. CLAUDE.md 의 "k6 부하 실행 정책" 의 *상세 가이드*.
  Claude 가 임의로 k6 를 실행하지 않도록 *절차적 가드* 역할.
user-invocable: true
---

본 skill 은 *k6 부하 실행* 의 의무 절차 + 모니터링 안내. CLAUDE.md `## 실험 테스트 ### k6 부하 실행 정책`
의 *상세 가이드*. **사용자가 "k6 시작해줘" / "부하 측정해줘" 등으로 명시 요청하거나, Claude 가 k6 발사를
앞두고 본 skill 의 절차를 답습해야 한다.**

---

## 0. 본 skill 의 핵심 정책 (one-liner)

> **k6 부하는 *반드시* 사용자가 *실시간 그래프* 를 보고 있는 상태에서 발사한다**. Claude 가 임의로 발사 X.

이유: k6 부하의 학습 자산은 *결과 박제 (k6-results.md)* 가 아니라 *부하 중 거동* — 사용자가 *어디서 무엇이
꺾이는가* 를 *실시간으로* 봐야 *원인 추적* 이 가능하다. 결과는 *사후 숫자* 일 뿐.

---

## 1. 사전 환경 구축 (k6 발사 *전*)

### 1.1 Grafana / Prometheus 부팅

```bash
docker-compose -f ./docker/monitoring-compose.yml up -d
```

- Prometheus `http://localhost:9090` — `/targets` 가 `stayloop-stay-api` 의 `health = up` 인지 확인
- Grafana `http://localhost:3000` (admin/admin) — `Stayloop` 폴더의 `Stayloop — k6 Load Test (stay-api)` 대시보드
  자동 등록 (`docker/grafana/provisioning/dashboards/stayloop-k6-load.json`)

### 1.2 stay-api 부팅

```bash
SPRING_PROFILES_ACTIVE=local MANAGEMENT_SERVER_PORT=8082 ./gradlew :apps:stay-api:bootRun
```

- 8082 override 이유: 로컬 wslrelay.exe 가 8081 점유. 추후 환경 정합 시 정책 재검토.
- `Started StayApiApplicationKt` 로그 확인 + `curl http://localhost:8082/actuator/prometheus` 응답 확인.

### 1.3 Prometheus 가 scrape 시작 후 사용자 확인

- `curl -s http://localhost:9090/api/v1/targets` 의 `health` 가 `"up"` 으로 전환되는 데 ~5초 소요.
- *Grafana 대시보드 에서 시계열이 흐르기 시작* 하면 사용자에게 *"준비됐다 / 시작해" 명시 요청*.

---

## 2. 측정 *중* 유의 깊게 봐야 할 메트릭 (우선순위 순)

### 🚨 P0 — 즉시 중단 / 분석 필요

| 메트릭 | 임계 | 의미 |
|---|---|---|
| **5xx rate** | > 0 | *서버 측 실패* — 백프레셔 / pool exhaustion / app crash. 0 이 아니면 *즉시 원인 추적* |
| **HikariCP `pending`** | > 0 | DB connection pool *포화* — 대기 큐 발생. 1만 넘어도 의미 (queueing). |
| **JVM heap used > 85%** | 지속 | GC pause 시작 — p95 cliff 임박. heap dump 후보 |

### ⚠️ P1 — 트렌드 관찰

| 메트릭 | 의미 |
|---|---|
| **HTTP RPS (by URI)** | *target RPS 도달* 확인. target 100 인데 70 만 도달 = 백프레셔 발생 신호 |
| **HTTP p95 (by URI)** | SLA 임계 비교 — 시나리오 별 임계 (`week5.md §⓪.5` 표) |
| **HTTP p99 / max** | *worst-case tail* — p95 OK 라도 max > 1s 면 *간헐 hang* |
| **Tomcat `busy_threads`** | accept queue 임계 — `max` 에 근접하면 *대기 큐 시작*. server thread 풀 = 200 default |
| **HikariCP `active`** | *효과적 동시 DB 작업 수* — DB connection 풀 size (default 10) 와 비교 |
| **CPU usage process** | *bottleneck 위치* — 80% 넘으면 CPU bound, 그 미만이면 IO bound |

### 📊 P2 — 사후 박제 자료

| 메트릭 | 박제 위치 |
|---|---|
| iterations / dropped_iterations | k6-results.md §RPS 도달도 |
| http_req_duration p50/p95/p99 | k6-results.md §시나리오 별 결과 |
| HikariCP peak active | k6-results.md §부하 중 거동 |
| Tomcat peak busy | k6-results.md §부하 중 거동 |
| 5xx total | k6-results.md §에러 분포 |

---

## 3. Red flag 패턴 (실시간 인지)

### 3.1 5xx 점프 (가장 위험)
- *원인 후보*: DB connection 고갈 (`hikaricp_pending` 동반 점프), app OOM, DB timeout.
- *조치*: 즉시 부하 중단 (`k6` Ctrl+C). 로그 / heap dump 확인.

### 3.2 p95 cliff (수직 점프)
- *원인 후보*: GC pause (heap usage > 80%), thread starvation (Tomcat busy 임계), DB lock contention.
- *조치*: 부하 지속 가능, 측정 후 원인 분류. 같은 시점의 *다른 메트릭 동조* 확인.

### 3.3 RPS plateau (target 미달)
- *원인 후보*: client-side (k6 generator CPU 포화 — `vus_max` 도달), server-side (pool 포화 + 백프레셔).
- *조치*: server 측 메트릭 정상이면 *client 측 한계* (분산 generator 합류 시점에 재측정).

### 3.4 HikariCP active 가 *너무 낮음*
- 비정상: 부하 중인데 active = 0~1. *DB 쿼리가 도달 못 하는 신호* — Tomcat thread starvation 또는 application 단 lock contention.

### 3.5 Tomcat busy 가 *max 도달*
- 임박: 200 thread default — 모두 점유 시 *accept queue* (default 100) 로 흘러감, 그 후 *connection refused*.
- 조치: server thread 풀 부족 또는 *backend (DB / Redis) 의 응답 지연*.

---

## 4. 사용자 확인 대기 패턴

**Claude 가 발사 전 보내는 메시지 템플릿**:

```
모니터링 환경 준비 완료:
- Grafana: http://localhost:3000 (admin/admin) → "Stayloop — k6 Load Test (stay-api)" 대시보드
- Prometheus targets: http://localhost:9090/targets (stayloop-stay-api = up)
- stay-api 8080 / management 8082 정상

Grafana 대시보드에서 시계열이 흐르는 게 확인되면 알려주세요.
준비되면 k6 run k6/local/<scenario>.js 시작합니다.
```

**사용자가 "준비됐다" / "시작해" / "쏴줘" 등 명시 신호** → k6 발사.

**사용자가 "대시보드가 안 보인다" / "Prometheus target 이 down" 등 문제 신호** → 환경 디버깅 후 다시 확인 요청.

---

## 5. k6 실행 + 진행 모니터링

```bash
k6 run k6/local/search-scenario-<a|b|c>.js
```

- *60s 시나리오* 기준 — 첫 10s 워밍업 / 50s 정상 부하 / 종료 후 thresholds 결과 출력.
- k6 stdout 의 `dropped_iterations` 가 *0 이상* 이면 RPS 미달 (대기 발생).
- thresholds 위반 시 마지막 줄에 `level=error msg="thresholds on metrics ... have been crossed"`.

---

## 6. 측정 *후* 박제

`documents/feature/{topic}/k6-results.md` (gitignored, 로컬 박제 — CLAUDE.md *Md 박제 파일 commit 금지*
정책 정합):

### 박제 형식 (필수 항목)

```markdown
## 0. 실행 환경
- 실행일 / 브랜치 / 활성 변경 / SLA 임계

## 1. 시나리오 X (조건 ...)
| 메트릭 | 값 | SLA |
- avg / min / p50 / p90 / p95 / p99 / max
- http_req_failed rate
- iterations / dropped_iterations
- vus (실 사용 / max)

### AS-IS vs TO-BE 게인
| 단계 | p95 (ms) | 배수 |
- PR0 baseline / 이전 PR / 본 PR

## 2. 부하 중 거동 (Grafana 캡처 / 메모)
- HikariCP peak active = ?
- Tomcat peak busy = ?
- JVM heap peak = ?
- 5xx 분포 (있다면)
- p95 cliff 발생 시점 (있다면)

## 3. 해석 — 본 PR 의 채택안이 만든 차이
- 단일 SQL 측정 (`comparison.md`) 의 *수직 차이* (있다면)
- *어디가* 느렸는가 (cliff 원인 / pool / GC / lock 등)

## 4. 반증 가드 결과
- PASS / FAIL + 임계 박제
- decision.md D-N 상태 전환 약속

## 5. 다음 진입 동기 (있다면)
```

---

## 7. 본 skill 의 종결 조건

- ✅ Grafana / Prometheus 부팅 + 대시보드 접속 확인
- ✅ stay-api 부팅 + Prometheus scrape `up`
- ✅ 사용자 확인 *명시 신호* 수신
- ✅ k6 발사 + 60s 시나리오 종료
- ✅ k6-results.md 박제 (로컬, untracked)
- ✅ Red flag 발생 시 *즉시 분석 + 박제* — 발견 자체가 학습 자산

본 skill 의 *진짜 가치* 는 *발사 자체* 가 아니라 *부하 중 무엇을 봤는가* 의 박제 — 사용자의 *실시간 관찰* 이
없으면 본 skill 의 의미가 절반.
