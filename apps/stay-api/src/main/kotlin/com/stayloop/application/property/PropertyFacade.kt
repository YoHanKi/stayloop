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
import com.stayloop.domain.reservation.value.StayPeriod
import com.stayloop.infrastructure.cache.AvailabilityCacheStore
import com.stayloop.infrastructure.cache.CacheStore
import com.stayloop.infrastructure.cache.RoomDailyAvailability
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.LocalDate

/**
 * Property 검색·상세 Facade (readOnly TX). 쓰기 흐름 (WishlistFacade / ReservationFacade) 과 분리.
 * `search` 의 N+1 제거 = `searchInfos` 의 QueryDSL 2-step batch IN (D-3).
 */
@Service
class PropertyFacade(
    private val propertyRepository: PropertyRepository,
    private val roomTypeRepository: RoomTypeRepository,
    private val propertyImageRepository: PropertyImageRepository,
    private val inventoryRepository: DailyRoomInventoryRepository,
    private val rateRepository: DailyRoomRateRepository,
    private val cacheStore: CacheStore,
    private val availabilityCacheStore: AvailabilityCacheStore,
) {
    companion object {
        private const val DETAIL_KEY_PREFIX = "property:detail:"
        private val DETAIL_TTL: Duration = Duration.ofMinutes(10)
        private const val SEARCH_KEY_PREFIX = "search:result:"
        private val SEARCH_TTL: Duration = Duration.ofMinutes(5)
    }

    /**
     * 도시 + 기간 + 인원 + 정렬 Property 검색 (D-1 / D-6 / D-3).
     *
     * `searchInfos` 가 (city 매칭 + 가용 RoomType ≥ 1 + 최저 합산가 + 가용 RT 수) 일괄 계산. Facade 는
     * `PropertySearchRow` → `PropertySearchInfo` 매핑만 (Money 래핑 + lowestPricePerNight 산출).
     * total = city 매칭 row 수 (가용성은 일시적 상태, total 의미 비분리).
     *
     * Cache (D-4): `search:result:{city}:{sort}:{checkIn}:{checkOut}:{guests}:{page}:{size}` (TTL 5m).
     * 키에 일자/게스트 포함은 plan 의도 (정적/동적 분리) 와 *부분 어긋남* — 응답 구조와 강결합, 카디널리티 위험은
     * 운영 traffic shape 측정 후 진화 (week6+ 인계). 결제 흐름은 본 cache 결정 근거 사용 금지 (D-5).
     */
    @Transactional(readOnly = true)
    fun search(criteria: PropertySearchCriteria): PageResult<PropertySearchInfo> {
        if (criteria.city.isBlank()) {
            throw CoreException(ErrorType.BAD_REQUEST, "도시 코드는 비어 있을 수 없습니다.")
        }
        if (criteria.guestCount <= 0) {
            throw CoreException(ErrorType.BAD_REQUEST, "투숙 인원은 1명 이상이어야 합니다.")
        }

        val cacheKey = buildSearchCacheKey(criteria)

        @Suppress("UNCHECKED_CAST")
        return cacheStore.getOrPut(
            key = cacheKey,
            ttl = SEARCH_TTL,
            type = PageResult::class.java,
        ) {
            loadSearchPage(criteria)
        } as PageResult<PropertySearchInfo>
    }

    private fun buildSearchCacheKey(criteria: PropertySearchCriteria): String =
        SEARCH_KEY_PREFIX +
            "${criteria.city}:" +
            "${criteria.sortKey}:" +
            "${criteria.period.checkIn}:" +
            "${criteria.period.checkOut}:" +
            "${criteria.guestCount}:" +
            "${criteria.page.page}:" +
            "${criteria.page.size}"

    private fun loadSearchPage(criteria: PropertySearchCriteria): PageResult<PropertySearchInfo> {
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
     * 단일 Property 정적 상세. PropertyImage 는 별도 AR — 명시 호출 (lazy proxy 회피).
     *
     * Cache (D-4): `property:detail:{id}` TTL 10m cache-aside. miss 시 loader 가 3-쿼리 (findById +
     * findByPropertyId × 2) 실행 후 cache put. loader 가 NOT_FOUND throw 면 cache put 안 함 (negative
     * caching 미적용, week6+ 인계). 무효화는 Wishlist afterCommit evict, 어드민 수정 합류 시 추가.
     */
    @Transactional(readOnly = true)
    fun getDetail(propertyId: Long): PropertyDetailInfo {
        return cacheStore.getOrPut(
            key = DETAIL_KEY_PREFIX + propertyId,
            ttl = DETAIL_TTL,
            type = PropertyDetailInfo::class.java,
        ) {
            val property = propertyRepository.findById(propertyId)
                ?: throw CoreException(ErrorType.NOT_FOUND, "존재하지 않는 숙소입니다.")
            val roomTypes = roomTypeRepository.findByPropertyId(propertyId)
            val images = propertyImageRepository.findByPropertyId(propertyId)
            PropertyDetailInfo.of(property, roomTypes, images)
        }
    }

    /** 단일 Property 의 객실 타입별 가용성 + 합산가. 사용자가 기간 + 인원 지정 후 호출. */
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
     * 객실 타입 단건의 가용성 + 합산가. 인원 초과 / 일자 누락 / 재고 부족 사유 분기.
     *
     * Cache (D-4): `AvailabilityCacheStore.loadForRange` RT × date 단위. 부분 hit 시 *전체 구간 fetch*
     * (Repository range query 한계), TTL 10s 가정에서 비용 무시 가능. `RoomDailyAvailability.pricePerNight`
     * 합산이 cache 직렬화 단위와 1:1 (`priceCalculator.totalPrice(rates)` 대체).
     *
     * **결제 금지 (D-5 contract)**: 본 메서드 응답은 `ReservationFacade.reserve` 의 결정 근거로 사용 금지.
     * reserve 는 비관적 락으로 DB 행 직접 재확인.
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
        val rows = availabilityCacheStore.loadForRange(
            roomTypeId = roomType.id,
            from = period.checkIn,
            to = period.checkOut,
        ) { missing ->
            loadAvailabilityForMissing(roomType.id, period.checkIn, period.checkOut, missing)
        }
        if (rows.size != expectedDates.size || rows.map { it.date }.toSet() != expectedDates.toSet()) {
            return RoomTypeAvailabilityInfo.unavailable(roomType, "기간 내 재고 또는 요금 정보가 누락되었습니다.")
        }
        if (rows.any { it.availableRooms <= 0 }) {
            return RoomTypeAvailabilityInfo.unavailable(roomType, "기간 내 재고가 부족한 일자가 있습니다.")
        }
        val total = Money.of(rows.sumOf { it.pricePerNight })
        return RoomTypeAvailabilityInfo.available(roomType, total, period.nights())
    }

    /** loader callback — miss 일자만 받지만 Repository range query 한계로 전체 구간 fetch 후 필터. */
    private fun loadAvailabilityForMissing(
        roomTypeId: Long,
        from: LocalDate,
        to: LocalDate,
        missing: List<LocalDate>,
    ): List<RoomDailyAvailability> {
        if (missing.isEmpty()) return emptyList()
        val missingSet = missing.toSet()
        val invs = inventoryRepository.findAllInRange(roomTypeId, from, to).associateBy { it.date }
        val rts = rateRepository.findAllInRange(roomTypeId, from, to).associateBy { it.date }
        return missing.mapNotNull { date ->
            if (date !in missingSet) return@mapNotNull null
            val inv = invs[date] ?: return@mapNotNull null
            val rate = rts[date] ?: return@mapNotNull null
            RoomDailyAvailability(
                roomTypeId = roomTypeId,
                date = date,
                totalRooms = inv.totalRooms,
                reservedRooms = inv.reservedRooms,
                pricePerNight = rate.pricePerNight.amount,
            )
        }
    }
}
