package com.stayloop.support.test

import com.stayloop.domain.BaseEntity
import com.stayloop.domain.reservation.ReservationModel
import com.stayloop.domain.reservation.ReservationRepository
import com.stayloop.domain.user.value.LoginId

/**
 * 운영 어댑터와 동치 의미론의 더블 — `checkIn DESC, id DESC` 정렬, [BaseEntity.id] reflection 할당.
 */
class InMemoryReservationRepository : ReservationRepository {
    private val store = LinkedHashMap<Long, ReservationModel>()
    private var sequence = 0L

    override fun save(reservation: ReservationModel): ReservationModel {
        if (reservation.id == 0L) {
            assignId(reservation, ++sequence)
        }
        store[reservation.id] = reservation
        return reservation
    }

    override fun findById(id: Long): ReservationModel? = store[id]

    // POJO 더블은 단일 스레드라 락이 불필요 — 운영의 FOR UPDATE 와 관측 동치(같은 행 반환).
    override fun findByIdForUpdate(id: Long): ReservationModel? = store[id]

    override fun findByIdempotencyKey(idempotencyKey: String): ReservationModel? =
        store.values.firstOrNull { it.idempotencyKey == idempotencyKey }

    override fun findByUserId(loginId: LoginId, page: Int, size: Int): List<ReservationModel> =
        store.values
            .filter { it.userId == loginId }
            .sortedWith(compareByDescending<ReservationModel> { it.period.checkIn }.thenByDescending { it.id })
            .drop(page * size)
            .take(size)

    private fun assignId(entity: BaseEntity, id: Long) {
        val field = BaseEntity::class.java.getDeclaredField("id")
        field.isAccessible = true
        field.setLong(entity, id)
    }
}
