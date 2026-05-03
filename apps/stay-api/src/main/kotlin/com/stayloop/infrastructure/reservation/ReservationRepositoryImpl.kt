package com.stayloop.infrastructure.reservation

import com.stayloop.domain.reservation.ReservationModel
import com.stayloop.domain.reservation.ReservationRepository
import com.stayloop.domain.reservation.value.StayPeriod
import com.stayloop.domain.user.value.LoginId
import org.springframework.stereotype.Component

/**
 * 도메인 `ReservationRepository` 의 인프라 어댑터. JpaRepository 위임만 — `LoginId` 가 reservation entity 의
 * `@Embedded` 컬럼이므로 Wishlist 와 달리 별도 BIGINT 변환은 필요 없다.
 */
@Component
class ReservationRepositoryImpl(
    private val jpa: ReservationJpaRepository,
) : ReservationRepository {

    override fun save(reservation: ReservationModel): ReservationModel = jpa.save(reservation)

    override fun findById(id: Long): ReservationModel? = jpa.findById(id).orElse(null)

    override fun findByUserId(userId: LoginId, period: StayPeriod): List<ReservationModel> =
        jpa.findByUserIdOverlapping(
            loginIdValue = userId.value,
            periodCheckIn = period.checkIn,
            periodCheckOut = period.checkOut,
        )
}
