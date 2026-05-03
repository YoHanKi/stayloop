package com.stayloop.domain.reservation.value

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ReservationStatusTest {

    /**
     * 6×6 합법 전이 매트릭스 — 36 케이스 전수 검증. 매트릭스가 KDoc 의 표와 *문자열 그대로 같은 의미*
     * (verify-code §19-B — 문서-가드 정합).
     *
     * `null` = 합법, `Pair(from, to)` = 매트릭스 셀.
     */
    private val expectedAllowed: Set<Pair<ReservationStatus, ReservationStatus>> = setOf(
        ReservationStatus.PENDING to ReservationStatus.CONFIRMED,
        ReservationStatus.PENDING to ReservationStatus.CANCELLED,
        ReservationStatus.CONFIRMED to ReservationStatus.CHECKED_IN,
        ReservationStatus.CONFIRMED to ReservationStatus.CANCELLED,
        ReservationStatus.CONFIRMED to ReservationStatus.NO_SHOW,
        ReservationStatus.CHECKED_IN to ReservationStatus.CHECKED_OUT,
    )

    @DisplayName("6×6 전이 매트릭스 — 합법 전이 6건만 true, 나머지 30건은 모두 false (자기 자신 포함).")
    @Test
    fun shouldEnforceAllowedTransitionMatrix() {
        ReservationStatus.entries.forEach { from ->
            ReservationStatus.entries.forEach { to ->
                val expected = (from to to) in expectedAllowed
                val actual = from.canTransitTo(to)
                assertThat(actual)
                    .withFailMessage(
                        "전이 매트릭스 위반: %s → %s 가 expected=%s 여야 하지만 actual=%s",
                        from,
                        to,
                        expected,
                        actual,
                    )
                    .isEqualTo(expected)
            }
        }
    }

    @DisplayName("자기 자신으로의 전이는 모든 상태에서 거절 (no-op 차단) — 6건 모두 false.")
    @Test
    fun shouldRejectSelfTransition() {
        ReservationStatus.entries.forEach { status ->
            assertThat(status.canTransitTo(status))
                .withFailMessage("%s → %s 자기 전이가 허용되어 있다", status, status)
                .isFalse()
        }
    }

    @DisplayName("terminal 상태(CHECKED_OUT / CANCELLED / NO_SHOW) 는 어떤 전이도 false — 재처리 차단.")
    @Test
    fun shouldRejectAllTransitions_fromTerminalStates() {
        val terminals = setOf(
            ReservationStatus.CHECKED_OUT,
            ReservationStatus.CANCELLED,
            ReservationStatus.NO_SHOW,
        )

        terminals.forEach { terminal ->
            ReservationStatus.entries.forEach { next ->
                assertThat(terminal.canTransitTo(next))
                    .withFailMessage(
                        "terminal %s → %s 전이가 허용되어 있다 (terminal 은 모든 전이가 거절되어야 함)",
                        terminal,
                        next,
                    )
                    .isFalse()
            }
        }
    }

    @DisplayName("PENDING → CHECKED_IN 차단 — 결제(CONFIRMED) 단계를 건너뛴 입실은 거절.")
    @Test
    fun shouldReject_pendingToCheckedIn() {
        assertThat(ReservationStatus.PENDING.canTransitTo(ReservationStatus.CHECKED_IN)).isFalse()
    }

    @DisplayName("CHECKED_IN → CANCELLED 차단 — 입실 후 취소는 환불 정책 동반(05 §8.2), 본 라운드 거절.")
    @Test
    fun shouldReject_checkedInToCancelled() {
        assertThat(ReservationStatus.CHECKED_IN.canTransitTo(ReservationStatus.CANCELLED)).isFalse()
    }
}
