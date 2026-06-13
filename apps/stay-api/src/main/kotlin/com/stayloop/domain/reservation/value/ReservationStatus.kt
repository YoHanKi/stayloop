package com.stayloop.domain.reservation.value

/**
 * 예약 상태 머신. 합법 전이만 통과시키고 나머지 조합은 모두 거부한다(03 §8.1).
 * `NO_SHOW` 는 트리거(자정 배치)가 5~6주차지만 enum 값·전이 규칙만 선반영해 후속 마이그레이션을 던다.
 */
enum class ReservationStatus {
    PENDING,
    CONFIRMED,
    CHECKED_IN,
    CHECKED_OUT,
    CANCELLED,
    NO_SHOW,
    ;

    fun canTransitTo(next: ReservationStatus): Boolean = next in TRANSITIONS.getValue(this)

    companion object {
        private val TRANSITIONS: Map<ReservationStatus, Set<ReservationStatus>> = mapOf(
            PENDING to setOf(CONFIRMED, CANCELLED),
            CONFIRMED to setOf(CHECKED_IN, CANCELLED, NO_SHOW),
            CHECKED_IN to setOf(CHECKED_OUT),
            CHECKED_OUT to emptySet(),
            CANCELLED to emptySet(),
            NO_SHOW to emptySet(),
        )
    }
}
