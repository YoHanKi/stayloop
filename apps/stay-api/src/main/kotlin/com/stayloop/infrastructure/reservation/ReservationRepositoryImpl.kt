package com.stayloop.infrastructure.reservation

import com.stayloop.domain.reservation.ReservationModel
import com.stayloop.domain.reservation.ReservationRepository
import com.stayloop.domain.user.value.LoginId
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component

@Component
class ReservationRepositoryImpl(
    private val reservationJpaRepository: ReservationJpaRepository,
) : ReservationRepository {
    override fun save(reservation: ReservationModel): ReservationModel = reservationJpaRepository.save(reservation)

    override fun findById(id: Long): ReservationModel? = reservationJpaRepository.findById(id).orElse(null)

    override fun findByUserId(loginId: LoginId, page: Int, size: Int): List<ReservationModel> =
        reservationJpaRepository.findByUserLoginId(loginId.value, PageRequest.of(page, size))
}
