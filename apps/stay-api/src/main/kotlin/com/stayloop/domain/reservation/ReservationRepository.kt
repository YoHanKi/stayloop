package com.stayloop.domain.reservation

import com.stayloop.domain.reservation.value.StayPeriod
import com.stayloop.domain.user.value.LoginId

/**
 * Reservation 의 도메인 Repository 인터페이스. (`docs/design/03-class-diagram.md §4`,
 * `docs/plan/week2-3.md §⑦`)
 * 구현체는 `infrastructure/reservation/ReservationRepositoryImpl` (Spring Data 위임).
 *
 * **boundary 는 `LoginId`** — 호출자(Facade) 는 `LoginId` 만 들고 다닌다. 본 도메인은 `userId` 가
 * `users.id` BIGINT 가 아니라 `LoginId` 자체로 박제되므로(ReservationModel.userId: LoginId), Wishlist 와 달리
 * Impl 안에서 `LoginId ↔ users.id` 변환은 필요 없다 — boundary 와 entity 의 타입이 일치한다.
 *
 * **`findByUserId(userId, period)` 의 overlap 의미론** — `reservation.checkIn < period.checkOut`
 * AND `reservation.checkOut > period.checkIn` 인 예약. "이 기간 동안의 내 예약" 자연어와 정합.
 * 정렬은 `period.checkIn DESC, id DESC` 고정 — tie-breaker 로 `id` 추가 (verify-code §4 결정성 룰).
 */
interface ReservationRepository {
    /**
     * 신규 예약 저장 또는 변경된 예약 갱신.
     */
    fun save(reservation: ReservationModel): ReservationModel

    /**
     * `id` 단건 조회. 없으면 null.
     */
    fun findById(id: Long): ReservationModel?

    /**
     * 사용자의 예약 중 `period` 기간과 *겹치는* 예약 목록.
     * 정렬: `period.checkIn DESC, id DESC` 고정 (안정적 tie-breaker).
     */
    fun findByUserId(userId: LoginId, period: StayPeriod): List<ReservationModel>
}
