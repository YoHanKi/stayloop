package com.stayloop.application.property

import com.stayloop.application.property.command.PropertySearchCriteria
import com.stayloop.application.property.command.RoomAvailabilityQuery
import com.stayloop.domain.common.value.Money
import com.stayloop.domain.common.value.PageResult
import com.stayloop.domain.inventory.DailyRoomInventoryRepository
import com.stayloop.domain.property.PropertyImageRepository
import com.stayloop.domain.property.PropertyRepository
import com.stayloop.domain.property.RoomTypeModel
import com.stayloop.domain.property.RoomTypeRepository
import com.stayloop.domain.rate.DailyRoomRateRepository
import com.stayloop.domain.rate.ReservationPriceCalculator
import com.stayloop.domain.reservation.value.StayPeriod
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Property 검색·상세 (시퀀스 1) Facade. (`docs/design/02-sequence-diagram.md §1`)
 *
 * **트랜잭션은 `readOnly = true`** — 검색·상세는 모두 읽기 전용. 쓰기 흐름(WishlistFacade /
 * ReservationFacade) 과 분리.
 *
 * **N+1 제거 (week5 PR3 D-3)** — `search` 가 `propertyRepository.searchInfos(...)` 의 *QueryDSL 2-step batch IN*
 * 만 호출. 기존 N+1 helper (`buildSearchInfoOrNull` / `availableTotalPrice` / `hasAllDatesAvailable`) 는 본 PR
 * 에서 완전 제거. `getDetail` / `getAvailableRooms` 는 *단건 Property 의 객실 상세* 라 N+1 영역 아님.
 */
@Service
class PropertyFacade(
    private val propertyRepository: PropertyRepository,
    private val roomTypeRepository: RoomTypeRepository,
    private val propertyImageRepository: PropertyImageRepository,
    private val inventoryRepository: DailyRoomInventoryRepository,
    private val rateRepository: DailyRoomRateRepository,
    private val priceCalculator: ReservationPriceCalculator,
) {
    /**
     * 도시 + 기간 + 인원 + 정렬 기준 Property 검색. (AC-1, AC-2 + week5 PR1 D-1 / D-6 + PR3 D-3)
     *
     * **흐름** — `propertyRepository.searchInfos(...)` 가 QueryDSL 2-step batch IN 으로 (a) city 매칭 + (b)
     * 가용 RoomType ≥ 1 + (c) 최저 합산가 + (d) 가용 RoomType 수 를 모두 계산. Facade 는 결과 `PropertySearchRow`
     * 를 응답 DTO `PropertySearchInfo` 로 1:1 매핑 (Money 래핑 + lowestPricePerNight 산출).
     *
     * **`page.total`** — Repository 가 *city 매칭 전체 row 수* 로 박제. 가용 0 Property 는 *content 에서만* 제외,
     * total 은 도시 기준 유지 (AC-1 가용성은 일시적 상태).
     */
    @Transactional(readOnly = true)
    fun search(criteria: PropertySearchCriteria): PageResult<PropertySearchInfo> {
        if (criteria.city.isBlank()) {
            throw CoreException(ErrorType.BAD_REQUEST, "도시 코드는 비어 있을 수 없습니다.")
        }
        if (criteria.guestCount <= 0) {
            throw CoreException(ErrorType.BAD_REQUEST, "투숙 인원은 1명 이상이어야 합니다.")
        }

        val rows = propertyRepository.searchInfos(
            criteria.city,
            criteria.period,
            criteria.sortKey,
            criteria.guestCount,
            criteria.page,
        )
        val nights = criteria.period.nights()
        val infos = rows.content.map { row ->
            val lowestTotal = Money.of(row.lowestTotalPrice)
            val lowestPerNight = if (nights > 0) Money.of(row.lowestTotalPrice / nights) else Money.ZERO
            PropertySearchInfo(
                propertyId = row.propertyId,
                name = row.name,
                city = row.city,
                fullAddress = row.fullAddress,
                mainImageUrl = row.mainImageUrl,
                starRating = row.starRating,
                rating = row.rating,
                wishCount = row.wishCount,
                lowestTotalPrice = lowestTotal,
                lowestPricePerNight = lowestPerNight,
                availableRoomTypeCount = row.availableRoomTypeCount,
            )
        }
        return PageResult(content = infos, total = rows.total)
    }

    /**
     * 단일 Property 의 정적 상세. 가용성 / 합산가는 별도 API.
     *
     * **이미지 조회 정책 (PR3, week5-b.md Loop 8'')**: PropertyImage 는 별도 AR — Facade 가 명시 호출. JPA
     * `@OneToMany` 의 *암시적 lazy loading* 보다 *명시적 Repository 호출* 의 추적 / 테스트 가능성 우위.
     */
    @Transactional(readOnly = true)
    fun getDetail(propertyId: Long): PropertyDetailInfo {
        val property = propertyRepository.findById(propertyId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "존재하지 않는 숙소입니다.")
        val roomTypes = roomTypeRepository.findByPropertyId(propertyId)
        val images = propertyImageRepository.findByPropertyId(propertyId)
        return PropertyDetailInfo.of(property, roomTypes, images)
    }

    /**
     * 단일 Property 의 객실 타입별 가용성 + 합산가. 사용자가 기간 + 인원을 지정한 후 호출.
     */
    @Transactional(readOnly = true)
    fun getAvailableRooms(query: RoomAvailabilityQuery): List<RoomTypeAvailabilityInfo> {
        if (query.guestCount <= 0) {
            throw CoreException(ErrorType.BAD_REQUEST, "투숙 인원은 1명 이상이어야 합니다.")
        }
        propertyRepository.findById(query.propertyId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "존재하지 않는 숙소입니다.")
        val roomTypes = roomTypeRepository.findByPropertyId(query.propertyId)
        return roomTypes.map { roomType ->
            roomTypeAvailability(roomType, query.period, query.guestCount)
        }
    }

    /**
     * 객실 타입 단건의 가용성 + 합산가 / 인원 초과 / 일자 누락 / 재고 부족 사유 분기.
     */
    private fun roomTypeAvailability(
        roomType: RoomTypeModel,
        period: StayPeriod,
        guestCount: Int,
    ): RoomTypeAvailabilityInfo {
        if (guestCount > roomType.guestCount.max) {
            return RoomTypeAvailabilityInfo.unavailable(
                roomType,
                "요청 인원($guestCount) 이 객실 최대 인원(${roomType.guestCount.max}) 을 초과합니다.",
            )
        }
        val expectedDates = period.datesToReserve()
        val inventories = inventoryRepository.findAllInRange(roomType.id, period.checkIn, period.checkOut)
        if (!hasAllDatesPresent(inventories.map { it.date }, expectedDates)) {
            return RoomTypeAvailabilityInfo.unavailable(roomType, "기간 내 재고 정보가 누락되었습니다.")
        }
        if (inventories.any { it.available() <= 0 }) {
            return RoomTypeAvailabilityInfo.unavailable(roomType, "기간 내 재고가 부족한 일자가 있습니다.")
        }
        val rates = rateRepository.findAllInRange(roomType.id, period.checkIn, period.checkOut)
        if (!hasAllDatesPresent(rates.map { it.date }, expectedDates)) {
            return RoomTypeAvailabilityInfo.unavailable(roomType, "기간 내 요금 정보가 누락되었습니다.")
        }
        val total = priceCalculator.totalPrice(rates)
        return RoomTypeAvailabilityInfo.available(roomType, total, period.nights())
    }

    private fun hasAllDatesPresent(
        actual: List<java.time.LocalDate>,
        expected: List<java.time.LocalDate>,
    ): Boolean = actual.size == expected.size && actual.toSet() == expected.toSet()
}
