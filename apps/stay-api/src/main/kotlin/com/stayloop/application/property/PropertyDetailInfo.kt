package com.stayloop.application.property

import com.stayloop.domain.property.PropertyImageModel
import com.stayloop.domain.property.PropertyModel
import com.stayloop.domain.property.RoomTypeModel
import com.stayloop.domain.property.value.AmenityTag
import com.stayloop.domain.property.value.BedType
import com.stayloop.domain.property.value.PropertyCategory
import com.stayloop.domain.property.value.PropertyPolicy
import java.time.LocalTime

/**
 * Property 상세 응답. (`docs/design/02-sequence-diagram.md §1`)
 *
 * 가용성 / 합산가는 별도 API (`getAvailableRooms`) — 본 Info 는 Property 정적 정보 + 객실 타입 목록만.
 */
data class PropertyDetailInfo(
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
    val amenities: Set<AmenityTag>,
    val checkInTime: LocalTime,
    val checkOutTime: LocalTime,
    val cancellationPolicy: PropertyPolicy,
    val images: List<PropertyImageInfo>,
    val roomTypes: List<RoomTypeInfo>,
) {
    companion object {
        fun of(property: PropertyModel, roomTypes: List<RoomTypeModel>): PropertyDetailInfo =
            PropertyDetailInfo(
                propertyId = property.id,
                name = property.name.value,
                category = property.category,
                description = property.description,
                city = property.address.city,
                fullAddress = property.address.fullAddress,
                mainImageUrl = property.mainImageUrl,
                starRating = property.starRating?.value,
                rating = property.rating.value,
                wishCount = property.wishCount,
                amenities = property.amenities.tags,
                checkInTime = property.policy.checkInTime,
                checkOutTime = property.policy.checkOutTime,
                cancellationPolicy = property.policy,
                images = property.images.map(PropertyImageInfo::from),
                roomTypes = roomTypes.map(RoomTypeInfo::from),
            )
    }
}

data class PropertyImageInfo(
    val imageUrl: String,
    val altText: String?,
    val displayOrder: Int,
    val isMain: Boolean,
) {
    companion object {
        fun from(image: PropertyImageModel): PropertyImageInfo = PropertyImageInfo(
            imageUrl = image.imageUrl,
            altText = image.altText,
            displayOrder = image.displayOrder,
            isMain = image.isMain,
        )
    }
}

data class RoomTypeInfo(
    val roomTypeId: Long,
    val name: String,
    val baseGuests: Int,
    val maxGuests: Int,
    val beds: Map<BedType, Int>,
) {
    companion object {
        fun from(roomType: RoomTypeModel): RoomTypeInfo = RoomTypeInfo(
            roomTypeId = roomType.id,
            name = roomType.name.value,
            baseGuests = roomType.guestCount.base,
            maxGuests = roomType.guestCount.max,
            beds = roomType.bedConfig.beds,
        )
    }
}
