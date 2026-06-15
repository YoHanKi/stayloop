# 재고 동시 차감 충돌 제어 — 조건부 원자 UPDATE 채택

week4 chunk 1(재고 동시 차감·복원 안전화). 관련 계획: `docs/plan/week4-a.md` §2·§5·§8, `week4-b.md` §3 Q1·Q3·§5-1.

## 1. 맥락 / 문제

예약 생성은 일자별 재고 row(`(roomTypeId, date)`)를 읽고 차감한다. 기존 흐름은 평문 SELECT 로 읽고 애플리케이션이 `available()` 을 판정한 뒤 UPDATE 하는 read-modify-write 라 격리 수준만으로는 Lost Update(더블부킹)가 열려 있었다. 닫아야 할 불변식:

- `(roomTypeId, date)` 예약 수 ≤ 총 객실 수 (더블부킹 금지)
- 다일자 예약은 전 일자 한꺼번에 성공/실패 (부분 차감 금지)

## 2. 선택지와 고민

낙관적 락(`@Version`)은 핫스팟에서 충돌→재시도 폭주로 역효과(04-a §5·§8)라 처음부터 제외하고, 두 후보를 실제로 구현해 부하로 비교했다.

- **비관적 락(locking read)**: 대상 일자를 일자 오름차순으로 `SELECT ... FOR UPDATE` 한 뒤 락 안에서 도메인 가드로 재판정·차감. 어느 일자가 매진인지 코드가 알고 복합 규칙을 표현하기 쉽다. 임계 구간이 read + 재판정 + flush 로 길다.
- **조건부 원자 UPDATE**: 가용한 일자만 한 문장으로 차감하고 영향 행 수를 요청 일수와 비교. DB 가 row 를 배타 점유한 채 조건을 평가하므로 초과 차감이 구조적으로 불가능. 임계 구간이 한 문장으로 짧다. 어느 일자가 만실인지 영향 행 수만으로는 분리되지 않는다.

## 3. 코드 예시

> 참고: 채택안의 쿼리 작성 방식(QueryDSL)과 영향 행 수 해석 위치(도메인 서비스)는 이후 리팩토링으로 정리했다 — [02-querydsl-and-affected-rows.md](02-querydsl-and-affected-rows.md). 아래는 두 전략을 비교하던 시점의 형태(개념).

채택(조건부 원자 UPDATE) — `WHERE reserved+1<=total` 를 한 문장으로 평가, 영향 행 수를 요청 일수와 비교해 부분 차감을 거른다:

```sql
UPDATE daily_room_inventories SET reserved_rooms = reserved_rooms + 1
WHERE room_type_id = ? AND date IN (...) AND reserved_rooms + 1 <= total_rooms
-- affected != 요청일수 → 일부 매진/부재 → CONFLICT 로 전체 롤백(부분 차감 되돌림)
```

기각(비관적 락) — 대상 일자를 오름차순 `SELECT ... FOR UPDATE` 로 잠근 뒤 락 안에서 도메인 가드로 재판정·차감:

```sql
SELECT * FROM daily_room_inventories
WHERE room_type_id = ? AND date IN (...) ORDER BY date ASC FOR UPDATE
-- 잠근 행마다 available>0 재판정 후 reserved+1 (매진이면 CONFLICT)
```

## 4. 실제 테스트 환경

- **정합성**: 실 MySQL 8.0(Testcontainers), `ConcurrentInventoryReserveTest`. N 스레드가 같은 판정 구간에 동시 진입(단일행 60스레드/마지막 N실, 다일자 40스레드, 차감·복원 혼합 20+20). 성공 수와 최종 row 값을 함께 검증. H2 는 `FOR UPDATE`·gap lock·데드락을 MySQL 과 동일 재현하지 않아 제외(04-a §7).
- **부하**: k6(`k6/local/inventory-strategy-compare.js`) + 핫스팟 시드(`seed-hotspot.sql`). 단일 property=1 × roomType=1 × 2026-06-01, `total_rooms=1억`(매진 없이 락 경합만 측정). `rate=200/s, duration=30s, 300 VU`. 앱 local 프로파일(HikariCP `maximum-pool-size=40`), MySQL 8.0 docker. 두 전략은 앱 재기동으로 전환해 동일 모수로 측정(측정 후 전략 스위치 설정은 제거).
- 단일 row 극단 경합(200/s on 1 row)은 의도적 worst-case. 실 트래픽은 다수 객실·일자로 분산되므로 절대치가 아니라 두 전략의 상대 차이가 근거.

## 5. 판단 기준

정합성(더블부킹·부분 차감 0)은 두 전략 모두 만족함을 전제로, 단일 핫스팟에서 ① reserve 2xx goodput, ② p95/avg latency, ③ 5xx(연쇄 장애 신호)로 우열을 가린다.

## 6. 결과

| 지표 | 비관적 락 | 조건부 UPDATE |
|---|---|---|
| reserve 2xx goodput | 2008 (56.30/s) | **2437 (68.47/s)** (+22%) |
| http_req_duration avg | 4.55s | **3.76s** (-17%) |
| http_req_duration p95 | 5.67s | **5.57s** |
| reserve 5xx | 32 (0.90/s) | **19 (0.53/s)** |
| dropped_iterations | 3961 | 3544 |

정합성 테스트(`ConcurrentInventoryReserveTest`)는 두 전략 모두 통과 — 동시 차감 성공 수 = 재고, reserved ∈ [0, total], 다일자 부분 차감 0. 즉 조건부 UPDATE 도 초과 차감하지 않음을 실측으로 확인(초과 위험은 비원자 read-then-update 패턴에서만 발생).

## 7. 트레이드오프

- 조건부 UPDATE 가 포기한 것: 어느 일자가 만실인지 영향 행 수만으로는 모름(일반 매진 CONFLICT 로 응답), 여러 행·복합 규칙을 한 문장에 담기 어려움.
- 비관적 락이 나았을 조건: 잠근 상태를 근거로 여러 결정을 해야 하거나, 일자별 실패 원인을 사용자에게 분리 노출해야 하거나, 복합 차감 규칙이 한 문장에 안 담길 때.
- 5xx 의 정체(양쪽 공통): 락 타임아웃이 아니라 HikariCP 커넥션 풀 고갈(connection-timeout 3s, pool 40, 동시 300). 긴 임계 구간이 커넥션을 오래 쥐어 무관한 요청까지 획득 실패 → 04-a §5 가 예고한 연쇄 장애. 조건부가 락 보유가 짧아 풀 압박·5xx 가 적었다.

## 8. 결론

**조건부 원자 UPDATE 채택, 비관적 락 구현 삭제.** 차감 규칙이 `reserved+1<=total` 단일 문장이라 04-a §8 의 "단일 row 카운터성 차감 1순위" 와 정합하고, 실측에서 처리량·지연·풀 압박 모두 우위. 비관적 락의 강점(일자별 매진 식별·복합 규칙)은 chunk 1 의 UX(일반 매진 CONFLICT 로 충분)와 저경합 쿠폰 경로(자체 상태전이+UNIQUE, 04-a §6.3)에서 필요하지 않다.

되돌릴 트리거: 일자별 매진 원인 분리 노출이 요구되거나, 재고 차감에 한 문장으로 안 담기는 복합 규칙이 합류하면 비관적 락(또는 락+조건부 혼합)을 재검토한다.

## 9. 후속 / 미해결

- 단일 핫스팟의 커넥션 풀 고갈은 풀 확대로 풀리지 않는다(04-a §8) → 상위 admission control·per-key rate limit·품절 캐시는 이번 범위 밖(04 미룬 빚).
- 락 경합 시 발생하는 lock-wait timeout·deadlock victim 의 실패 종류별 재시도는 chunk 3(예약 트랜잭션 통합)에서 다룬다. 현재 Facade 는 `PessimisticLockingFailureException` 을 CONFLICT 로 흡수만 한다.
