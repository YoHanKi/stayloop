# 운영 견고성 1 — 요청 멱등 키 · 빠른 실패 · 견적 재검증

week4 교차 검토(06) 백로그 중 P1-1, P1-3, P1-5 구현 기록.

## P1-1. 예약 요청 단위 멱등 키

- 맥락: 쿠폰 없는 예약은 더블클릭·네트워크 재시도로 중복 생성 가능(06 P1-1, 계획 §6 공백).
- 결정: `Idempotency-Key` HTTP 헤더(선택) → `reservations.idempotency_key` **UNIQUE 컬럼**(nullable, NULL 다중 허용). 흐름:
  1. 사전 조회 — 같은 키 예약이 이미 있으면 작업 없이 그대로 재응답(흔한 재시도 케이스 비용 0).
  2. 임계 구간에서 키와 함께 저장 — 동시 같은 키는 UNIQUE 위반(`DataIntegrityViolationException`)으로 한 건만 커밋, 진 쪽은 catch 후 최초 예약을 재응답(둘 다 같은 예약을 받음).
- 코드: `ReserveCommand.idempotencyKey`, `ReservationRepository.findByIdempotencyKey`(읽기 tx 래핑 — open-in-view=false), Facade 사전조회+충돌 재응답.
- 테스트: POJO `shouldReplayOnSameIdempotencyKey`(2회 → 동일 예약·재고 1차감), 실 MySQL `sameIdempotencyKey_createsOnce`(4 동시 → 1건 생성·모두 동일 id·재고 1).
- 트레이드오프: 컬럼·UNIQUE 추가 vs 중복 차단. 키 미전송 시 기존 동작(멱등 없음). 키 보관 만료(TTL) 정책은 후속.

## P1-3. 빠른 실패 — 트랜잭션/문장 타임아웃

- 맥락: MySQL 기본 `innodb_lock_wait_timeout`(50s)은 웹 요청에 과도(06 P1-3).
- 결정: 예약 `TransactionTemplate.timeout = 5s` — 트랜잭션·문장(JDBC) 타임아웃을 묶어 락 대기를 5초에 끊는다(웹 요청 fail-fast). 전역 `innodb_lock_wait_timeout` 은 배치까지 깨므로 건드리지 않고, **트랜잭션 단위**로 둔다(04-a §8 "세션·트랜잭션 단위" 정합).
- 트레이드오프: 성공률(긴 대기 허용) vs tail latency·가용성 체감. 5s 는 기준선, 측정 후 조정. 비관 락 `NOWAIT/SKIP LOCKED` 는 비관 락 경로가 늘면 선택지.

## P1-5. 락 밖 견적의 임계 구간 재검증

- 맥락: 요금을 락 밖에서 읽어 스냅샷하면 그 사이 요금 변경 시 과거 값으로 고정(06 P1-5, 계획 §3 Q3 "재판정" 부분 미이행).
- 결정: 요금 조회(`rateRepository.findAllInRange`)를 **임계 구간(트랜잭션) 안으로 이동**. 트랜잭션 시점의 요금으로 금액을 산정·스냅샷해 "생성 시점 확정 금액" 불변식을 지킨다. 요금 조회는 가벼워 임계 구간 증가가 미미하다.
- 트레이드오프: 임계 구간 +1 SELECT vs 스냅샷 정확성. 쿠폰 할인은 발급 시점 스냅샷(불변)이라 락 밖 유지.

## 검증

전체 테스트 통과(ktlint + test). 동시성 12+건 + 멱등 2건 + 빠른실패는 타임아웃 설정(부하 시 효과는 운영 측정).
