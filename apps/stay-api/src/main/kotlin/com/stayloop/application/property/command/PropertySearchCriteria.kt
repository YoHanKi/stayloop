package com.stayloop.application.property.command

import com.stayloop.domain.common.value.PageQuery
import com.stayloop.domain.reservation.value.StayPeriod

/**
 * Property 검색 입력 (시퀀스 1). (`docs/design/02-sequence-diagram.md §1`, `docs/plan/week2-3.md §⑧ Phase A`)
 *
 * 입력은 *도메인 어휘만* 통과 — Controller 가 String / Int 를 도메인 VO 로 변환한 후 본 Command 를 만든다.
 *
 * **`sort` 본 라운드 정책**: `recommended` 만 placeholder 로 받아들이고, `price_asc` / `rating_desc` /
 * `wishes_desc` 는 P1 으로 미룸 (`docs/plan/week2-3.md §⑧` *의식적으로 빼는 것*). silent ignore 가 아니라
 * 명시적으로 enum 으로 제한하여, 미지원 키는 Facade 가 BAD_REQUEST 로 거절.
 */
data class PropertySearchCriteria(
    val city: String,
    val period: StayPeriod,
    val guestCount: Int,
    val page: PageQuery,
    val sortKey: PropertySortKey = PropertySortKey.RECOMMENDED,
)

enum class PropertySortKey {
    RECOMMENDED,
    PRICE_ASC,
    RATING_DESC,
    WISHES_DESC,
}
