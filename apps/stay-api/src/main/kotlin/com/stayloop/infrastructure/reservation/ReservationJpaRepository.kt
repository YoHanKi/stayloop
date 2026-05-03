package com.stayloop.infrastructure.reservation

import com.stayloop.domain.reservation.ReservationModel
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

/**
 * Spring Data JPA 인터페이스. 도메인 `ReservationRepository` 와 분리되어 있고
 * `RepositoryImpl` 이 위임한다 (3-class 분리).
 *
 * **boundary 차이**: 도메인 인터페이스는 `LoginId` / `StayPeriod` 를 받지만, 본 JPA 인터페이스는
 * `loginIdValue: String` / `periodCheckIn: LocalDate` / `periodCheckOut: LocalDate` 의 풀어진 인자를 받는다 —
 * Spring Data 가 `@Embedded` 컬럼을 자연스럽게 다루도록 한다.
 *
 * **overlap 매칭**: `reservation.checkIn < periodCheckOut` AND `reservation.checkOut > periodCheckIn`.
 * 정렬: `period.checkIn DESC, id DESC` (tie-breaker — verify-code §4).
 */
interface ReservationJpaRepository : JpaRepository<ReservationModel, Long> {

    @Query(
        "SELECT r FROM ReservationModel r " +
            "WHERE r.userId.value = :loginIdValue " +
            "  AND r.period.checkIn < :periodCheckOut " +
            "  AND r.period.checkOut > :periodCheckIn " +
            "ORDER BY r.period.checkIn DESC, r.id DESC",
    )
    fun findByUserIdOverlapping(
        @Param("loginIdValue") loginIdValue: String,
        @Param("periodCheckIn") periodCheckIn: LocalDate,
        @Param("periodCheckOut") periodCheckOut: LocalDate,
    ): List<ReservationModel>
}
