package com.stayloop.infrastructure.inventory

import com.stayloop.domain.inventory.DailyRoomInventoryId
import com.stayloop.domain.inventory.DailyRoomInventoryModel
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

/**
 * Spring Data JPA 인터페이스. 도메인 `DailyRoomInventoryRepository` 와 분리되어 있고
 * `RepositoryImpl` 이 위임한다 (`docs/plan/week2-3.md` 의 3-class 분리).
 *
 * `JpaRepository<DailyRoomInventoryModel, DailyRoomInventoryId>` — 두 번째 타입 매개변수에 `@IdClass` 사용.
 */
interface DailyRoomInventoryJpaRepository : JpaRepository<DailyRoomInventoryModel, DailyRoomInventoryId> {

    /**
     * 반-닫힌 구간 `[from, to)`. `Between` 은 양 끝 포함이라 부적합 → `@Query` 명시.
     * 정렬은 `date ASC` — 일자별 결정성 보장.
     */
    @Query(
        "SELECT d FROM DailyRoomInventoryModel d " +
            "WHERE d.roomTypeId = :roomTypeId AND d.date >= :from AND d.date < :to " +
            "ORDER BY d.date ASC",
    )
    fun findAllInRange(
        @Param("roomTypeId") roomTypeId: Long,
        @Param("from") from: LocalDate,
        @Param("to") to: LocalDate,
    ): List<DailyRoomInventoryModel>
}
