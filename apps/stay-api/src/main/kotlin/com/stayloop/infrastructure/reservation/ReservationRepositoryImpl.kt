package com.stayloop.infrastructure.reservation

import com.querydsl.jpa.impl.JPAQueryFactory
import com.stayloop.domain.reservation.QReservationModel
import com.stayloop.domain.reservation.ReservationModel
import com.stayloop.domain.reservation.ReservationRepository
import com.stayloop.domain.user.value.LoginId
import org.springframework.stereotype.Component

@Component
class ReservationRepositoryImpl(
    private val reservationJpaRepository: ReservationJpaRepository,
    private val queryFactory: JPAQueryFactory,
) : ReservationRepository {
    private val reservation = QReservationModel.reservationModel

    override fun save(reservation: ReservationModel): ReservationModel = reservationJpaRepository.save(reservation)

    override fun findById(id: Long): ReservationModel? = reservationJpaRepository.findById(id).orElse(null)

    override fun findByUserId(loginId: LoginId, page: Int, size: Int): List<ReservationModel> =
        queryFactory
            .selectFrom(reservation)
            .where(reservation.userId.value.eq(loginId.value))
            .orderBy(reservation.period.checkIn.desc(), reservation.id.desc())
            .offset(page.toLong() * size)
            .limit(size.toLong())
            .fetch()
}
