package com.stayloop.infrastructure.rate

import com.querydsl.jpa.impl.JPAQueryFactory
import com.stayloop.domain.rate.DailyRoomRateId
import com.stayloop.domain.rate.DailyRoomRateModel
import com.stayloop.domain.rate.DailyRoomRateRepository
import com.stayloop.domain.rate.QDailyRoomRateModel
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * 도메인 `DailyRoomRateRepository` 의 인프라 어댑터. JpaRepository 위임 + QueryDSL.
 *
 * 자연키 `(roomTypeId, date)` 의 단건 조회는 `JpaRepository.findById(DailyRoomRateId(...))` 로 변환한다 —
 * 도메인 인터페이스가 `(roomTypeId, date)` 두 인자를 받는 시그니처를 유지하기 위한 어댑터 책임.
 *
 * **`findAllInRange` 는 QueryDSL** — 반-닫힌 구간 `[from, to)` 와 `date ASC` 정렬을 type-safe 경로로 표현
 * (Inventory 와 동일 의미론, verify-code §19-B).
 */
@Component
class DailyRoomRateRepositoryImpl(
    private val jpa: DailyRoomRateJpaRepository,
    private val queryFactory: JPAQueryFactory,
) : DailyRoomRateRepository {
    override fun findById(roomTypeId: Long, date: LocalDate): DailyRoomRateModel? =
        jpa.findById(DailyRoomRateId(roomTypeId, date)).orElse(null)

    override fun findAllInRange(
        roomTypeId: Long,
        from: LocalDate,
        to: LocalDate,
    ): List<DailyRoomRateModel> {
        val r = QDailyRoomRateModel.dailyRoomRateModel
        return queryFactory
            .selectFrom(r)
            .where(
                r.roomTypeId.eq(roomTypeId),
                r.date.goe(from),
                r.date.lt(to),
            )
            .orderBy(r.date.asc())
            .fetch()
    }

    override fun saveAll(rates: Collection<DailyRoomRateModel>): List<DailyRoomRateModel> =
        jpa.saveAll(rates).toList()

    override fun save(rate: DailyRoomRateModel): DailyRoomRateModel = jpa.save(rate)
}
