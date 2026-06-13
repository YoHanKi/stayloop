package com.stayloop.application.property

import java.math.BigDecimal

/**
 * 검색 결과 한 건 — 가용 객실이 있는 숙소와 그 최저 기간 합산가(AC-1)·1박 평균가.
 */
data class PropertySearchInfo(
    val propertyId: Long,
    val name: String,
    val category: String,
    val city: String,
    val mainImageUrl: String?,
    val rating: BigDecimal,
    val wishCount: Int,
    val nights: Long,
    val lowestTotalPrice: BigDecimal,
    val averageNightlyPrice: BigDecimal,
)

/**
 * 특정 객실 타입의 기간 가용성 — 기간 중 최소 잔여 객실 수와 기간 합산가·1박 평균가.
 */
data class RoomTypeAvailabilityInfo(
    val roomTypeId: Long,
    val name: String,
    val maxGuests: Int,
    val availableRooms: Int,
    val nights: Long,
    val totalPrice: BigDecimal,
    val averageNightlyPrice: BigDecimal,
)

/**
 * 숙소 상세 — 정적 정보(이름·정책·편의시설·이미지)와 객실 타입 목록.
 */
data class PropertyDetailInfo(
    val propertyId: Long,
    val name: String,
    val category: String,
    val description: String?,
    val city: String,
    val roadAddress: String,
    val amenities: List<String>,
    val checkInTime: String,
    val checkOutTime: String,
    val cancellationType: String,
    val freeUntilDaysBefore: Int?,
    val smokingAllowed: Boolean,
    val petAllowed: Boolean,
    val starRating: Int?,
    val rating: BigDecimal,
    val wishCount: Int,
    val mainImageUrl: String?,
    val images: List<PropertyImageInfo>,
    val roomTypes: List<RoomTypeBriefInfo>,
)

data class PropertyImageInfo(
    val imageUrl: String,
    val isMain: Boolean,
)

data class RoomTypeBriefInfo(
    val roomTypeId: Long,
    val name: String,
    val baseGuests: Int,
    val maxGuests: Int,
    val bedConfig: Map<String, Int>,
)
