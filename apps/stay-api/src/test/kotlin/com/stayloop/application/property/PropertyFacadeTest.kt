package com.stayloop.application.property

import com.stayloop.application.property.command.PropertySearchCriteria
import com.stayloop.application.property.command.RoomAvailabilityQuery
import com.stayloop.domain.property.value.PropertySortKey
import com.stayloop.domain.common.value.Money
import com.stayloop.domain.common.value.PageQuery
import com.stayloop.domain.inventory.DailyRoomInventoryModel
import com.stayloop.domain.property.PropertyModel
import com.stayloop.domain.property.RoomTypeModel
import com.stayloop.domain.property.value.Address
import com.stayloop.domain.property.value.Amenities
import com.stayloop.domain.property.value.BedConfig
import com.stayloop.domain.property.value.BedType
import com.stayloop.domain.property.value.CancellationPolicy
import com.stayloop.domain.property.value.CancellationType
import com.stayloop.domain.property.value.GuestCount
import com.stayloop.domain.property.value.Name
import com.stayloop.domain.property.value.PropertyCategory
import com.stayloop.domain.property.value.PropertyPolicy
import com.stayloop.domain.property.value.Rating
import com.stayloop.domain.rate.DailyRoomRateModel
import com.stayloop.domain.rate.ReservationPriceCalculator
import com.stayloop.domain.reservation.value.StayPeriod
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import com.stayloop.support.test.InMemoryCacheStore
import com.stayloop.support.test.InMemoryDailyRoomInventoryRepository
import com.stayloop.support.test.InMemoryDailyRoomRateRepository
import com.stayloop.support.test.InMemoryPropertyImageRepository
import com.stayloop.support.test.InMemoryPropertyRepository
import com.stayloop.support.test.InMemoryRoomTypeRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class PropertyFacadeTest {
    private lateinit var properties: InMemoryPropertyRepository
    private lateinit var roomTypes: InMemoryRoomTypeRepository
    private lateinit var propertyImages: InMemoryPropertyImageRepository
    private lateinit var inventories: InMemoryDailyRoomInventoryRepository
    private lateinit var rates: InMemoryDailyRoomRateRepository
    private lateinit var cacheStore: InMemoryCacheStore
    private lateinit var sut: PropertyFacade

    @BeforeEach
    fun setUp() {
        roomTypes = InMemoryRoomTypeRepository()
        propertyImages = InMemoryPropertyImageRepository()
        inventories = InMemoryDailyRoomInventoryRepository()
        rates = InMemoryDailyRoomRateRepository()
        properties = InMemoryPropertyRepository(roomTypes, rates, inventories)
        cacheStore = InMemoryCacheStore()
        sut = PropertyFacade(
            propertyRepository = properties,
            roomTypeRepository = roomTypes,
            propertyImageRepository = propertyImages,
            inventoryRepository = inventories,
            rateRepository = rates,
            priceCalculator = ReservationPriceCalculator(),
            cacheStore = cacheStore,
        )
    }

    @DisplayName("search 는 가용 객실이 1개 이상인 숙소만 반환하고 최저 합산가를 노출한다 (AC-1, AC-2).")
    @Test
    fun shouldReturnPropertiesWithAvailableRoomsAndLowestTotalPrice() {
        val seoulProperty = saveProperty(name = "강남호텔", city = "SEOUL")
        val standard = saveRoomType(propertyId = seoulProperty.id, name = "스탠다드", baseGuests = 2, maxGuests = 2)
        val deluxe = saveRoomType(propertyId = seoulProperty.id, name = "디럭스", baseGuests = 2, maxGuests = 4)
        val period = StayPeriod(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 3))
        seedAllDates(standard.id, period, totalRooms = 5, reservedRooms = 0, pricePerNight = 80_000)
        seedAllDates(deluxe.id, period, totalRooms = 5, reservedRooms = 0, pricePerNight = 120_000)

        // 강남호텔 SEOUL — 가용 객실 2개, 최저 합산가 = 80_000 × 2박 = 160_000
        val saturated = saveProperty(name = "포화호텔", city = "SEOUL")
        val saturatedRoom = saveRoomType(propertyId = saturated.id, name = "트윈", baseGuests = 2, maxGuests = 2)
        seedAllDates(saturatedRoom.id, period, totalRooms = 1, reservedRooms = 1, pricePerNight = 90_000)

        // 다른 도시 — 검색 도시가 SEOUL 이므로 제외
        val busanProperty = saveProperty(name = "해운대리조트", city = "BUSAN")
        val busanRoom = saveRoomType(propertyId = busanProperty.id, name = "오션뷰", baseGuests = 2, maxGuests = 2)
        seedAllDates(busanRoom.id, period, totalRooms = 5, reservedRooms = 0, pricePerNight = 200_000)

        val result = sut.search(
            PropertySearchCriteria(
                city = "SEOUL",
                period = period,
                guestCount = 2,
                page = PageQuery(page = 0, size = 20),
                sortKey = PropertySortKey.RECOMMENDED,
            ),
        )

        // SEOUL 매칭 2건 중 가용 객실이 있는 강남호텔만 결과에 포함 (AC-2)
        assertThat(result.content).hasSize(1)
        assertThat(result.total).isEqualTo(2L) // total 은 도시 매칭 행 수 — 가용 0 제외 전 기준
        val info = result.content.first()
        assertThat(info.propertyId).isEqualTo(seoulProperty.id)
        assertThat(info.lowestTotalPrice).isEqualTo(Money.of(160_000)) // 80_000 × 2박
        assertThat(info.lowestPricePerNight).isEqualTo(Money.of(80_000))
        assertThat(info.availableRoomTypeCount).isEqualTo(2)
    }

    @DisplayName("search 는 인원 수가 maxGuests 를 초과하는 객실 타입을 가용에서 제외한다.")
    @Test
    fun shouldExcludeRoomTypeWithGuestCountOverMax() {
        val property = saveProperty(name = "강남호텔", city = "SEOUL")
        val small = saveRoomType(propertyId = property.id, name = "싱글", baseGuests = 1, maxGuests = 1)
        val large = saveRoomType(propertyId = property.id, name = "패밀리", baseGuests = 2, maxGuests = 4)
        val period = StayPeriod(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 3))
        seedAllDates(small.id, period, totalRooms = 5, reservedRooms = 0, pricePerNight = 50_000)
        seedAllDates(large.id, period, totalRooms = 5, reservedRooms = 0, pricePerNight = 150_000)

        val result = sut.search(
            PropertySearchCriteria(
                city = "SEOUL",
                period = period,
                guestCount = 3,
                page = PageQuery(page = 0, size = 20),
            ),
        )

        // 3명 → 싱글(max=1) 제외, 패밀리(max=4) 만 가용 → 합산가 150_000 × 2 = 300_000
        val info = result.content.single()
        assertThat(info.lowestTotalPrice).isEqualTo(Money.of(300_000))
        assertThat(info.availableRoomTypeCount).isEqualTo(1)
    }

    @DisplayName("search 는 일자별 재고 누락 / 재고 0 / 요금 누락 객실을 가용에서 제외한다.")
    @Test
    fun shouldExcludeRoomTypeWithMissingInventoryOrZeroAvailability() {
        val property = saveProperty(name = "강남호텔", city = "SEOUL")
        val period = StayPeriod(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 3))

        // (a) 재고 누락 — 6/1 만 등록, 6/2 누락
        val missing = saveRoomType(propertyId = property.id, name = "재고누락", baseGuests = 2, maxGuests = 2)
        inventories.save(DailyRoomInventoryModel.create(missing.id, period.checkIn, totalRooms = 5))
        rates.save(DailyRoomRateModel.create(missing.id, period.checkIn, Money.of(50_000)))
        rates.save(DailyRoomRateModel.create(missing.id, period.checkIn.plusDays(1), Money.of(50_000)))

        // (b) 재고 0 — 모든 일자 totalRooms == reservedRooms
        val full = saveRoomType(propertyId = property.id, name = "재고0", baseGuests = 2, maxGuests = 2)
        seedAllDates(full.id, period, totalRooms = 1, reservedRooms = 1, pricePerNight = 50_000)

        // (c) 요금 누락 — 재고는 있는데 6/2 요금이 없음
        val noRate = saveRoomType(propertyId = property.id, name = "요금누락", baseGuests = 2, maxGuests = 2)
        period.datesToReserve().forEach { date ->
            inventories.save(DailyRoomInventoryModel.create(noRate.id, date, totalRooms = 5))
        }
        rates.save(DailyRoomRateModel.create(noRate.id, period.checkIn, Money.of(50_000)))

        val result = sut.search(
            PropertySearchCriteria(
                city = "SEOUL",
                period = period,
                guestCount = 2,
                page = PageQuery(page = 0, size = 20),
            ),
        )

        // 모두 가용 0 → 결과에서 Property 자체가 제외됨 (AC-2)
        assertThat(result.content).isEmpty()
    }

    @DisplayName("search 는 city 가 비어 있으면 BAD_REQUEST 로 거절한다.")
    @Test
    fun shouldRejectBlankCity() {
        val period = StayPeriod(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 3))
        assertThatThrownBy {
            sut.search(
                PropertySearchCriteria(
                    city = "  ",
                    period = period,
                    guestCount = 2,
                    page = PageQuery(page = 0, size = 20),
                ),
            )
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("search 는 sort 4종 (RECOMMENDED / PRICE_ASC / RATING_DESC / WISHES_DESC) 모두 활성화된다 (week5 PR1 D-6).")
    @Test
    fun shouldActivateAllFourSortKeys() {
        // 같은 도시에 2 Property — wish_count / rating / 가격을 의도적으로 다르게.
        val cheaper = saveProperty(name = "가성비호텔", city = "SEOUL", wishCount = 10, rating = 3.5)
        val premium = saveProperty(name = "프리미엄호텔", city = "SEOUL", wishCount = 100, rating = 4.8)
        val period = StayPeriod(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 3))
        val cheaperRoom = saveRoomType(propertyId = cheaper.id, name = "스탠다드", baseGuests = 2, maxGuests = 2)
        val premiumRoom = saveRoomType(propertyId = premium.id, name = "디럭스", baseGuests = 2, maxGuests = 2)
        seedAllDates(cheaperRoom.id, period, totalRooms = 5, reservedRooms = 0, pricePerNight = 70_000)
        seedAllDates(premiumRoom.id, period, totalRooms = 5, reservedRooms = 0, pricePerNight = 200_000)

        fun searchWith(sortKey: PropertySortKey) = sut.search(
            PropertySearchCriteria(
                city = "SEOUL",
                period = period,
                guestCount = 2,
                page = PageQuery(page = 0, size = 20),
                sortKey = sortKey,
            ),
        ).content.map { it.propertyId }

        // PRICE_ASC — 가성비 (70k) 가 프리미엄 (200k) 보다 먼저
        assertThat(searchWith(PropertySortKey.PRICE_ASC)).containsExactly(cheaper.id, premium.id)
        // WISHES_DESC — wish_count 100 > 10
        assertThat(searchWith(PropertySortKey.WISHES_DESC)).containsExactly(premium.id, cheaper.id)
        // RATING_DESC — rating 4.8 > 3.5
        assertThat(searchWith(PropertySortKey.RATING_DESC)).containsExactly(premium.id, cheaper.id)
        // RECOMMENDED — id ASC (저장 순서)
        assertThat(searchWith(PropertySortKey.RECOMMENDED)).containsExactly(cheaper.id, premium.id)
    }

    @DisplayName("search 는 동일 criteria 에 대해 두 번째 호출에서 cache hit 으로 응답한다 (PR4 A-4).")
    @Test
    fun shouldServeSearchFromCacheOnSecondCall() {
        val property = saveProperty(name = "강남호텔", city = "SEOUL")
        val period = StayPeriod(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 3))
        val roomType = saveRoomType(propertyId = property.id, name = "스탠다드", baseGuests = 2, maxGuests = 2)
        seedAllDates(roomType.id, period, totalRooms = 5, reservedRooms = 0, pricePerNight = 80_000)

        val criteria = PropertySearchCriteria(
            city = "SEOUL",
            period = period,
            guestCount = 2,
            page = PageQuery(page = 0, size = 20),
            sortKey = PropertySortKey.RECOMMENDED,
        )

        val first = sut.search(criteria)
        assertThat(first.content).hasSize(1)

        // Repository 상태를 *변경* — 새 Property 추가. cache 가 정상이면 두 번째 응답은 그대로 1건.
        val newProperty = saveProperty(name = "추가호텔", city = "SEOUL")
        val newRoom = saveRoomType(propertyId = newProperty.id, name = "신규", baseGuests = 2, maxGuests = 2)
        seedAllDates(newRoom.id, period, totalRooms = 5, reservedRooms = 0, pricePerNight = 70_000)

        val second = sut.search(criteria)
        // 새 Property 가 응답에 *없음* — cache hit 의 직접 증거
        assertThat(second.content.map { it.propertyId }).containsExactly(property.id)
    }

    @DisplayName("search 의 cache 키는 city/sort/checkIn/checkOut/guests/page/size 모두 포함 — 일자가 다르면 다른 키로 miss 가 발생한다 (PR4 A-4 trade-off 박제).")
    @Test
    fun shouldUseSeparateCacheKeyPerPeriod() {
        val property = saveProperty(name = "강남호텔", city = "SEOUL")
        val periodA = StayPeriod(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 3))
        val periodB = StayPeriod(LocalDate.of(2026, 6, 5), LocalDate.of(2026, 6, 7))
        val roomType = saveRoomType(propertyId = property.id, name = "스탠다드", baseGuests = 2, maxGuests = 2)
        seedAllDates(roomType.id, periodA, totalRooms = 5, reservedRooms = 0, pricePerNight = 80_000)
        seedAllDates(roomType.id, periodB, totalRooms = 5, reservedRooms = 0, pricePerNight = 100_000)

        // 두 기간 검색 결과는 *다른 cache 키* — 각각 miss → load → put.
        val criteriaA = PropertySearchCriteria(
            city = "SEOUL",
            period = periodA,
            guestCount = 2,
            page = PageQuery(page = 0, size = 20),
            sortKey = PropertySortKey.RECOMMENDED,
        )
        val criteriaB = criteriaA.copy(period = periodB)

        val responseA = sut.search(criteriaA)
        val responseB = sut.search(criteriaB)

        // 일자 따라 lowestTotalPrice 가 다름 — 두 응답이 *분리된 cache 엔트리* 임을 증명
        assertThat(responseA.content.first().lowestTotalPrice).isEqualTo(Money.of(160_000))
        assertThat(responseB.content.first().lowestTotalPrice).isEqualTo(Money.of(200_000))
    }

    @DisplayName("getDetail 은 존재하지 않는 propertyId 에 NOT_FOUND 를 던진다.")
    @Test
    fun shouldThrowNotFoundOnUnknownPropertyId() {
        assertThatThrownBy { sut.getDetail(999L) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.NOT_FOUND)
    }

    @DisplayName("getDetail 은 Property 정적 정보 + 객실 타입 목록을 반환한다.")
    @Test
    fun shouldReturnDetail() {
        val property = saveProperty(name = "강남호텔", city = "SEOUL")
        saveRoomType(propertyId = property.id, name = "스탠다드", baseGuests = 2, maxGuests = 2)
        saveRoomType(propertyId = property.id, name = "디럭스", baseGuests = 2, maxGuests = 4)

        val info = sut.getDetail(property.id)

        assertThat(info.propertyId).isEqualTo(property.id)
        assertThat(info.name).isEqualTo("강남호텔")
        assertThat(info.city).isEqualTo("SEOUL")
        assertThat(info.roomTypes).hasSize(2)
        assertThat(info.roomTypes.map { it.name }).containsExactlyInAnyOrder("스탠다드", "디럭스")
    }

    @DisplayName("getDetail 은 두 번째 호출에서 cache hit 으로 응답하고 Repository 를 다시 조회하지 않는다 (PR4 A-2).")
    @Test
    fun shouldServeDetailFromCacheOnSecondCall() {
        val property = saveProperty(name = "강남호텔", city = "SEOUL")
        saveRoomType(propertyId = property.id, name = "스탠다드", baseGuests = 2, maxGuests = 2)

        val first = sut.getDetail(property.id)
        // cache hit 검증 — 두 번째 호출이 첫 번째와 *완전히 동일한 인스턴스* (Info data class equality)
        val second = sut.getDetail(property.id)
        assertThat(second).isEqualTo(first)

        // Repository 의 *실제 데이터 상태* 가 바뀌어도 cache 가 유지하는 옛 응답이 그대로 — cache hit 의 직접 증거.
        // 본 Property 를 mutate (rating 갱신) 한 뒤 다시 getDetail 호출 — 여전히 *첫 호출* 의 응답이 와야 함.
        val mutated = properties.findById(property.id)!!.also {
            // Rating 은 protected set 라 직접 변경 X — 대신 새 객실 추가로 *Repository 상태* 만 변경
            saveRoomType(propertyId = property.id, name = "스위트", baseGuests = 2, maxGuests = 6)
        }
        val third = sut.getDetail(property.id)
        // 새 객실이 cache 응답에는 *없음* — cache hit 의 증거
        assertThat(third.roomTypes.map { it.name }).containsExactly("스탠다드")

        // 캐시 무효화 후 재호출은 새 상태 반영
        cacheStore.evict("property:detail:${property.id}")
        val refreshed = sut.getDetail(property.id)
        assertThat(refreshed.roomTypes.map { it.name }).containsExactlyInAnyOrder("스탠다드", "스위트")
    }

    @DisplayName("getAvailableRooms 는 가용 / 인원 초과 / 재고 부족 / 일자 누락을 사유와 함께 노출한다.")
    @Test
    fun shouldReturnRoomAvailabilityWithReason() {
        val property = saveProperty(name = "강남호텔", city = "SEOUL")
        val ok = saveRoomType(propertyId = property.id, name = "OK", baseGuests = 2, maxGuests = 2)
        val tooSmall = saveRoomType(propertyId = property.id, name = "1인용", baseGuests = 1, maxGuests = 1)
        val full = saveRoomType(propertyId = property.id, name = "FULL", baseGuests = 2, maxGuests = 2)
        val period = StayPeriod(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 3))
        seedAllDates(ok.id, period, totalRooms = 5, reservedRooms = 0, pricePerNight = 100_000)
        seedAllDates(tooSmall.id, period, totalRooms = 5, reservedRooms = 0, pricePerNight = 50_000)
        seedAllDates(full.id, period, totalRooms = 1, reservedRooms = 1, pricePerNight = 100_000)

        val results = sut.getAvailableRooms(
            RoomAvailabilityQuery(propertyId = property.id, period = period, guestCount = 2),
        )

        val byName = results.associateBy { it.name }
        assertThat(byName["OK"]?.available).isTrue()
        assertThat(byName["OK"]?.totalPrice).isEqualTo(Money.of(200_000))
        assertThat(byName["OK"]?.pricePerNight).isEqualTo(Money.of(100_000))
        assertThat(byName["1인용"]?.available).isFalse()
        assertThat(byName["1인용"]?.unavailableReason).contains("최대 인원")
        assertThat(byName["FULL"]?.available).isFalse()
        assertThat(byName["FULL"]?.unavailableReason).contains("재고")
    }

    private fun saveProperty(
        name: String,
        city: String,
        wishCount: Int = 0,
        rating: Double = 0.0,
    ): PropertyModel {
        // internal constructor 직접 사용 — wishCount / rating 은 운영에서 별도 흐름으로 갱신되는 *집계 상태* 라
        // PropertyModel.create() 가 노출하지 않는다. 본 테스트는 정렬 의미론 검증이라 *시드 상태* 를 직접 주입.
        val property = PropertyModel(
            name = Name(name),
            category = PropertyCategory.HOTEL,
            description = "테스트용 숙소",
            address = Address(city = city, fullAddress = "$city 어딘가 123"),
            amenities = Amenities.EMPTY,
            policy = PropertyPolicy(
                checkInTime = LocalTime.of(15, 0),
                checkOutTime = LocalTime.of(11, 0),
                cancellation = CancellationPolicy(type = CancellationType.FREE_UNTIL, freeUntilDaysBefore = 3),
            ),
            rating = Rating(rating),
            wishCount = wishCount,
        )
        return properties.save(property)
    }

    private fun saveRoomType(propertyId: Long, name: String, baseGuests: Int, maxGuests: Int): RoomTypeModel {
        val roomType = RoomTypeModel.create(
            propertyId = propertyId,
            name = Name(name),
            guestCount = GuestCount(base = baseGuests, max = maxGuests),
            bedConfig = BedConfig.of(BedType.DOUBLE to 1),
        )
        return roomTypes.save(roomType)
    }

    private fun seedAllDates(
        roomTypeId: Long,
        period: StayPeriod,
        totalRooms: Int,
        reservedRooms: Int,
        pricePerNight: Long,
    ) {
        period.datesToReserve().forEach { date ->
            inventories.save(
                DailyRoomInventoryModel.create(
                    roomTypeId = roomTypeId,
                    date = date,
                    totalRooms = totalRooms,
                    reservedRooms = reservedRooms,
                ),
            )
            rates.save(DailyRoomRateModel.create(roomTypeId, date, Money.of(pricePerNight)))
        }
    }
}
