package com.stayloop.domain.reservation

import com.stayloop.domain.user.value.LoginId

interface ReservationRepository {
    fun save(reservation: ReservationModel): ReservationModel

    fun findById(id: Long): ReservationModel?

    /** 사용자의 예약 목록을 체크인 내림차순(같으면 id 내림차순)으로 페이지 조회한다. */
    fun findByUserId(loginId: LoginId, page: Int, size: Int): List<ReservationModel>
}
