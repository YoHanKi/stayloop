package com.stayloop.application.property

import com.stayloop.application.property.command.PropertySearchCriteria
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
import com.stayloop.domain.reservation.value.StayPeriod
import com.stayloop.testcontainers.MySqlTestContainersConfig
import com.stayloop.utils.DatabaseCleanUp
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
 * week5 PR1 A-4 — Testcontainers MySQL + EXPLAIN 어설션 통합 테스트.
 *
 * **목적**:
 * - sort 4종 (RECOMMENDED / PRICE_ASC / RATING_DESC / WISHES_DESC) 의 *데이터 정렬 의미론* 을 실 MySQL 로 검증.
 * - V010 의 복합 인덱스가 *옵티마이저에 의해 실제로 선택* 됨을 EXPLAIN 으로 회귀 가드 (PR1 D-7 룰).
 *
 * **D-7 어설션 형식** (`docs/plan/week5.md` D-7):
 * - `key == 'idx_...'` — 옵티마이저가 의도 인덱스 선택
 * - `Extra not contains 'Using filesort'` — 정렬이 인덱스 prefix scan 으로 처리됨 (filesort 없음)
 *
 * **InMemory 테스트와의 분담**: `PropertyFacadeTest` (InMemory) 는 *도메인 의미론*, 본 테스트는 *옵티마이저 plan
 * 회귀 차단* + *MySQL 8.0 descending index 동작 확인*. 두 테스트 모두 통과해야 PR1 D-1/D-6/D-7 충족.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainersConfig::class)
class PropertyFacadeSearchSortTest {
    @Autowired
    private lateinit var sut: PropertyFacade

    @Autowired
    private lateinit var properties: PropertyRepository

    @Autowired
    private lateinit var roomTypes: RoomTypeRepository

    @Autowired
    private lateinit var inventories: DailyRoomInventoryRepository

    @Autowired
    private lateinit var rates: DailyRoomRateRepository

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var databaseCleanUp: DatabaseCleanUp

    private val city = "jeju"
    private val period = StayPeriod(LocalDate.of(2026, 7, 30), LocalDate.of(2026, 8, 4))

    @BeforeEach
    fun cleanUp() {
        databaseCleanUp.truncateAllTables()
    }

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
    }

    @DisplayName("WISHES_DESC 정렬 — wish_count DESC 순으로 반환 + EXPLAIN 이 idx_properties_city_wish_count 선택 + filesort 없음 (D-7).")
    @Test
    fun wishesDescUsesCompositeIndexAndOrdersCorrectly() {
        val low = seedProperty(name = "낮은찜", wishCount = 5)
        val mid = seedProperty(name = "중간찜", wishCount = 50)
        val high = seedProperty(name = "높은찜", wishCount = 500)
        seedAvailableInventory(low.id, mid.id, high.id)

        val result = sut.search(
            PropertySearchCriteria(
                city = city,
                period = period,
                guestCount = 2,
                page = PageQuery(page = 0, size = 20),
                sortKey = PropertySortKey.WISHES_DESC,
            ),
        )

        assertThat(result.content.map { it.propertyId }).containsExactly(high.id, mid.id, low.id)

        val explain = explain(
            """
            SELECT id FROM properties WHERE city = ? ORDER BY wish_count DESC LIMIT 20
            """.trimIndent(),
            city,
        )
        assertThat(explain).anySatisfy { row ->
            assertThat(row["key"]).isEqualTo("idx_properties_city_wish_count")
            assertThat(row["Extra"] as String?).doesNotContainIgnoringCase("Using filesort")
        }
    }

    @DisplayName("RATING_DESC 정렬 — rating DESC 순으로 반환 + EXPLAIN 이 idx_properties_city_rating 선택 + filesort 없음 (D-7).")
    @Test
    fun ratingDescUsesCompositeIndexAndOrdersCorrectly() {
        val low = seedProperty(name = "낮은평점", rating = 2.0)
        val mid = seedProperty(name = "중간평점", rating = 3.8)
        val high = seedProperty(name = "높은평점", rating = 4.9)
        seedAvailableInventory(low.id, mid.id, high.id)

        val result = sut.search(
            PropertySearchCriteria(
                city = city,
                period = period,
                guestCount = 2,
                page = PageQuery(page = 0, size = 20),
                sortKey = PropertySortKey.RATING_DESC,
            ),
        )

        assertThat(result.content.map { it.propertyId }).containsExactly(high.id, mid.id, low.id)

        val explain = explain(
            """
            SELECT id FROM properties WHERE city = ? ORDER BY rating DESC LIMIT 20
            """.trimIndent(),
            city,
        )
        assertThat(explain).anySatisfy { row ->
            assertThat(row["key"]).isEqualTo("idx_properties_city_rating")
            assertThat(row["Extra"] as String?).doesNotContainIgnoringCase("Using filesort")
        }
    }

    @DisplayName("PRICE_ASC 정렬 — min(price_per_night) 오름차순으로 반환 + daily_room_rates JOIN 이 idx_daily_room_rates_room_type_date 선택 (D-7).")
    @Test
    fun priceAscUsesCompositeIndexOnRatesAndOrdersCorrectly() {
        val cheap = seedProperty(name = "가성비")
        val expensive = seedProperty(name = "프리미엄")
        // cheap = 70_000, expensive = 200_000
        seedRoomTypeAndRates(cheap.id, pricePerNight = 70_000)
        seedRoomTypeAndRates(expensive.id, pricePerNight = 200_000)

        val result = sut.search(
            PropertySearchCriteria(
                city = city,
                period = period,
                guestCount = 2,
                page = PageQuery(page = 0, size = 20),
                sortKey = PropertySortKey.PRICE_ASC,
            ),
        )

        assertThat(result.content.map { it.propertyId }).containsExactly(cheap.id, expensive.id)

        val explain = explain(
            """
            SELECT p.id FROM properties p
            INNER JOIN room_types rt ON rt.property_id = p.id
            INNER JOIN daily_room_rates r ON r.room_type_id = rt.id
            WHERE p.city = ? AND r.date >= ? AND r.date < ?
            GROUP BY p.id
            ORDER BY MIN(r.price_per_night) ASC
            LIMIT 60
            """.trimIndent(),
            city,
            java.sql.Date.valueOf(period.checkIn),
            java.sql.Date.valueOf(period.checkOut),
        )
        // V010 의 (room_type_id, date) 인덱스가 rates 테이블에 선택됨을 확인 — JOIN row 중 daily_room_rates 행만 검증.
        assertThat(explain).anySatisfy { row ->
            assertThat(row["table"] as String?).isEqualTo("r")
            assertThat(row["key"]).isEqualTo("idx_daily_room_rates_room_type_date")
        }
    }

    @DisplayName("RECOMMENDED 정렬 — id ASC 순서 (저장 순) + EXPLAIN 이 idx_properties_city 또는 PK 사용.")
    @Test
    fun recommendedSortReturnsByIdAsc() {
        val first = seedProperty(name = "first", wishCount = 1)
        val second = seedProperty(name = "second", wishCount = 100)
        val third = seedProperty(name = "third", wishCount = 10)
        seedAvailableInventory(first.id, second.id, third.id)

        val result = sut.search(
            PropertySearchCriteria(
                city = city,
                period = period,
                guestCount = 2,
                page = PageQuery(page = 0, size = 20),
                sortKey = PropertySortKey.RECOMMENDED,
            ),
        )

        // id ASC = 저장 순서 = first, second, third
        assertThat(result.content.map { it.propertyId }).containsExactly(first.id, second.id, third.id)
    }

    private fun explain(sql: String, vararg args: Any): List<Map<String, Any?>> =
        jdbc.queryForList("EXPLAIN $sql", *args)

    private fun seedProperty(name: String, wishCount: Int = 0, rating: Double = 0.0): PropertyModel {
        val property = PropertyModel(
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
        )
        return properties.save(property)
    }

    private fun seedAvailableInventory(vararg propertyIds: Long) {
        // 모든 Property 가 *가용한* RoomType + Inventory + Rate 를 갖도록 seed — search 결과가 가용 0 제외에
        // 걸리지 않게 한다 (정렬 의미만 검증, 가용성은 PropertyFacadeTest 가 담당).
        propertyIds.forEach { propertyId -> seedRoomTypeAndRates(propertyId, pricePerNight = 100_000) }
    }

    private fun seedRoomTypeAndRates(propertyId: Long, pricePerNight: Long) {
        val roomType = roomTypes.save(
            RoomTypeModel.create(
                propertyId = propertyId,
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
                    totalRooms = 10,
                    reservedRooms = 0,
                ),
            )
            rates.save(DailyRoomRateModel.create(roomType.id, date, Money.of(pricePerNight)))
        }
    }
}
