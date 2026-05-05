package com.stayloop.application.property

import com.stayloop.domain.common.value.Money
import com.stayloop.domain.property.PropertyModel

/**
 * 검색 결과 한 건. (`docs/design/02-sequence-diagram.md §1`)
 *
 * 가용 객실 0 인 Property 는 검색 결과에서 제외(AC-2)되므로, 본 Info 는 *최소 1개 가용 객실이 있는 Property*
 * 만 표현한다. `lowestTotalPrice` 는 가용 객실 중 합산가 최저, `lowestPricePerNight` 는 그 객실의 1박 평균.
 */
data class PropertySearchInfo(
    val propertyId: Long,
    val name: String,
    val city: String,
    val fullAddress: String,
    val mainImageUrl: String?,
    val starRating: Int?,
    val rating: Double,
    val wishCount: Int,
    val lowestTotalPrice: Money,
    val lowestPricePerNight: Money,
    val availableRoomTypeCount: Int,
) {
    companion object {
        fun of(
            property: PropertyModel,
            lowestTotalPrice: Money,
            lowestPricePerNight: Money,
            availableRoomTypeCount: Int,
        ): PropertySearchInfo = PropertySearchInfo(
            propertyId = property.id,
            name = property.name.value,
            city = property.address.city,
            fullAddress = property.address.fullAddress,
            mainImageUrl = property.mainImageUrl,
            starRating = property.starRating?.value,
            rating = property.rating.value,
            wishCount = property.wishCount,
            lowestTotalPrice = lowestTotalPrice,
            lowestPricePerNight = lowestPricePerNight,
            availableRoomTypeCount = availableRoomTypeCount,
        )
    }
}
