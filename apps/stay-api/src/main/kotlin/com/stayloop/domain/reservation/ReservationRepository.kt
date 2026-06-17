package com.stayloop.domain.reservation

import com.stayloop.domain.user.value.LoginId

interface ReservationRepository {
    fun save(reservation: ReservationModel): ReservationModel

    fun findById(id: Long): ReservationModel?

    /**
     * 예약 row 를 비관적 쓰기 락으로 잠그고 조회한다(취소 경로). 동시 취소가 같은 예약을 두 번 복원하지 않도록,
     * 둘째 트랜잭션이 락 해제 후 **신선한** 상태(CANCELLED)를 읽어 상태 전이에서 거르게 한다. 단일 row·저경합이라
     * 비관 락 비용이 작다.
     */
    fun findByIdForUpdate(id: Long): ReservationModel?

    /** 멱등 키로 기존 예약을 찾는다(중복 요청 재응답용). 없으면 null. */
    fun findByIdempotencyKey(idempotencyKey: String): ReservationModel?

    /** 사용자의 예약 목록을 체크인 내림차순(같으면 id 내림차순)으로 페이지 조회한다. */
    fun findByUserId(loginId: LoginId, page: Int, size: Int): List<ReservationModel>
}
