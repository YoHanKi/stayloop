package com.stayloop.application.property

import com.stayloop.application.property.command.PropertySearchCriteria
import com.stayloop.application.property.command.PropertySortKey
import com.stayloop.application.property.command.RoomAvailabilityQuery
import com.stayloop.domain.inventory.DailyRoomInventoryRepository
import com.stayloop.domain.property.PropertyModel
import com.stayloop.domain.property.PropertyRepository
import com.stayloop.domain.property.RoomTypeModel
import com.stayloop.domain.property.RoomTypeRepository
import com.stayloop.domain.rate.DailyRoomRateRepository
import com.stayloop.domain.reservation.ReservationPriceCalculator
import com.stayloop.domain.reservation.value.StayPeriod
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 검색/상세/가용성 유스케이스를 조립한다. 여러 애그리거트를 가로지르는 조회·조합은 도메인 책임 밖이라
 * 여기서 Repository 들을 직접 엮는다(03 §6 — PropertyService 미생성).
 *
 * 검색은 도시 페이지 → 각 숙소의 객실·재고·요금을 개별 조회하는 N+1 구조다. batch / fetch join 으로의
 * 전환은 4주차 쿼리·동시성 라운드의 영역이라 여기서는 의식적으로 단순화한다.
 */
@Service
class PropertyFacade(
    private val propertyRepository: PropertyRepository,
    private val roomTypeRepository: RoomTypeRepository,
    private val inventoryRepository: DailyRoomInventoryRepository,
    private val rateRepository: DailyRoomRateRepository,
    private val priceCalculator: ReservationPriceCalculator,
) {
    @Transactional(readOnly = true)
    fun search(criteria: PropertySearchCriteria): List<PropertySearchInfo> {
        if (criteria.sortKey != PropertySortKey.RECOMMENDED) {
            throw CoreException(ErrorType.BAD_REQUEST, "현재는 추천순(recommended) 정렬만 지원합니다.")
        }
        val period = criteria.period()
        return propertyRepository.findByCity(criteria.city, criteria.page, criteria.size)
            .mapNotNull { property ->
                val available = availableRoomTypes(property.id, period, criteria.guestCount)
                if (available.isEmpty()) {
                    null
                } else {
                    toSearchInfo(property, period, available)
                }
            }
    }

    @Transactional(readOnly = true)
    fun getAvailableRooms(query: RoomAvailabilityQuery): List<RoomTypeAvailabilityInfo> =
        availableRoomTypes(query.propertyId, query.period(), query.guestCount)

    @Transactional(readOnly = true)
    fun getDetail(propertyId: Long): PropertyDetailInfo {
        val property = propertyRepository.findById(propertyId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "존재하지 않는 숙소입니다.")
        val roomTypes = roomTypeRepository.findByPropertyId(propertyId)
        return toDetailInfo(property, roomTypes)
    }

    private fun availableRoomTypes(
        propertyId: Long,
        period: StayPeriod,
        guestCount: Int,
    ): List<RoomTypeAvailabilityInfo> =
        roomTypeRepository.findByPropertyId(propertyId)
            .filter { it.guestCount.canAccommodate(guestCount) }
            .mapNotNull { roomType -> toAvailabilityInfo(roomType, period) }

    /** 기간 전체 재고·요금이 1:1 로 갖춰지고 모든 날짜에 잔여가 있는 객실만 가용으로 본다(AC-2). */
    private fun toAvailabilityInfo(roomType: RoomTypeModel, period: StayPeriod): RoomTypeAvailabilityInfo? {
        val dateCount = period.datesToReserve().size
        val inventories = inventoryRepository.findAllInRange(roomType.id, period.checkIn, period.checkOut)
        val rates = rateRepository.findAllInRange(roomType.id, period.checkIn, period.checkOut)
        if (inventories.size != dateCount || rates.size != dateCount) return null
        if (inventories.any { it.available() <= 0 }) return null

        val total = priceCalculator.totalPrice(rates).amount
        val nights = period.nights()
        return RoomTypeAvailabilityInfo(
            roomTypeId = roomType.id,
            name = roomType.name,
            maxGuests = roomType.guestCount.maxGuests,
            availableRooms = inventories.minOf { it.available() },
            nights = nights,
            totalPrice = total,
            averageNightlyPrice = averageNightly(total, nights),
        )
    }

    private fun toSearchInfo(
        property: PropertyModel,
        period: StayPeriod,
        available: List<RoomTypeAvailabilityInfo>,
    ): PropertySearchInfo {
        val lowest = available.minByOrNull { it.totalPrice }!!
        return PropertySearchInfo(
            propertyId = property.id,
            name = property.name.value,
            category = property.category.name,
            city = property.address.city,
            mainImageUrl = property.mainImageUrl,
            rating = property.rating.value,
            wishCount = property.wishCount,
            nights = period.nights(),
            lowestTotalPrice = lowest.totalPrice,
            averageNightlyPrice = lowest.averageNightlyPrice,
        )
    }

    private fun toDetailInfo(property: PropertyModel, roomTypes: List<RoomTypeModel>): PropertyDetailInfo {
        val policy = property.policy
        return PropertyDetailInfo(
            propertyId = property.id,
            name = property.name.value,
            category = property.category.name,
            description = property.description,
            city = property.address.city,
            roadAddress = property.address.roadAddress,
            amenities = property.amenities.tags.map { it.name },
            checkInTime = policy.checkInTime.toString(),
            checkOutTime = policy.checkOutTime.toString(),
            cancellationType = policy.cancellation.type.name,
            freeUntilDaysBefore = policy.cancellation.freeUntilDaysBefore,
            smokingAllowed = policy.smokingAllowed,
            petAllowed = policy.petAllowed,
            starRating = property.starRating?.value,
            rating = property.rating.value,
            wishCount = property.wishCount,
            mainImageUrl = property.mainImageUrl,
            images = property.images.map { PropertyImageInfo(it.imageUrl, it.isMain) },
            roomTypes = roomTypes.map {
                RoomTypeBriefInfo(
                    roomTypeId = it.id,
                    name = it.name,
                    baseGuests = it.guestCount.baseGuests,
                    maxGuests = it.guestCount.maxGuests,
                    bedConfig = it.bedConfig.beds.mapKeys { (bedType, _) -> bedType.name },
                )
            },
        )
    }

    private fun averageNightly(total: BigDecimal, nights: Long): BigDecimal =
        total.divide(BigDecimal.valueOf(nights), 2, RoundingMode.HALF_UP)
}
