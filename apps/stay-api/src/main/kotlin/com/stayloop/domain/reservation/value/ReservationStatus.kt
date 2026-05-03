package com.stayloop.domain.reservation.value

/**
 * 예약 상태 머신. (`docs/design/03-class-diagram.md §4`, `docs/design/05-domain-landscape.md §8.1`,
 * `docs/plan/week2-3.md §⑦`)
 *
 * 6 상태 — `PENDING / CONFIRMED / CHECKED_IN / CHECKED_OUT / CANCELLED / NO_SHOW`.
 *
 * **합법 전이 매트릭스:**
 *
 * | from \ to     | PENDING | CONFIRMED | CHECKED_IN | CHECKED_OUT | CANCELLED | NO_SHOW |
 * |---------------|:-------:|:---------:|:----------:|:-----------:|:---------:|:-------:|
 * | PENDING       |    -    |     ✓     |      -     |      -      |     ✓     |    -    |
 * | CONFIRMED     |    -    |     -     |      ✓     |      -      |     ✓     |    ✓    |
 * | CHECKED_IN    |    -    |     -     |      -     |      ✓      |     -     |    -    |
 * | CHECKED_OUT   |    -    |     -     |      -     |      -      |     -     |    -    |  (terminal)
 * | CANCELLED     |    -    |     -     |      -     |      -      |     -     |    -    |  (terminal)
 * | NO_SHOW       |    -    |     -     |      -     |      -      |     -     |    -    |  (terminal)
 *
 * 의식적으로 빼는 전이:
 * - `PENDING → CHECKED_IN` — 결제(`CONFIRMED`) 를 거치지 않은 입실 차단. 결제 합류 전 본 라운드는 enum 자리만.
 * - `CONFIRMED → PENDING` — 결제 후 PG 환불·실패 보상은 결제 합류 시점(5주차+) 에 별도 보상 흐름으로
 *   설계 (`docs/design/05 §2.6 / §8.2`). 본 라운드는 단순화.
 * - `CHECKED_IN → CANCELLED` — 입실 후 취소는 환불 정책 동반 (`docs/design/05 §8.2`). 본 라운드 거절.
 * - terminal 상태 (CHECKED_OUT / CANCELLED / NO_SHOW) 에서의 어떤 전이도 불가 — 잘못된 재처리 방지.
 *
 * **`NO_SHOW` 트리거**: 체크인 일자 자정 후 배치로 `CONFIRMED → NO_SHOW` 전이는 5~6주차 영역. 본 라운드는
 * enum 값 + 전이 규칙 자리만 (`docs/plan/week2-3.md §⑦` 결정).
 */
enum class ReservationStatus {
    PENDING,
    CONFIRMED,
    CHECKED_IN,
    CHECKED_OUT,
    CANCELLED,
    NO_SHOW,
    ;

    /**
     * 본 상태에서 `next` 상태로의 전이가 합법인지 여부. terminal 상태(`CHECKED_OUT`/`CANCELLED`/`NO_SHOW`)
     * 는 어떤 전이도 false 를 반환한다.
     */
    fun canTransitTo(next: ReservationStatus): Boolean = next in allowedNext()

    private fun allowedNext(): Set<ReservationStatus> = when (this) {
        PENDING -> setOf(CONFIRMED, CANCELLED)
        CONFIRMED -> setOf(CHECKED_IN, CANCELLED, NO_SHOW)
        CHECKED_IN -> setOf(CHECKED_OUT)
        CHECKED_OUT, CANCELLED, NO_SHOW -> emptySet()
    }
}
