package com.stayloop.domain.reservation.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 체크인 ~ 체크아웃 기간 VO. (`docs/design/03-class-diagram.md §4`, `docs/plan/week2-3.md §⑦`)
 *
 * **`data class` 채택** — Kotlin `value class` 는 단일 프로퍼티만 가질 수 있어 두 `LocalDate` 를 합칠 수 없다
 * (`docs/design/03 §4` 결정).
 *
 * 도메인 규칙:
 * - `checkOut` 은 `checkIn` 보다 *반드시 뒤* — 0박 / 음수 박 거절 (1박 이상 보장)
 * - **`datesToReserve()` 는 체크아웃 당일 제외** — 반-닫힌 구간 `[checkIn, checkOut)`. AC-3 의 정합 (체크아웃
 *   당일은 재고 차감 / 요금 합산 모두 제외). Inventory / Rate Repository 의 `findAllInRange(from, to)` 와
 *   동일 의미론.
 *
 * 의식적으로 미루는 검증:
 * - 미래 일자 제한 (예: 1년 이내) → Reservation Facade 책임 (비즈니스 정책)
 * - 과거 일자 거절 (이미 지난 날짜로 예약 차단) → Facade 가 `Clock` 으로 비교
 * - 너무 긴 stay (예: 30박 초과) → Facade 정책
 *
 * 도메인 VO 는 *형식적 일관성* (체크아웃 > 체크인) 만 보장. 시간/Clock 의존 비즈니스 룰은 호출자 책임.
 */
@Embeddable
data class StayPeriod(
    @Column(name = "check_in", nullable = false)
    val checkIn: LocalDate,
    @Column(name = "check_out", nullable = false)
    val checkOut: LocalDate,
) {
    init {
        if (!checkOut.isAfter(checkIn)) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "체크아웃 일자는 체크인 일자보다 뒤여야 합니다 (1박 이상 필요).",
            )
        }
    }

    /**
     * 박수 — `checkIn` 부터 `checkOut` 까지의 일 수 차이. 1박 이상 (`init` 가드).
     */
    fun nights(): Int = ChronoUnit.DAYS.between(checkIn, checkOut).toInt()

    /**
     * 재고 차감 / 요금 합산 대상 일자 목록. **체크아웃 당일 제외** (`[checkIn, checkOut)`).
     * 1박이면 1개, N박이면 N개.
     */
    fun datesToReserve(): List<LocalDate> =
        (0 until nights()).map { checkIn.plusDays(it.toLong()) }
}
