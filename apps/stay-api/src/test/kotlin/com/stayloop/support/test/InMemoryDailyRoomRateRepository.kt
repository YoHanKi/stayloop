package com.stayloop.support.test

import com.stayloop.domain.rate.DailyRoomRateId
import com.stayloop.domain.rate.DailyRoomRateModel
import com.stayloop.domain.rate.DailyRoomRateRepository
import java.time.LocalDate

/**
 * 테스트용 InMemory `DailyRoomRateRepository`. 운영 RepositoryImpl 의 의미론과 동치 —
 * **반-닫힌 구간 `[from, to)`** / **자연키 일관성** / **`saveAll` 단건 upsert** / **정렬 date ASC**
 * 모두 운영과 같게 동작 (verify-code §19-A 운영-테스트 동치성).
 */
class InMemoryDailyRoomRateRepository : DailyRoomRateRepository {
    private val store = mutableMapOf<DailyRoomRateId, DailyRoomRateModel>()

    override fun findById(roomTypeId: Long, date: LocalDate): DailyRoomRateModel? =
        store[DailyRoomRateId(roomTypeId, date)]

    override fun findAllInRange(
        roomTypeId: Long,
        from: LocalDate,
        to: LocalDate,
    ): List<DailyRoomRateModel> =
        store.values
            .filter { it.roomTypeId == roomTypeId && !it.date.isBefore(from) && it.date.isBefore(to) }
            .sortedBy { it.date }

    override fun saveAll(rates: Collection<DailyRoomRateModel>): List<DailyRoomRateModel> =
        rates.map { save(it) }

    override fun save(rate: DailyRoomRateModel): DailyRoomRateModel {
        store[DailyRoomRateId(rate.roomTypeId, rate.date)] = rate
        return rate
    }
}
