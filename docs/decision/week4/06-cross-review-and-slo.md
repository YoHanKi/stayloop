# week4 교차 검토 — 고민 반영도, 아쉬운 점, SLA/SLO

week4(트랜잭션·동시성) 4 chunk 완료 후, 계획(week4-a/-b)의 고민이 구현에 녹았는지 외부 모델(Gemini 0.45, Codex gpt-5.5)과 교차 검토하고, 남은 간극에 대안·테스트·부하 결정 기준·트레이드오프·SLA/SLO 를 지정한다. 검토 프롬프트는 `_review-prompt.md`(커밋 제외).

## 1. 종합 평가 — 고민은 대체로 잘 녹았다

두 모델 모두 동의한 강점:
- read-modify-write → **조건부 원자 UPDATE / 원자 INSERT** 일관 적용(재고 차감·쿠폰 발급/사용·찜 카운터).
- **영속성은 사실(영향 행 수)만 반환, 비즈니스 판단은 도메인/앱** 계층 분리.
- 예약 생성에서 **재고→쿠폰→저장을 한 트랜잭션** 으로 묶고 부분 실패 전체 롤백.
- **실 MySQL(Testcontainers)** 로 동시성 검증(성공 수 + 최종 row 동시 확인).

즉 -a 의 트레이드오프(격리수준 vs 락 vs 조건부)와 -b 의 적용 방향(임계 구간 최소화, 영향 행 수 검증)은 코드에 충실히 반영됐다.

## 2. 발견·수정 — P0: 동시 예약 취소 과복원 (Codex 발견, 수정 완료)

- **현황(버그)**: `ReservationFacade.cancel` 이 예약을 **락 없이** 조회 → 엔티티 메모리 전이(CANCELLED) → 재고 release 했다. 같은 예약 동시 2회 취소 시 둘 다 PENDING 을 읽고 둘 다 `canTransitTo` 를 통과해 **재고가 2번 복원**(같은 일자 다른 예약이 있으면 reserved 과복원). 기존 `ConcurrentReservationTest` 는 동시 *생성* 만 봤고 동시 *취소* 공백.
- **수정**: 취소를 `findByIdForUpdate`(비관적 쓰기 락) 조회로 바꿔, 둘째 트랜잭션이 락 해제 후 **신선한 CANCELLED** 를 읽어 상태 전이에서 CONFLICT 로 걸리게 했다(단일 row·저경합이라 비관 락 비용이 작다). 회귀 테스트 `duplicateCancel_restoresOnce`(같은 일자 2예약 → 한 예약 20동시취소 → 성공 1·reserved 1) 추가, 통과.
- **대안 비교**: Codex 는 조건부 상태 UPDATE(`WHERE status IN (PENDING,CONFIRMED)`)를 권했다. 동등하게 정합하지만, 벌크 UPDATE + 같은 트랜잭션 엔티티 재조회의 L1 캐시 staleness 처리가 필요해, 저경합 단일 row 에는 FOR UPDATE 가 더 단순·명료해 채택. 트레이드오프: 비관 락 1건 도입(취소는 핫스팟 아님).

> Gemini 의 P0(다일자 재고 차감 IN-리스트 정렬 누락 → 데드락)은 **기각**. 단일 statement 조건부 UPDATE 는 InnoDB 가 PK 인덱스 순서로 락을 잡아 IN-리스트 표기 순서와 무관하다(Codex 도 P2 "관찰되면 재검토"로 강등). 정렬은 효과 없는 장식이라 추가하지 않음 — 데드락이 실제 관찰되면 그때 재검토.

## 3. 남은 아쉬운 점 (백로그) — 대안·테스트·부하 기준·트레이드오프

### P1-1. 쿠폰 없는 예약의 요청 단위 멱등 부재
- 현황: 쿠폰 보유 예약은 쿠폰 원자 사용이 중복을 막지만, 쿠폰 없는 예약은 더블클릭·재시도로 중복 생성 가능(계획 §6 에서 의식적 공백).
- 대안: `Idempotency-Key`(클라이언트 발급) + userId + endpoint 를 키로, 예약 테이블 UNIQUE 또는 Redis SETNX 로 최초 결과 재응답.
- 테스트: 같은 키 30 동시 요청 → 성공 1·동일 응답.
- 부하 기준: 멱등 저장소 p95 < 10ms, 중복 재응답 성공률 99.99%.
- 트레이드오프: 키 보관 TTL·저장소 비용 vs 중복 차단. 채택 트리거: 업무 키로 안 잡히는 중복이 운영에서 관찰될 때.

### P1-2. 재시도 정책이 단순함 (실패 원인 미분리·전체 deadline 없음)
- 현황: `retryOnTransientLock` 가 `PessimisticLockingFailureException` 만 3회·20~40ms 지터로 재시도. 데드락(1213)/락 타임아웃(1205)/커넥션 획득 실패를 구분하지 않고, 전체 요청 deadline·지수 백오프가 없다.
- 대안: 데드락=짧은 지터 재시도, 락 타임아웃=백오프 길게 또는 빠른 실패, 커넥션 획득 실패=재시도 금지. 전체 SLA deadline 상한·지수 백오프 추가.
- 테스트: 데드락/락 타임아웃 강제 주입 후 분기별 동작 검증.
- 부하 기준: 재시도 후 성공률, p99, retry storm 부재(재시도율이 부하와 함께 폭증하지 않음).
- 트레이드오프: 정교함 vs 코드 복잡도. (현재 기준선은 데드락 흡수엔 충분.)

### P1-3. 빠른 실패 설정 부재 (lock_wait_timeout / query timeout 정합)
- 현황: 문서에 언급했으나 설정·코드 미반영. MySQL 기본 `innodb_lock_wait_timeout`(50s)은 웹 요청에 과도하게 길다.
- 대안: 세션/트랜잭션 단위 `innodb_lock_wait_timeout` 단축, JDBC query timeout 과 HikariCP `connection-timeout`(현재 3s) 정합. 비관 락 경로엔 `NOWAIT/SKIP LOCKED` 선택지.
- 테스트: 락 보유 상태에서 경쟁 요청의 실패 시간이 목표 내인지.
- 부하 기준: 락 대기 p95, tail latency. 트레이드오프: 빠른 실패(가용성 체감↑) vs 성공률.

### P1-4. 커넥션 풀 포화 보호(admission control) 부재
- 현황: chunk1 k6 에서 단일 핫스팟 시 HikariCP(40) 고갈로 5xx 관찰. 풀 확대로는 안 풀린다(-a §8).
- 대안: per-key(roomTypeId/templateId) Semaphore/Bulkhead 또는 전역 admission control 로 핫키 유입 제한 후 429/409 빠른 실패.
- 테스트: 핫키 폭주 시 무관 요청이 보호되는지(격리).
- 부하 기준(활성화 임계): Hikari pending > 0 가 30s 지속, connection acquire p95 > 100ms, 예약 p99 > 2s.
- 트레이드오프: 일부 요청 조기 거절 vs 연쇄 장애 차단.

### P1-5. 락 밖 견적의 임계 구간 재검증 부족
- 현황: 요금·쿠폰을 락 밖에서 읽어 가격을 스냅샷하나, 임계 구간에서 요금 변경을 재검증하지 않는다(-b §3 Q3 "재판정" 의 부분 미이행). 요금이 그 사이 바뀌면 스냅샷이 과거 값.
- 대안: 요금 `version/updatedAt` 재검증 또는 "요금 변경 불가 윈도우". 현실적으로 요금은 자주 안 바뀌어 우선순위는 중간.
- 테스트: 견적 후 요금 변경 주입 → 스냅샷 정책대로 동작.
- 트레이드오프: 정확성 vs 임계 구간 길이.

### P2-1. 관찰성 지표 부재
- 현황: 재시도 횟수·sold-out·중복·rollback·Hikari pending·DB lock wait 를 업무 지표로 묶은 코드가 없다(silent retry).
- 대안: Micrometer counter/timer 를 유스케이스 단위로(reserve/cancel/coupon/wish), 정합성 점검 배치(예약 합 = 재고 reserved, 찜 행 수 = wishCount).
- 트레이드오프: 계측 비용 vs 운영 가시성. (재시도 발생은 최소 warn 로그 + 메트릭.)

### P2-2. INSERT IGNORE 의 광범위한 오류 무시
- 현황: 찜 `INSERT IGNORE` 는 중복키 외 일부 데이터 오류(절단 등)도 경고로 낮춘다.
- 대안: plain INSERT + `DuplicateKeyException` 만 멱등 성공 처리, 또는 `ON DUPLICATE KEY UPDATE` no-op 으로 영향 행 수 의미 명확화. (찜 테이블은 입력이 단순해 위험 낮음 → 우선순위 낮음.)

### P2-3. 쿠폰 USED 확정/환불 모델 (결제 합류 시)
- 현황: 예약 생성 커밋 시 즉시 USED, 취소 시 AVAILABLE 복원. 결제·환불 없음(이번 라운드 전제).
- 대안: 결제 합류 시 `HELD → USED → RESTORED/EXPIRED` + hold TTL. 테스트: 결제 실패/성공 후 환불/취소 수수료별 쿠폰 상태. (week5~6.)

## 4. 권장 SLA / SLO

가용성은 월간, 지연은 서버 처리 시간(네트워크 제외, 재시도 포함). **정합성 위반(더블부킹·초과발급·과복원·카운터 드리프트)은 0건이 SLO** — 가용성과 별개의 경성 목표.

| 기능 | 가용성 SLO | 지연 SLO | 정합성 SLO | 에러 예산 |
|---|---:|---|---|---:|
| 예약 생성 | 99.9% | p95 < 800ms, p99 < 2s, 핫스팟 p99 < 3s | 더블부킹 0 | 월 ~43분 |
| 예약 취소 | 99.95% | p95 < 500ms, p99 < 1.5s | 재고 과복원 0 | 월 ~22분 |
| 쿠폰 발급 | 일반 99.9% / 선착순 이벤트 99.5% | p95 < 300ms, p99 < 1s | 초과·중복 발급 0 | (소진·중복은 비즈니스 실패로 제외) |
| 찜 토글 | 99.95% | p95 < 200ms, p99 < 700ms | wishCount 드리프트 ≤ 0.01%, 일배치 100% 보정 | 월 ~22분 |
| DB 경합 보호(횡단) | Hikari pending 30s 이상 지속 0 | connection acquire p95 < 100ms | — | 초과 시 핫키 admission control / 429·409 |

- 측정: 위 지연·가용성은 부하테스트(k6)로 기준선을 잡고, 운영에선 위 지표를 대시보드화해 에러 예산 소진을 추적.
- 핫스팟 처리량 상한(-a §5)은 임계 구간 길이가 좌우하므로, 예약 생성 핫스팟 p99 3s 를 넘기면 임계 구간 단축 또는 admission control 을 먼저 검토(풀 확대 금지).

## 5. 결론 / 우선순위

- **P0(동시 취소 과복원): 수정 완료** + 회귀 테스트.
- 다음 권장 순서: **P1-1(멱등 키) → P1-4(admission control) → P1-2(재시도 정교화)·P1-3(빠른 실패) → P2(관찰성·INSERT IGNORE)**. 대부분 운영/부하 단계의 빚으로, 트리거(관찰 임계)를 위 표로 못박았다.
- week4 의 동시성 정합 코어(더블부킹·초과발급·과복원·드리프트 0)는 실 MySQL 테스트로 닫혔다. 남은 것은 "운영 견고성(멱등·유입 제어·관찰성)"이며, 결제 합류(week5~6) 와 함께 가져간다.

## 부록 — 교차 검토 원본 요지
- Gemini: 조건부 UPDATE 일관 적용·계층 분리 우수. P0 로 일자 정렬을 지목(본 문서에서 기각). 멱등·재시도 관찰성·풀 고갈·SLO 제시.
- Codex(gpt-5.5): 동시 취소 과복원(P0, 채택·수정)·요청 멱등·재시도 정교화·lock timeout·admission control·락 밖 재검증·INSERT IGNORE 한계·관찰성·SLO 제시.
