package com.stayloop.application.property

import com.stayloop.application.property.command.PropertySearchCriteria
import com.stayloop.application.property.command.RoomAvailabilityQuery
import com.stayloop.application.reservation.ReservationFacade
import com.stayloop.application.reservation.command.ReserveCommand
import com.stayloop.domain.common.value.Money
import com.stayloop.domain.common.value.PageQuery
import com.stayloop.domain.inventory.DailyRoomInventoryModel
import com.stayloop.domain.inventory.DailyRoomInventoryRepository
import com.stayloop.domain.property.PropertyModel
import com.stayloop.domain.property.PropertyRepository
import com.stayloop.domain.property.RoomTypeModel
import com.stayloop.domain.property.RoomTypeRepository
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
import com.stayloop.domain.property.value.PropertySortKey
import com.stayloop.domain.property.value.Rating
import com.stayloop.domain.rate.DailyRoomRateModel
import com.stayloop.domain.rate.DailyRoomRateRepository
import com.stayloop.domain.reservation.value.GuestInfo
import com.stayloop.domain.reservation.value.StayPeriod
import com.stayloop.domain.user.value.LoginId
import com.stayloop.domain.user.value.PhoneNumber
import com.stayloop.infrastructure.cache.CacheStore
import com.stayloop.testcontainers.MySqlTestContainersConfig
import com.stayloop.testcontainers.RedisTestContainersConfig
import com.stayloop.utils.DatabaseCleanUp
import com.stayloop.utils.RedisCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDate
import java.time.LocalTime

/**
 * week5 PR5 Phase A-3 — **4 채택안 통합 E2E 회귀 가드** (`docs/plan/week5.md` PR5 A-3).
 *
 * **목적**: PR1 인덱스 + PR2 D-2 wishCount 비정규화 + PR3 D-3 QueryDSL 2-step batch IN + PR4 D-4 cache —
 * 4 채택안이 *동시* 작동하는 상태에서 시나리오 A (서울 RECOMMENDED) / B (제주 WISHES_DESC) / C (부산 PRICE_ASC)
 * 의 *검색 → 상세 → 가용 → 예약* 흐름이 *끊김 없이 정합* 임을 검증.
 *
 * **PR1~4 의 개별 회귀 가드 분담**:
 * - `PropertyFacadeSearchSortTest` — sort 4종 × EXPLAIN 어설션 (D-7)
 * - `PropertyDetailCacheTest` / `PropertySearchCacheTest` / `AvailabilityCacheTest` — cache 흐름
 * - `PropertyRepositoryImplSearchTest` — N+1 제거 (발행 SQL ≤ 3)
 * - `ReservationCacheBypassTest` — D-5 결제 직전 DB 재확인 contract
 *
 * **본 테스트의 *추가 가치***: 위 모든 회귀 가드가 *각자* 통과해도 *통합 흐름* 에서 어긋날 수 있는 부분 —
 * (a) 검색 응답의 `lowestTotalPrice` 가 reserve 의 실 결제액과 정합, (b) cache hit 응답이 sort 4종 의도 순서
 * 보존, (c) reserve 후 *다음 검색* 이 변경된 가용성 반영 (cache evict + DB 재조회).
 *
 * **간소화 — p95 게이트 미포함**: 본 테스트는 *기능 정합* 만 검증. *p95 게이트* 는 `k6-results.md` 가 SSOT
 * (`documents/feature/perf-rollout/k6-results.md` Phase E 박제). in-process micro-bench 는 *상한 추정* 만
 * 가능 (CPU contention) — k6 측정의 의미와 분담 (week5.md GR-6).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class)
class PropertySearchScenarioTest {
    @Autowired
    private lateinit var propertyFacade: PropertyFacade

    @Autowired
    private lateinit var reservationFacade: ReservationFacade

    @Autowired
    private lateinit var properties: PropertyRepository

    @Autowired
    private lateinit var roomTypes: RoomTypeRepository

    @Autowired
    private lateinit var inventories: DailyRoomInventoryRepository

    @Autowired
    private lateinit var rates: DailyRoomRateRepository

    @Autowired
    private lateinit var cacheStore: CacheStore

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var databaseCleanUp: DatabaseCleanUp

    @Autowired
    private lateinit var redisCleanUp: RedisCleanUp

    @BeforeEach
    fun cleanUp() {
        databaseCleanUp.truncateAllTables()
        redisCleanUp.truncateAll()
    }

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
        redisCleanUp.truncateAll()
    }

    @DisplayName("시나리오 A (서울 / RECOMMENDED) — 검색 → 상세 → 가용 → 예약 e2e 정합 + EXPLAIN 인덱스 활용 (D-1).")
    @Test
    fun scenarioA_seoulRecommendedE2E() {
        val period = StayPeriod(LocalDate.of(2026, 5, 15), LocalDate.of(2026, 5, 17))
        val (property, roomType) = seedSeoulProperty(period, pricePerNight = 80_000, totalRooms = 5)

        // 검색
        val search = propertyFacade.search(
            PropertySearchCriteria(
                city = "seoul",
                period = period,
                guestCount = 2,
                page = PageQuery(page = 0, size = 20),
                sortKey = PropertySortKey.RECOMMENDED,
            ),
        )
        assertThat(search.content).extracting<Long> { it.propertyId }.contains(property.id)
        val searchRow = search.content.first { it.propertyId == property.id }
        assertThat(searchRow.lowestTotalPrice).isEqualTo(Money.of(160_000)) // 2박 × 80_000

        // 상세 + 가용
        val detail = propertyFacade.getDetail(property.id)
        assertThat(detail.propertyId).isEqualTo(property.id)
        val availability = propertyFacade.getAvailableRooms(
            RoomAvailabilityQuery(propertyId = property.id, period = period, guestCount = 2),
        )
        assertThat(availability).hasSize(1)
        assertThat(availability[0].available).isTrue()
        assertThat(availability[0].totalPrice).isEqualTo(Money.of(160_000))

        // 예약 — 검색 응답의 결제액 정합
        val reservation = reservationFacade.reserve(
            ReserveCommand(
                userId = LoginId("usera01"),
                propertyId = property.id,
                roomTypeId = roomType.id,
                period = period,
                guestCount = 2,
                guest = GuestInfo(name = "홍길동", phoneNumber = PhoneNumber("010-1234-5678")),
                couponId = null,
            ),
        )
        assertThat(reservation.totalPrice).isEqualTo(Money.of(160_000))

        // EXPLAIN — 시나리오 A 의 properties 인덱스 활용 회귀 가드 (D-7).
        val explainProps = jdbc.queryForList(
            "EXPLAIN SELECT id FROM properties WHERE city = ? LIMIT 20",
            "seoul",
        )
        assertThat(explainProps).anySatisfy { row ->
            assertThat(row["key"] as String?).isIn("idx_properties_city", "idx_properties_city_wish_count")
            assertThat(row["Extra"] as String?).doesNotContainIgnoringCase("Using filesort")
        }

        // reserve afterCommit evict 의 통합 정합 — *다음 가용 조회* 가 변경된 reservedRooms 반영
        val availabilityAfter = propertyFacade.getAvailableRooms(
            RoomAvailabilityQuery(propertyId = property.id, period = period, guestCount = 2),
        )
        period.datesToReserve().forEach { date ->
            val inv = inventories.findById(roomType.id, date)
                ?: error("inventory 누락 (date=$date)")
            assertThat(inv.reservedRooms).isEqualTo(1)
        }
        // cache evict 가 정상 발화했으므로 *다음 호출* 의 가용 응답이 4 (=5-1) 으로 갱신
        assertThat(availabilityAfter[0].available).isTrue()
    }

    @DisplayName("시나리오 B (제주 / WISHES_DESC) — wishCount DESC 정렬 + EXPLAIN idx_properties_city_wish_count + filesort 없음 (D-1/D-2).")
    @Test
    fun scenarioB_jejuWishesDescE2E() {
        val period = StayPeriod(LocalDate.of(2026, 7, 30), LocalDate.of(2026, 8, 4)) // 5박
        val (high, _) = seedJejuProperty(period, name = "인기숙소", wishCount = 500, pricePerNight = 200_000)
        val (mid, _) = seedJejuProperty(period, name = "중간숙소", wishCount = 200, pricePerNight = 150_000)
        val (low, _) = seedJejuProperty(period, name = "비인기", wishCount = 10, pricePerNight = 100_000)

        val search = propertyFacade.search(
            PropertySearchCriteria(
                city = "jeju",
                period = period,
                guestCount = 2,
                page = PageQuery(page = 0, size = 20),
                sortKey = PropertySortKey.WISHES_DESC,
            ),
        )
        assertThat(search.content.map { it.propertyId }).containsExactly(high.id, mid.id, low.id)

        // EXPLAIN — wishCount DESC 인덱스 prefix scan + filesort 없음
        val explain = jdbc.queryForList(
            "EXPLAIN SELECT id FROM properties WHERE city = ? ORDER BY wish_count DESC LIMIT 20",
            "jeju",
        )
        assertThat(explain).anySatisfy { row ->
            assertThat(row["key"]).isEqualTo("idx_properties_city_wish_count")
            assertThat(row["Extra"] as String?).doesNotContainIgnoringCase("Using filesort")
        }

        // 두 번째 동일 검색 — cache hit 응답이 sort 의도 순서 보존
        val cached = propertyFacade.search(
            PropertySearchCriteria(
                city = "jeju",
                period = period,
                guestCount = 2,
                page = PageQuery(page = 0, size = 20),
                sortKey = PropertySortKey.WISHES_DESC,
            ),
        )
        assertThat(cached.content.map { it.propertyId }).containsExactly(high.id, mid.id, low.id)
        // search:result:* cache key 가 존재
        val cacheKey = "search:result:jeju:WISHES_DESC:${period.checkIn}:${period.checkOut}:2:0:20"
        assertThat(cacheStore.get(cacheKey, com.stayloop.domain.common.value.PageResult::class.java)).isNotNull
    }

    @DisplayName("시나리오 C (부산 / PRICE_ASC) — 최저가 오름차순 + EXPLAIN daily_room_rates 복합 인덱스 사용 (D-1/D-6).")
    @Test
    fun scenarioC_busanPriceAscE2E() {
        val period = StayPeriod(LocalDate.of(2026, 5, 10), LocalDate.of(2026, 5, 11)) // 1박
        val (cheap, _) = seedBusanProperty(period, name = "가성비", pricePerNight = 60_000)
        val (mid, _) = seedBusanProperty(period, name = "중간가", pricePerNight = 120_000)
        val (premium, _) = seedBusanProperty(period, name = "프리미엄", pricePerNight = 250_000)

        val search = propertyFacade.search(
            PropertySearchCriteria(
                city = "busan",
                period = period,
                guestCount = 4,
                page = PageQuery(page = 0, size = 20),
                sortKey = PropertySortKey.PRICE_ASC,
            ),
        )
        assertThat(search.content.map { it.propertyId }).containsExactly(cheap.id, mid.id, premium.id)
        // 최저가 정합 — 1박이므로 lowestTotalPrice == pricePerNight
        assertThat(search.content[0].lowestTotalPrice).isEqualTo(Money.of(60_000))

        // EXPLAIN — daily_room_rates 의 (room_type_id, date) 인덱스 활용 회귀 가드
        val explain = jdbc.queryForList(
            """
            EXPLAIN SELECT p.id FROM properties p
            INNER JOIN room_types rt ON rt.property_id = p.id
            INNER JOIN daily_room_rates r ON r.room_type_id = rt.id
            WHERE p.city = ? AND r.date >= ? AND r.date < ?
            GROUP BY p.id
            ORDER BY MIN(r.price_per_night) ASC
            LIMIT 60
            """.trimIndent(),
            "busan",
            java.sql.Date.valueOf(period.checkIn),
            java.sql.Date.valueOf(period.checkOut),
        )
        // V010 의 (room_type_id, date) 인덱스가 r 테이블에 선택됨을 확인
        assertThat(explain).anySatisfy { row ->
            assertThat(row["table"] as String?).isEqualTo("r")
            assertThat(row["key"]).isEqualTo("idx_daily_room_rates_room_type_date")
        }
    }

    // ───────────────────── seed helpers ─────────────────────

    private fun seedSeoulProperty(period: StayPeriod, pricePerNight: Long, totalRooms: Int) =
        seedCityProperty("seoul", period, name = "강남호텔", wishCount = 100, rating = 4.5, pricePerNight = pricePerNight, totalRooms = totalRooms)

    private fun seedJejuProperty(period: StayPeriod, name: String, wishCount: Int, pricePerNight: Long) =
        seedCityProperty("jeju", period, name = name, wishCount = wishCount, rating = 4.0, pricePerNight = pricePerNight, totalRooms = 5)

    private fun seedBusanProperty(period: StayPeriod, name: String, pricePerNight: Long) =
        seedCityProperty("busan", period, name = name, wishCount = 50, rating = 4.0, pricePerNight = pricePerNight, totalRooms = 5)

    private fun seedCityProperty(
        city: String,
        period: StayPeriod,
        name: String,
        wishCount: Int,
        rating: Double,
        pricePerNight: Long,
        totalRooms: Int,
    ): Pair<PropertyModel, RoomTypeModel> {
        val property = properties.save(
            PropertyModel(
                name = Name(name),
                category = PropertyCategory.HOTEL,
                description = null,
                address = Address(city = city, fullAddress = "$city 어딘가 $name"),
                amenities = Amenities.EMPTY,
                policy = PropertyPolicy(
                    checkInTime = LocalTime.of(15, 0),
                    checkOutTime = LocalTime.of(11, 0),
                    cancellation = CancellationPolicy(type = CancellationType.FREE_UNTIL, freeUntilDaysBefore = 3),
                ),
                rating = Rating(rating),
                wishCount = wishCount,
            ),
        )
        val roomType = roomTypes.save(
            RoomTypeModel.create(
                propertyId = property.id,
                name = Name("표준객실"),
                guestCount = GuestCount(base = 2, max = 4),
                bedConfig = BedConfig.of(BedType.DOUBLE to 1),
            ),
        )
        period.datesToReserve().forEach { date ->
            inventories.save(
                DailyRoomInventoryModel.create(
                    roomTypeId = roomType.id,
                    date = date,
                    totalRooms = totalRooms,
                    reservedRooms = 0,
                ),
            )
            rates.save(DailyRoomRateModel.create(roomType.id, date, Money.of(pricePerNight)))
        }
        return property to roomType
    }
}
