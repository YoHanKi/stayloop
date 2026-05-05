package com.stayloop.application.property.command

import com.stayloop.domain.reservation.value.StayPeriod

/**
 * 단일 Property 의 객실 타입별 가용성 조회 입력. (`docs/design/02-sequence-diagram.md §1`)
 *
 * 사용 시점: 상세 페이지에서 사용자가 기간/인원을 지정한 뒤 "지금 가능한 객실" 을 묻는 흐름.
 */
data class RoomAvailabilityQuery(
    val propertyId: Long,
    val period: StayPeriod,
    val guestCount: Int,
)
