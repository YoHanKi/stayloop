package com.stayloop.infrastructure.rate

import com.stayloop.domain.rate.DailyRoomRateId
import com.stayloop.domain.rate.DailyRoomRateModel
import com.stayloop.domain.rate.DailyRoomRateRepository
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * 도메인 `DailyRoomRateRepository` 의 인프라 어댑터. JpaRepository 위임만.
 *
 * 자연키 `(roomTypeId, date)` 의 단건 조회는 `JpaRepository.findById(DailyRoomRateId(...))` 로 변환한다 —
 * 도메인 인터페이스가 `(roomTypeId, date)` 두 인자를 받는 시그니처를 유지하기 위한 어댑터 책임.
 */
@Component
class DailyRoomRateRepositoryImpl(
    private val jpa: DailyRoomRateJpaRepository,
) : DailyRoomRateRepository {
    override fun findById(roomTypeId: Long, date: LocalDate): DailyRoomRateModel? =
        jpa.findById(DailyRoomRateId(roomTypeId, date)).orElse(null)

    override fun findAllInRange(
        roomTypeId: Long,
        from: LocalDate,
        to: LocalDate,
    ): List<DailyRoomRateModel> = jpa.findAllInRange(roomTypeId, from, to)

    override fun saveAll(rates: Collection<DailyRoomRateModel>): List<DailyRoomRateModel> =
        jpa.saveAll(rates).toList()

    override fun save(rate: DailyRoomRateModel): DailyRoomRateModel = jpa.save(rate)
}
