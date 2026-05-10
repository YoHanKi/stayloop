package com.stayloop.infrastructure.inventory

import com.querydsl.jpa.impl.JPAQueryFactory
import com.stayloop.domain.inventory.DailyRoomInventoryId
import com.stayloop.domain.inventory.DailyRoomInventoryModel
import com.stayloop.domain.inventory.DailyRoomInventoryRepository
import com.stayloop.domain.inventory.QDailyRoomInventoryModel
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * 도메인 `DailyRoomInventoryRepository` 의 인프라 어댑터. JpaRepository 위임 + QueryDSL.
 *
 * 자연키 `(roomTypeId, date)` 의 단건 조회는 `JpaRepository.findById(DailyRoomInventoryId(...))` 로 변환한다 —
 * 도메인 인터페이스가 `(roomTypeId, date)` 두 인자를 받는 시그니처를 유지하기 위한 어댑터 책임.
 *
 * **`findAllInRange` 는 QueryDSL** — 반-닫힌 구간 `[from, to)` (`Between` 양 끝 포함과 다름) 와 `date ASC` 정렬을
 * type-safe 경로로 표현. 다일자 락 순서 (`docs/plan/week4.md` ③ Phase A) 와 정합 (verify-code §19-B —
 * 문서 ↔ 가드 정합).
 *
 * **`findInventoriesForUpdate` 는 QueryDSL `setLockMode(PESSIMISTIC_WRITE)`** —
 * 도메인 Repository 가 `@Lock` / `@QueryHints` 같은 Spring Data 어노테이션을 의식하지 않도록
 * 인프라 어댑터에서 락 모드를 일원화. `*JpaRepository` 인터페이스는 `JpaRepository<T, ID>`
 * 만 상속하는 정책 (`.github/instructions/repository.instructions.md`, `decision.md` D-6) 정합.
 */
@Component
class DailyRoomInventoryRepositoryImpl(
    private val jpa: DailyRoomInventoryJpaRepository,
    private val queryFactory: JPAQueryFactory,
) : DailyRoomInventoryRepository {
    override fun findById(roomTypeId: Long, date: LocalDate): DailyRoomInventoryModel? =
        jpa.findById(DailyRoomInventoryId(roomTypeId, date)).orElse(null)

    override fun findAllInRange(
        roomTypeId: Long,
        from: LocalDate,
        to: LocalDate,
    ): List<DailyRoomInventoryModel> {
        val d = QDailyRoomInventoryModel.dailyRoomInventoryModel
        return queryFactory
            .selectFrom(d)
            .where(
                d.roomTypeId.eq(roomTypeId),
                d.date.goe(from),
                d.date.lt(to),
            )
            .orderBy(d.date.asc())
            .fetch()
    }

    override fun findInventoriesForUpdate(
        roomTypeId: Long,
        dates: List<LocalDate>,
    ): List<DailyRoomInventoryModel> {
        if (dates.isEmpty()) return emptyList()
        val d = QDailyRoomInventoryModel.dailyRoomInventoryModel
        return queryFactory
            .selectFrom(d)
            .where(
                d.roomTypeId.eq(roomTypeId),
                d.date.`in`(dates),
            )
            .orderBy(d.date.asc())
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .fetch()
    }

    override fun saveAll(inventories: Collection<DailyRoomInventoryModel>): List<DailyRoomInventoryModel> =
        jpa.saveAll(inventories).toList()

    override fun save(inventory: DailyRoomInventoryModel): DailyRoomInventoryModel = jpa.save(inventory)
}
