package com.stayloop.interfaces.api.property

import com.stayloop.application.property.PropertyDetailInfo
import com.stayloop.application.property.PropertySearchInfo
import com.stayloop.application.property.RoomTypeAvailabilityInfo
import java.math.BigDecimal

class PropertyV1Dto {
    data class SearchResponse(
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
    ) {
        companion object {
            fun from(info: PropertySearchInfo): SearchResponse =
                SearchResponse(
                    propertyId = info.propertyId,
                    name = info.name,
                    category = info.category,
                    city = info.city,
                    mainImageUrl = info.mainImageUrl,
                    rating = info.rating,
                    wishCount = info.wishCount,
                    nights = info.nights,
                    lowestTotalPrice = info.lowestTotalPrice,
                    averageNightlyPrice = info.averageNightlyPrice,
                )
        }
    }

    data class RoomAvailabilityResponse(
        val roomTypeId: Long,
        val name: String,
        val maxGuests: Int,
        val availableRooms: Int,
        val nights: Long,
        val totalPrice: BigDecimal,
        val averageNightlyPrice: BigDecimal,
    ) {
        companion object {
            fun from(info: RoomTypeAvailabilityInfo): RoomAvailabilityResponse =
                RoomAvailabilityResponse(
                    roomTypeId = info.roomTypeId,
                    name = info.name,
                    maxGuests = info.maxGuests,
                    availableRooms = info.availableRooms,
                    nights = info.nights,
                    totalPrice = info.totalPrice,
                    averageNightlyPrice = info.averageNightlyPrice,
                )
        }
    }

    data class DetailResponse(
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
        val images: List<ImageResponse>,
        val roomTypes: List<RoomTypeResponse>,
    ) {
        data class ImageResponse(val imageUrl: String, val isMain: Boolean)

        data class RoomTypeResponse(
            val roomTypeId: Long,
            val name: String,
            val baseGuests: Int,
            val maxGuests: Int,
            val bedConfig: Map<String, Int>,
        )

        companion object {
            fun from(info: PropertyDetailInfo): DetailResponse =
                DetailResponse(
                    propertyId = info.propertyId,
                    name = info.name,
                    category = info.category,
                    description = info.description,
                    city = info.city,
                    roadAddress = info.roadAddress,
                    amenities = info.amenities,
                    checkInTime = info.checkInTime,
                    checkOutTime = info.checkOutTime,
                    cancellationType = info.cancellationType,
                    freeUntilDaysBefore = info.freeUntilDaysBefore,
                    smokingAllowed = info.smokingAllowed,
                    petAllowed = info.petAllowed,
                    starRating = info.starRating,
                    rating = info.rating,
                    wishCount = info.wishCount,
                    mainImageUrl = info.mainImageUrl,
                    images = info.images.map { ImageResponse(it.imageUrl, it.isMain) },
                    roomTypes = info.roomTypes.map {
                        RoomTypeResponse(it.roomTypeId, it.name, it.baseGuests, it.maxGuests, it.bedConfig)
                    },
                )
        }
    }
}
