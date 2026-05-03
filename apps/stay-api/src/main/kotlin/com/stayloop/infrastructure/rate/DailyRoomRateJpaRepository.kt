package com.stayloop.infrastructure.rate

import com.stayloop.domain.rate.DailyRoomRateId
import com.stayloop.domain.rate.DailyRoomRateModel
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

/**
 * Spring Data JPA 인터페이스. 도메인 `DailyRoomRateRepository` 와 분리되어 있고
 * `RepositoryImpl` 이 위임한다 (3-class 분리).
 *
 * `JpaRepository<DailyRoomRateModel, DailyRoomRateId>` — 두 번째 타입 매개변수에 `@IdClass` 사용.
 */
interface DailyRoomRateJpaRepository : JpaRepository<DailyRoomRateModel, DailyRoomRateId> {

    /**
     * 반-닫힌 구간 `[from, to)`. `Between` 은 양 끝 포함이라 부적합 → `@Query` 명시.
     * 정렬은 `date ASC` — 일자별 결정성 보장.
     */
    @Query(
        "SELECT r FROM DailyRoomRateModel r " +
            "WHERE r.roomTypeId = :roomTypeId AND r.date >= :from AND r.date < :to " +
            "ORDER BY r.date ASC",
    )
    fun findAllInRange(
        @Param("roomTypeId") roomTypeId: Long,
        @Param("from") from: LocalDate,
        @Param("to") to: LocalDate,
    ): List<DailyRoomRateModel>
}
