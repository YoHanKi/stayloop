package com.stayloop.application.property.command

import com.stayloop.domain.common.value.PageQuery
import com.stayloop.domain.property.value.PropertySortKey
import com.stayloop.domain.reservation.value.StayPeriod

/**
 * Property 검색 입력 (시퀀스 1). (`docs/design/02-sequence-diagram.md §1`)
 *
 * 입력은 *도메인 어휘만* 통과 — Controller 가 String / Int 를 도메인 VO 로 변환한 후 본 Command 를 만든다.
 *
 * **`sortKey` 본 라운드 정책 (week5 PR1 D-6)**: 4종 모두 활성. Facade 가 거절하지 않고 Repository 가
 * 각 sort 에 맞는 SQL 분기를 발화 — PRICE_ASC 는 `daily_room_rates` JOIN GROUP BY MIN, 그 외는 properties
 * 단일 테이블 `ORDER BY` (V010 의 (city, wish_count DESC) / (city, rating DESC) 인덱스 prefix scan).
 * 미지원 키는 Controller 단의 `PropertyV1Dto.parseSortKey` 가 BAD_REQUEST 로 거절 (silent ignore 차단).
 */
data class PropertySearchCriteria(
    val city: String,
    val period: StayPeriod,
    val guestCount: Int,
    val page: PageQuery,
    val sortKey: PropertySortKey = PropertySortKey.RECOMMENDED,
)
