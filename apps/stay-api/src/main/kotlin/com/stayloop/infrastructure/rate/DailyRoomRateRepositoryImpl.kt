package com.stayloop.infrastructure.rate

import com.querydsl.jpa.impl.JPAQueryFactory
import com.stayloop.domain.rate.DailyRoomRateId
import com.stayloop.domain.rate.DailyRoomRateModel
import com.stayloop.domain.rate.DailyRoomRateRepository
import com.stayloop.domain.rate.QDailyRoomRateModel
import org.springframework.stereotype.Component
import java.time.LocalDate

@Component
class DailyRoomRateRepositoryImpl(
    private val jpaRepository: DailyRoomRateJpaRepository,
    private val queryFactory: JPAQueryFactory,
) : DailyRoomRateRepository {
    private val rate = QDailyRoomRateModel.dailyRoomRateModel

    override fun findById(roomTypeId: Long, date: LocalDate): DailyRoomRateModel? =
        jpaRepository.findById(DailyRoomRateId(roomTypeId, date)).orElse(null)

    override fun findAllInRange(roomTypeId: Long, from: LocalDate, to: LocalDate): List<DailyRoomRateModel> =
        queryFactory
            .selectFrom(rate)
            .where(
                rate.roomTypeId.eq(roomTypeId),
                rate.date.goe(from),
                rate.date.lt(to),
            )
            .orderBy(rate.date.asc())
            .fetch()

    override fun save(rate: DailyRoomRateModel): DailyRoomRateModel = jpaRepository.save(rate)

    override fun saveAll(rates: List<DailyRoomRateModel>): List<DailyRoomRateModel> = jpaRepository.saveAll(rates)
}
