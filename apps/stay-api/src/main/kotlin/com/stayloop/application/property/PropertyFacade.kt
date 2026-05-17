package com.stayloop.application.property

import com.stayloop.application.property.command.PropertySearchCriteria
import com.stayloop.application.property.command.RoomAvailabilityQuery
import com.stayloop.domain.property.value.PropertySortKey
import com.stayloop.domain.common.value.Money
import com.stayloop.domain.common.value.PageResult
import com.stayloop.domain.inventory.DailyRoomInventoryModel
import com.stayloop.domain.inventory.DailyRoomInventoryRepository
import com.stayloop.domain.property.PropertyImageRepository
import com.stayloop.domain.property.PropertyModel
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
 * Property 검색·상세 (시퀀스 1) Facade. (`docs/design/02-sequence-diagram.md §1`,
 * `docs/plan/week2-3.md §⑧ Phase A`)
 *
 * **트랜잭션은 `readOnly = true`** — 검색·상세는 모두 읽기 전용. 쓰기 흐름(WishlistFacade /
 * ReservationFacade) 과 분리.
 *
 * **N+1 영역**: `search` 가 도시별 Property 페이지를 조회한 후 *각 Property 의 RoomType / Inventory / Rate*
 * 를 개별 호출로 묶는다. 본 라운드는 의식적으로 단순화하고, **4주차에 batch / fetch join 으로 전환**
 * (`docs/plan/week2-3.md §⑧ — N+1 영역 명시 주석`, week4 인계 항목).
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
     * 도시 + 기간 + 인원 + 정렬 기준 Property 검색. (AC-1, AC-2 + week5 PR1 D-1 / D-6)
     *
     * 흐름:
     * 1. `propertyRepository.search(city, period, sortKey, page)` — 도시 기준 + sort 적용 Property 페이지.
     *    - 비 PRICE_ASC: page.size 만큼 fetch.
     *    - PRICE_ASC: K = page.size × 3 candidate overfetch (`PropertyRepositoryImpl` 박제).
     * 2. 각 Property → N+1 으로 RoomType / Inventory / Rate (현 PR3 projection 합류 전).
     * 3. 가용성 판정 — 모든 일자 inventory 존재 + `available > 0` + `maxGuests >= guestCount`.
     * 4. 가용 객실 중 *최저 합산가* 선택 (AC-2 "최저가").
     * 5. **가용 객실 0 Property 는 결과 제외** (AC-2). PRICE_ASC 는 K overfetch 후 *page.size 만큼 take* —
     *    부족 시 그대로 반환 (week5-b.md decompose-decision Q4 δ 박제, imperfect pagination 한계 week6+ 인계).
     * 6. **`page.total` = *도시 매칭 행 수*** (모든 sort 공통) — 가용 0 제외는 결과 content 에만 반영, total
     *    의미는 도시 기준 유지 (AC-1 가용성은 일시적 상태).
     */
    @Transactional(readOnly = true)
    fun search(criteria: PropertySearchCriteria): PageResult<PropertySearchInfo> {
        if (criteria.city.isBlank()) {
            throw CoreException(ErrorType.BAD_REQUEST, "도시 코드는 비어 있을 수 없습니다.")
        }
        if (criteria.guestCount <= 0) {
            throw CoreException(ErrorType.BAD_REQUEST, "투숙 인원은 1명 이상이어야 합니다.")
        }

        val cityPage = propertyRepository.search(
            criteria.city,
            criteria.period,
            criteria.sortKey,
            criteria.page,
        )
        val filtered = cityPage.content.mapNotNull { property ->
            buildSearchInfoOrNull(property, criteria.period, criteria.guestCount)
        }
        // PRICE_ASC 는 Repository 가 K = page.size × 3 candidates 를 반환하므로 가용성 필터 후 page.size 만큼 take.
        // 다른 sort 는 Repository 가 이미 page.size 로 limit — take 가 no-op 이지만 일관성 위해 적용.
        val infos = if (criteria.sortKey == PropertySortKey.PRICE_ASC) {
            filtered.take(criteria.page.size)
        } else {
            filtered
        }
        return PageResult(content = infos, total = cityPage.total)
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
     * Property 단위로 *최저 합산가 가용 객실* 을 찾고 InfoOrNull 반환. 가용 객실 0 → null (검색 결과 제외).
     */
    private fun buildSearchInfoOrNull(
        property: PropertyModel,
        period: StayPeriod,
        guestCount: Int,
    ): PropertySearchInfo? {
        val roomTypes = roomTypeRepository.findByPropertyId(property.id)
        val availableTotals = roomTypes.mapNotNull { roomType ->
            availableTotalPrice(roomType, period, guestCount)
        }
        val lowestTotal = availableTotals.minByOrNull { it.amount } ?: return null
        val nights = period.nights()
        val lowestPerNight = if (nights > 0) Money.of(lowestTotal.amount / nights) else Money.ZERO
        return PropertySearchInfo.of(
            property = property,
            lowestTotalPrice = lowestTotal,
            lowestPricePerNight = lowestPerNight,
            availableRoomTypeCount = availableTotals.size,
        )
    }

    /**
     * 객실 타입의 `(period, guestCount)` 조합이 가용한 경우 합산가, 아니면 null.
     * 가용 조건: maxGuests ≥ guestCount + 모든 일자 inventory 존재 + available > 0 + 모든 일자 rate 존재.
     */
    private fun availableTotalPrice(
        roomType: RoomTypeModel,
        period: StayPeriod,
        guestCount: Int,
    ): Money? {
        if (guestCount > roomType.guestCount.max) return null
        val expectedDates = period.datesToReserve()
        val inventories = inventoryRepository.findAllInRange(roomType.id, period.checkIn, period.checkOut)
        if (!hasAllDatesAvailable(inventories, expectedDates)) return null
        val rates = rateRepository.findAllInRange(roomType.id, period.checkIn, period.checkOut)
        if (rates.size != expectedDates.size) return null
        if (rates.map { it.date }.toSet() != expectedDates.toSet()) return null
        return priceCalculator.totalPrice(rates)
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

    private fun hasAllDatesAvailable(
        inventories: List<DailyRoomInventoryModel>,
        expectedDates: List<java.time.LocalDate>,
    ): Boolean {
        if (inventories.size != expectedDates.size) return false
        val dates = inventories.map { it.date }.toSet()
        if (dates != expectedDates.toSet()) return false
        return inventories.all { it.available() > 0 }
    }

    private fun hasAllDatesPresent(
        actual: List<java.time.LocalDate>,
        expected: List<java.time.LocalDate>,
    ): Boolean = actual.size == expected.size && actual.toSet() == expected.toSet()
}
