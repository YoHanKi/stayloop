package com.stayloop.infrastructure.reservation

import com.querydsl.jpa.impl.JPAQueryFactory
import com.stayloop.domain.reservation.QReservationModel
import com.stayloop.domain.reservation.ReservationModel
import com.stayloop.domain.reservation.ReservationRepository
import com.stayloop.domain.reservation.value.StayPeriod
import com.stayloop.domain.user.value.LoginId
import org.springframework.stereotype.Component

/**
 * 도메인 `ReservationRepository` 의 인프라 어댑터. JpaRepository 위임 + QueryDSL.
 *
 * `LoginId` 가 reservation entity 의 `@Embedded` 컬럼이므로 Wishlist 와 달리 별도 BIGINT 변환은 필요 없다 —
 * QueryDSL 도 `r.userId.value.eq(...)` 로 자연스럽게 표현.
 *
 * **`findByUserId(period)` 의 overlap 의미론** — `r.checkIn < period.checkOut` AND `r.checkOut > period.checkIn`.
 * 정렬: `period.checkIn DESC, id DESC` (tie-breaker — verify-code §4 — 결정성).
 */
@Component
class ReservationRepositoryImpl(
    private val jpa: ReservationJpaRepository,
    private val queryFactory: JPAQueryFactory,
) : ReservationRepository {

    override fun save(reservation: ReservationModel): ReservationModel = jpa.save(reservation)

    override fun findById(id: Long): ReservationModel? = jpa.findById(id).orElse(null)

    override fun findByUserId(userId: LoginId, period: StayPeriod): List<ReservationModel> {
        val r = QReservationModel.reservationModel
        return queryFactory
            .selectFrom(r)
            .where(
                r.userId.value.eq(userId.value),
                r.period.checkIn.lt(period.checkOut),
                r.period.checkOut.gt(period.checkIn),
            )
            .orderBy(r.period.checkIn.desc(), r.id.desc())
            .fetch()
    }
}
