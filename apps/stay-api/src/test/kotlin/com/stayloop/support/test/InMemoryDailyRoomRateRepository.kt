package com.stayloop.support.test

import com.stayloop.domain.rate.DailyRoomRateId
import com.stayloop.domain.rate.DailyRoomRateModel
import com.stayloop.domain.rate.DailyRoomRateRepository
import java.time.LocalDate

class InMemoryDailyRoomRateRepository : DailyRoomRateRepository {
    private val store = LinkedHashMap<DailyRoomRateId, DailyRoomRateModel>()

    override fun findById(roomTypeId: Long, date: LocalDate): DailyRoomRateModel? =
        store[DailyRoomRateId(roomTypeId, date)]

    override fun findAllInRange(roomTypeId: Long, from: LocalDate, to: LocalDate): List<DailyRoomRateModel> =
        store.values
            .filter { it.roomTypeId == roomTypeId && !it.date.isBefore(from) && it.date.isBefore(to) }
            .sortedBy { it.date }

    override fun save(rate: DailyRoomRateModel): DailyRoomRateModel {
        store[DailyRoomRateId(rate.roomTypeId, rate.date)] = rate
        return rate
    }

    override fun saveAll(rates: List<DailyRoomRateModel>): List<DailyRoomRateModel> = rates.map { save(it) }
}
