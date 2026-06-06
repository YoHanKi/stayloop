package com.stayloop.domain.rate

import java.time.LocalDate

interface DailyRoomRateRepository {
    fun findById(roomTypeId: Long, date: LocalDate): DailyRoomRateModel?

    /** `[from, to)` 반-닫힌 구간(체크아웃 당일 제외)의 요금을 날짜 오름차순으로 조회한다. */
    fun findAllInRange(roomTypeId: Long, from: LocalDate, to: LocalDate): List<DailyRoomRateModel>

    fun save(rate: DailyRoomRateModel): DailyRoomRateModel

    fun saveAll(rates: List<DailyRoomRateModel>): List<DailyRoomRateModel>
}
