package com.stayloop.application.reservation.command

import com.stayloop.domain.reservation.value.GuestInfo
import com.stayloop.domain.reservation.value.StayPeriod
import com.stayloop.domain.user.value.LoginId

/**
 * 예약 생성 Command. (`docs/design/02-sequence-diagram.md §2`, `docs/plan/week2-3.md §⑧ Phase C`)
 *
 * **`userId` 는 `LoginId`** — 본인 자원 인가가 헤더의 LoginId 로 결정되는 흐름이라 도메인 boundary 와 일관.
 * Property/RoomType 박제 VO 는 Facade 가 *영속화된* PropertyModel/RoomTypeModel 을 조회한 뒤 만든다 — Command
 * 단계에서는 만들지 않는다 (입력 검증이 끝난 *영속 데이터* 가 박제 대상이므로).
 */
data class ReserveCommand(
    val userId: LoginId,
    val propertyId: Long,
    val roomTypeId: Long,
    val period: StayPeriod,
    val guestCount: Int,
    val guest: GuestInfo,
    /**
     * 적용할 쿠폰의 발급 인스턴스 id. **null = 쿠폰 미적용** (`docs/plan/week4.md` ② Commit 3,
     * `docs/plan/week4/decision.md` D-3 / D-4).
     *
     * 본인 자원 인가 (`couponIssue.userId == this.userId`) / 만료 / 사용 여부 검증은 모두 `ReservationFacade.reserve`
     * 의 책임 — Command 자체는 *원시 입력 박제* 위치.
     */
    val couponId: Long? = null,
)
