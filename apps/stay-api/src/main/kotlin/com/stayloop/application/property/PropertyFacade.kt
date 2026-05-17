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
     * 도시 + 기간 + 인원 + 정렬 기준 Property 검색. (AC-1, AC-2 + week5 PR1 D-1 / D-6 + PR3 D-3)
     *
     * **흐름** — `propertyRepository.searchInfos(...)` 가 QueryDSL 2-step batch IN 으로 (a) city 매칭 + (b)
     * 가용 RoomType ≥ 1 + (c) 최저 합산가 + (d) 가용 RoomType 수 를 모두 계산. Facade 는 결과 `PropertySearchRow`
     * 를 응답 DTO `PropertySearchInfo` 로 1:1 매핑 (Money 래핑 + lowestPricePerNight 산출).
     *
     * **`page.total`** — Repository 가 *city 매칭 전체 row 수* 로 박제. 가용 0 Property 는 *content 에서만* 제외,
     * total 은 도시 기준 유지 (AC-1 가용성은 일시적 상태).
     *
     * **캐시 정책 (PR4 D-4, A-4)**: `search:result:{city}:{sort}:{checkIn}:{checkOut}:{guests}:{page}:{size}`
     * (TTL 5m) 로 cache-aside. 응답 `PageResult<PropertySearchInfo>` 전체를 직렬화.
     *
     * **본 라운드의 trade-off — plan 의 *정적/동적 분리* 와 부분 어긋남**:
     * - plan A-4 명세: *키에 일자/게스트수 미포함* — 정적/동적 분리의 본질 (Loop 7 §고민 2 카디널리티 폭발 회피).
     * - 본 라운드 구현: *키에 일자/게스트수 포함* — 응답 구조 (`PropertySearchInfo` 가 `lowestTotalPrice` /
     *   `availableRoomTypeCount` 같은 동적 필드 포함) 와 강결합. 응답 분리 (정적 부분만 cache + 동적은 매
     *   요청) 는 복잡도 큼 — Phase M (비교군 측정) 에서 hit rate 측정 후 GR-3 진화 여부 결정.
     * - 본 trade-off 의 *학습 자산* — *Loop 7 §고민 2 의 카디널리티 위험을 측정으로 입증* 하는 것.
     *
     * **무효화 정책**: 본 라운드는 evict 호출 없음 — TTL 5m 만료에 의존. wish/unwish 의 wishCount 변경은
     * WISHES_DESC 정렬에 영향을 주지만 5분 stale 은 *정적 분리의 정의* (Loop 7 §고민 2 와 정합). 결제 흐름은
     * 본 cache 를 *결정 근거로 사용 금지* — PR5 D-5 의 *결제 직전 DB 재확인* contract.
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
     * 단일 Property 의 정적 상세. 가용성 / 합산가는 별도 API.
     *
     * **이미지 조회 정책 (PR3, week5-b.md Loop 8'')**: PropertyImage 는 별도 AR — Facade 가 명시 호출. JPA
     * `@OneToMany` 의 *암시적 lazy loading* 보다 *명시적 Repository 호출* 의 추적 / 테스트 가능성 우위.
     *
     * **캐시 정책 (PR4 D-4, A-2)**: `property:detail:{id}` (TTL 10m) 를 통해 cache-aside 흐름. miss 시 loader
     * 가 기존 3-쿼리 흐름 (findById + findByPropertyId × 2) 을 실행하고 결과를 cache 에 put. Redis 다운 시
     * loader 결과 그대로 반환 (cache put 실패는 silent — `RedisCacheStore.getOrPut` KDoc 정합).
     *
     * **NOT_FOUND 처리**: loader 안에서 `findById` 가 `null` 이면 `CoreException(NOT_FOUND)` throw. cache 에
     * *부정 응답* 은 저장하지 않는다 (`getOrPut` 이 throw 시 put 안 함). negative caching 은 본 라운드 외
     * (week6+ 인계 — 부재 Property 에 대한 반복 호출 부하는 운영 합류 시 측정).
     *
     * **무효화 정책**: 본 라운드는 *Property 정적 정보 변경 흐름이 없음* — 어드민 수정 합류 시점 (week6+)
     * 에 evict 호출 추가. TTL 10m 만료에 의존.
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
     *
     * **캐시 정책 (PR4 D-4, A-5b)**: `AvailabilityCacheStore.loadForRange(rtId, from, to, loader)` 로 RT × date
     * 단위 cache 통과. miss 인 일자만 [loadAvailabilityForMissing] 이 inventory + rate 를 함께 fetch 해
     * `RoomDailyAvailability` 로 변환 → cache put.
     *
     * **부분 hit 최적화 미적용**: miss 가 한 일자라도 있으면 loader 가 *전체 구간* `findAllInRange` 호출.
     * 부분 hit 비율이 낮은 (TTL 10s) 본 시나리오에서는 비용 무시 가능 — Phase M 측정에서 확인.
     *
     * **`RoomDailyAvailability.pricePerNight` 합산**: cache 가 inventory + rate 를 한 단위로 묶어 `pricePerNight`
     * 직접 sum. 기존 `priceCalculator.totalPrice(rates)` 대체 — 합산 로직이 *cache 직렬화 단위* 와 1:1.
     *
     * **결제 금지 (D-5 contract)**: 본 메서드 응답을 *결제 흐름 (`ReservationFacade.reserve`)* 의 결정 근거로
     * 사용 금지. reserve 는 비관적 락으로 DB 행 직접 잡고 재확인 — PR5 가 회귀 가드 합류.
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

    /**
     * `AvailabilityCacheStore.loadForRange` 의 loader callback — miss 일자만 받지만 *전체 구간 fetch* 후 필터
     * (Repository 가 `from..to` 단위 query 만 제공하므로). 부분 hit 시 약간의 over-fetch 가 발생하지만 본
     * 시나리오의 TTL 10s 가정에서는 무시 가능.
     */
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
