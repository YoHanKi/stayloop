package com.stayloop.support.test

import com.stayloop.domain.BaseEntity
import com.stayloop.domain.reservation.ReservationModel
import com.stayloop.domain.reservation.ReservationRepository
import com.stayloop.domain.reservation.value.StayPeriod
import com.stayloop.domain.user.value.LoginId

/**
 * 테스트용 InMemory `ReservationRepository`. 운영 RepositoryImpl 의 의미론과 **동일 정책** —
 * - **`findByUserId(userId, period)` overlap 의미** — `r.checkIn < period.checkOut` AND `r.checkOut > period.checkIn`
 * - 정렬: `period.checkIn DESC, id DESC` (tie-breaker — verify-code §4)
 *
 * 운영-테스트 동치성 (verify-code §19-A) — 더블이 운영보다 단순화하면 회귀 사각지대.
 *
 * id 자동 할당은 `BaseEntity::class.java.getDeclaredField("id")` reflection — `InMemoryUserRepository` 와 동일
 * 패턴. 테스트 fake 한정으로 의식적으로 받아들인 트레이드오프(`.github/copilot-instructions.md` 수용된 트레이드오프).
 */
class InMemoryReservationRepository : ReservationRepository {
    private val store = mutableMapOf<Long, ReservationModel>()
    private var sequence = 0L

    override fun save(reservation: ReservationModel): ReservationModel {
        if (reservation.id == 0L) {
            assignId(reservation, ++sequence)
        }
        store[reservation.id] = reservation
        return reservation
    }

    override fun findById(id: Long): ReservationModel? = store[id]

    override fun findByUserId(userId: LoginId, period: StayPeriod): List<ReservationModel> =
        store.values
            .filter { it.userId == userId }
            .filter { it.period.checkIn.isBefore(period.checkOut) && it.period.checkOut.isAfter(period.checkIn) }
            .sortedWith(
                compareByDescending<ReservationModel> { it.period.checkIn }
                    .thenByDescending { it.id },
            )

    private fun assignId(reservation: ReservationModel, id: Long) {
        val field = BaseEntity::class.java.getDeclaredField("id")
        field.isAccessible = true
        field.setLong(reservation, id)
    }
}
