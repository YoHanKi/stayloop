package com.stayloop.domain.property

/**
 * Property 검색 결과 한 건의 *projection row*. (`docs/plan/week5.md` PR3 D-3)
 *
 * **위치**: `domain/property/` — Repository 의 출력 계약 (반환 타입). plain Kotlin data class (JPA 어노테이션 누출 0).
 *
 * **분담**:
 * - **본 row (domain)**: SQL projection 결과의 primitive 표현 — Money / VO 미사용.
 * - **`PropertySearchInfo` (application)**: 도메인 어휘로 감싼 응답 — Money 타입 / 비즈니스 의미.
 *
 * **컬럼 의미**:
 * - [propertyId] / [name] / [city] / [fullAddress] — properties 의 정적 컬럼.
 * - [mainImageUrl] — null 허용 (이미지 미등록 Property 의 캐시).
 * - [starRating] — null 허용 (등급 미설정 숙소).
 * - [rating] — decimal(3,2) → Double.
 * - [wishCount] — 비정규화 (D-2, week4 답습).
 * - [lowestTotalPrice] — 가용 RoomType 중 *기간 합산 최저가* (원). `MIN(SUM(rate.price_per_night))` 결과.
 *   inventory + rate 가 *모든 일자에 존재 + available > 0* + `max_guests >= guestCount` 인 RoomType 만 후보.
 * - [availableRoomTypeCount] — 위 조건을 만족하는 가용 RoomType 의 수.
 *
 * **null 정합** — `lowestTotalPrice` / `availableRoomTypeCount` 는 *가용 RoomType ≥ 1 Property* 만 본 row 로
 * 생성됨 (가용 0 Property 는 검색 결과 제외, AC-2).
 */
data class PropertySearchRow(
    val propertyId: Long,
    val name: String,
    val city: String,
    val fullAddress: String,
    val mainImageUrl: String?,
    val starRating: Int?,
    val rating: Double,
    val wishCount: Int,
    val lowestTotalPrice: Long,
    val availableRoomTypeCount: Int,
)
