package com.stayloop.domain.reservation.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 투숙 기간 `[checkIn, checkOut)` 반-닫힌 구간. 생성 시 체크아웃이 체크인보다 뒤임을 강제하고,
 * 날짜 계산을 캡슐화해 호출부의 복붙·순서 오용을 막는다(value class 는 프로퍼티가 둘이라 불가, 03 §4).
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
            throw CoreException(ErrorType.BAD_REQUEST, "체크아웃은 체크인보다 뒤 날짜여야 합니다.")
        }
    }

    fun nights(): Long = ChronoUnit.DAYS.between(checkIn, checkOut)

    /** 재고를 차감할 날짜 목록 — 체크인부터 체크아웃 전날까지(체크아웃 당일 제외, AC-3). */
    fun datesToReserve(): List<LocalDate> =
        generateSequence(checkIn) { it.plusDays(1) }
            .takeWhile { it.isBefore(checkOut) }
            .toList()
}
