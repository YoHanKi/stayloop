# 운영 견고성 2 — 핫키 admission control · 재시도 정교화 · 관찰성

week4 교차 검토(06) 백로그 중 P1-4, P1-2, P2-1 과 P2-2·P2-3 결론 기록.

## P1-4. 핫키 admission control

- 맥락: 단일 핫키(인기 객실·선착순 쿠폰) 폭주 시 락 대기열이 HikariCP 커넥션을 점유해 무관한 요청까지 막는 연쇄 장애(06 P1-4, chunk1 k6 에서 풀 고갈 관찰).
- 결정: `HotKeyGuard`(per-key Semaphore, 기본 64/key, `stayloop.admission.max-concurrent-per-key`)로 키별 동시 실행을 제한하고, 한도 초과는 **DB 닿기 전 429(TOO_MANY_REQUESTS)** 로 즉시 거절. 적용: 예약 생성(`reserve:rt:{roomTypeId}`), 선착순 발급(`coupon:tpl:{templateId}`). `ErrorType.TOO_MANY_REQUESTS` 추가(기존 advice 가 status 매핑).
- 성격: 인스턴스 로컬(분산 아님). 단일 MySQL row lock 이 정합을 책임지므로 분산 락 불필요, 본 가드는 각 인스턴스의 핫키 유입만 제한(04-a 미룬 빚의 1차 방어선).
- 테스트: `HotKeyGuardTest`(한도 초과 429·키 독립·permit 반환). 동시성 통합 테스트는 한도(64) > 스레드 수라 영향 없음.
- 트레이드오프: 일부 요청 조기 거절(가용성 체감 일부↓) vs 연쇄 장애 차단(전체 보호). 한계: 키별 세마포어 맵이 누적(만료 캐시는 후속). 활성화/튜닝 임계는 06 §4 SLO 표(Hikari pending 30s↑, acquire p95 100ms↑).

## P1-2 / P2-1. 재시도 정교화 + 관찰성

- 맥락: 재시도가 평면(3회·고정 지터)이고 발생이 silent — 운영에서 락 경합을 알 수 없음(06 P1-2/P2-1).
- 결정:
  - **지수 백오프 + 지터**: `BACKOFF_BASE * 2^(n-1) + jitter`(20→40→… + 0~20ms). 데드락 victim·락 타임아웃(`PessimisticLockingFailureException` 계열)에 적용, 조건부 0행(매진·소진)은 CoreException 이라 재시도 안 됨(그대로).
  - **관찰성**: 재시도 발생·소진을 `WARN` 로그로 남겨 silent retry 를 가시화. HTTP 결과(성공/CONFLICT/429)는 actuator `http.server.requests` 가 status 별로 이미 집계하므로 별도 카운터를 두지 않고, 내부 재시도만 로그로 메운다.
- 트레이드오프: 실패 원인별(데드락 vs 락 타임아웃) 분기는 지수 백오프+트랜잭션 타임아웃(P1-3)으로 사실상 흡수돼 추가 분기는 보류(과한 instanceof 회피). Micrometer 카운터(대시보드용)는 retry 로그 위에 얹는 후속.
- 테스트: 재시도 자체는 기존 동시성 테스트가 경합을 통과시키며 간접 검증(데드락 강제 주입 테스트는 후속).

## P2-2. INSERT IGNORE 한계 — 평가 후 유지

- 지적: `INSERT IGNORE` 가 중복키 외 오류(절단·NULL·FK)도 경고로 낮춤(06 P2-2).
- 결론: **유지**. 찜 테이블 입력은 검증된 Long·서버 생성 `wished_at` 뿐이라 현실적 위반은 중복키 1종이고, `INSERT IGNORE` 는 그 경우 영향 행 수(1/0)를 깔끔히 준다. 대안(plain INSERT + DuplicateKeyException catch)은 네이티브 제약 위반이 트랜잭션·세션을 오염시킬 위험이, `ON DUPLICATE KEY UPDATE` 는 영향 행 수 의미(1/2)가 모호해 더 나쁘다. 평가 후 의도적으로 유지(MySQL 전용 가정).

## P2-3. 쿠폰 USED 확정/환불(HELD/TTL) — 보류(의존성)

- 결제·환불 흐름이 없는 이번 라운드에선 의미가 없다. `HELD → USED → RESTORED/EXPIRED` + hold TTL 은 결제 합류(week5~6)에서 설계. 현재는 예약 생성 커밋 시 USED, 취소 시 AVAILABLE 복원(멱등)으로 충분.

## 검증

전체 테스트 통과(ktlint + test). `HotKeyGuardTest` 3건 + 기존 동시성·멱등 테스트 회귀 통과.
