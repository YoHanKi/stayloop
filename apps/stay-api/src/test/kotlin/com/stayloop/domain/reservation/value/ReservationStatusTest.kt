package com.stayloop.domain.reservation.value

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ReservationStatusTest {
    private val allowed: Map<ReservationStatus, Set<ReservationStatus>> = mapOf(
        ReservationStatus.PENDING to setOf(ReservationStatus.CONFIRMED, ReservationStatus.CANCELLED),
        ReservationStatus.CONFIRMED to setOf(ReservationStatus.CHECKED_IN, ReservationStatus.CANCELLED, ReservationStatus.NO_SHOW),
        ReservationStatus.CHECKED_IN to setOf(ReservationStatus.CHECKED_OUT),
        ReservationStatus.CHECKED_OUT to emptySet(),
        ReservationStatus.CANCELLED to emptySet(),
        ReservationStatus.NO_SHOW to emptySet(),
    )

    @DisplayName("6×6 모든 상태 전이 조합 중 허용 전이만 통과하고 나머지는 모두 거부된다.")
    @Test
    fun shouldAllowOnlyDefinedTransitions() {
        for (from in ReservationStatus.entries) {
            for (to in ReservationStatus.entries) {
                val expected = to in allowed.getValue(from)

                assertThat(from.canTransitTo(to))
                    .`as`("$from -> $to")
                    .isEqualTo(expected)
            }
        }
    }
}
