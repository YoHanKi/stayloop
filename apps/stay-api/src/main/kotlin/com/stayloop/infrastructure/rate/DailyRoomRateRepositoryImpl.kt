package com.stayloop.infrastructure.rate

import com.stayloop.domain.rate.DailyRoomRateId
import com.stayloop.domain.rate.DailyRoomRateModel
import com.stayloop.domain.rate.DailyRoomRateRepository
import org.springframework.stereotype.Component
import java.time.LocalDate

@Component
class DailyRoomRateRepositoryImpl(
    private val jpaRepository: DailyRoomRateJpaRepository,
) : DailyRoomRateRepository {
    override fun findById(roomTypeId: Long, date: LocalDate): DailyRoomRateModel? =
        jpaRepository.findById(DailyRoomRateId(roomTypeId, date)).orElse(null)

    override fun findAllInRange(roomTypeId: Long, from: LocalDate, to: LocalDate): List<DailyRoomRateModel> =
        jpaRepository.findInRange(roomTypeId, from, to)

    override fun save(rate: DailyRoomRateModel): DailyRoomRateModel = jpaRepository.save(rate)

    override fun saveAll(rates: List<DailyRoomRateModel>): List<DailyRoomRateModel> = jpaRepository.saveAll(rates)
}
