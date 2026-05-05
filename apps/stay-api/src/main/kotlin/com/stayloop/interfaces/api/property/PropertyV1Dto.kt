package com.stayloop.interfaces.api.property

import com.stayloop.application.property.PropertyDetailInfo
import com.stayloop.application.property.PropertyImageInfo
import com.stayloop.application.property.PropertySearchInfo
import com.stayloop.application.property.RoomTypeAvailabilityInfo
import com.stayloop.application.property.RoomTypeInfo
import com.stayloop.application.property.command.PropertySearchCriteria
import com.stayloop.application.property.command.PropertySortKey
import com.stayloop.application.property.command.RoomAvailabilityQuery
import com.stayloop.domain.common.value.PageQuery
import com.stayloop.domain.common.value.PageResult
import com.stayloop.domain.property.value.BedType
import com.stayloop.domain.property.value.PropertyCategory
import com.stayloop.domain.reservation.value.StayPeriod
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import java.time.LocalDate
import java.time.LocalTime

class PropertyV1Dto {
    data class SearchRequest(
        val city: String,
        val checkIn: LocalDate,
        val checkOut: LocalDate,
        val guests: Int,
        val page: Int,
        val size: Int,
        val sort: String?,
    ) {
        fun toCriteria(): PropertySearchCriteria = PropertySearchCriteria(
            city = city,
            period = StayPeriod(checkIn, checkOut),
            guestCount = guests,
            page = PageQuery(page = page, size = size),
            sortKey = parseSortKey(sort),
        )

        private fun parseSortKey(raw: String?): PropertySortKey {
            if (raw.isNullOrBlank()) return PropertySortKey.RECOMMENDED
            return runCatching { PropertySortKey.valueOf(raw.uppercase()) }.getOrElse {
                throw CoreException(
                    ErrorType.BAD_REQUEST,
                    "지원하지 않는 정렬 키입니다: $raw (지원: ${PropertySortKey.entries.joinToString(", ")})",
                )
            }
        }
    }

    data class AvailabilityRequest(
        val checkIn: LocalDate,
        val checkOut: LocalDate,
        val guests: Int,
    ) {
        fun toQuery(propertyId: Long): RoomAvailabilityQuery = RoomAvailabilityQuery(
            propertyId = propertyId,
            period = StayPeriod(checkIn, checkOut),
            guestCount = guests,
        )
    }

    data class SearchPageResponse(
        val content: List<PropertySummary>,
        val total: Long,
    ) {
        companion object {
            fun from(page: PageResult<PropertySearchInfo>): SearchPageResponse = SearchPageResponse(
                content = page.content.map(PropertySummary::from),
                total = page.total,
            )
        }
    }

    data class PropertySummary(
        val propertyId: Long,
        val name: String,
        val city: String,
        val fullAddress: String,
        val mainImageUrl: String?,
        val starRating: Int?,
        val rating: Double,
        val wishCount: Int,
        val lowestTotalPrice: Long,
        val lowestPricePerNight: Long,
        val availableRoomTypeCount: Int,
    ) {
        companion object {
            fun from(info: PropertySearchInfo): PropertySummary = PropertySummary(
                propertyId = info.propertyId,
                name = info.name,
                city = info.city,
                fullAddress = info.fullAddress,
                mainImageUrl = info.mainImageUrl,
                starRating = info.starRating,
                rating = info.rating,
                wishCount = info.wishCount,
                lowestTotalPrice = info.lowestTotalPrice.amount,
                lowestPricePerNight = info.lowestPricePerNight.amount,
                availableRoomTypeCount = info.availableRoomTypeCount,
            )
        }
    }

    data class PropertyDetailResponse(
        val propertyId: Long,
        val name: String,
        val category: PropertyCategory,
        val description: String?,
        val city: String,
        val fullAddress: String,
        val mainImageUrl: String?,
        val starRating: Int?,
        val rating: Double,
        val wishCount: Int,
        val amenities: List<String>,
        val checkInTime: LocalTime,
        val checkOutTime: LocalTime,
        val cancellationType: String,
        val freeUntilDaysBefore: Int,
        val smokingAllowed: Boolean,
        val petAllowed: Boolean,
        val images: List<PropertyImageResponse>,
        val roomTypes: List<RoomTypeResponse>,
    ) {
        companion object {
            fun from(info: PropertyDetailInfo): PropertyDetailResponse = PropertyDetailResponse(
                propertyId = info.propertyId,
                name = info.name,
                category = info.category,
                description = info.description,
                city = info.city,
                fullAddress = info.fullAddress,
                mainImageUrl = info.mainImageUrl,
                starRating = info.starRating,
                rating = info.rating,
                wishCount = info.wishCount,
                amenities = info.amenities.map { it.name },
                checkInTime = info.checkInTime,
                checkOutTime = info.checkOutTime,
                cancellationType = info.cancellationPolicy.cancellation.type.name,
                freeUntilDaysBefore = info.cancellationPolicy.cancellation.freeUntilDaysBefore,
                smokingAllowed = info.cancellationPolicy.smokingAllowed,
                petAllowed = info.cancellationPolicy.petAllowed,
                images = info.images.map(PropertyImageResponse::from),
                roomTypes = info.roomTypes.map(RoomTypeResponse::from),
            )
        }
    }

    data class PropertyImageResponse(
        val imageUrl: String,
        val altText: String?,
        val displayOrder: Int,
        val isMain: Boolean,
    ) {
        companion object {
            fun from(image: PropertyImageInfo): PropertyImageResponse = PropertyImageResponse(
                imageUrl = image.imageUrl,
                altText = image.altText,
                displayOrder = image.displayOrder,
                isMain = image.isMain,
            )
        }
    }

    data class RoomTypeResponse(
        val roomTypeId: Long,
        val name: String,
        val baseGuests: Int,
        val maxGuests: Int,
        val beds: Map<BedType, Int>,
    ) {
        companion object {
            fun from(info: RoomTypeInfo): RoomTypeResponse = RoomTypeResponse(
                roomTypeId = info.roomTypeId,
                name = info.name,
                baseGuests = info.baseGuests,
                maxGuests = info.maxGuests,
                beds = info.beds,
            )
        }
    }

    data class RoomAvailabilityResponse(
        val roomTypeId: Long,
        val name: String,
        val baseGuests: Int,
        val maxGuests: Int,
        val beds: Map<BedType, Int>,
        val available: Boolean,
        val totalPrice: Long?,
        val pricePerNight: Long?,
        val unavailableReason: String?,
    ) {
        companion object {
            fun from(info: RoomTypeAvailabilityInfo): RoomAvailabilityResponse = RoomAvailabilityResponse(
                roomTypeId = info.roomTypeId,
                name = info.name,
                baseGuests = info.baseGuests,
                maxGuests = info.maxGuests,
                beds = info.beds,
                available = info.available,
                totalPrice = info.totalPrice?.amount,
                pricePerNight = info.pricePerNight?.amount,
                unavailableReason = info.unavailableReason,
            )
        }
    }
}
