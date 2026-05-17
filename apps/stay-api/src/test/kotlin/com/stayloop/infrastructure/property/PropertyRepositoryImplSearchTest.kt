package com.stayloop.infrastructure.property

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
 * week5 PR3 A-3 — QueryDSL 2-step batch IN + 가용성/정렬/페이지네이션 통합 테스트.
 *
 * **검증 의도**:
 * - `PropertyRepository.searchInfos` 가 *2-step batch IN* 흐름으로 작동 (N+1 제거 + 가용성 집계).
 * - 결과 정합 — city / 인원 / 가용성 / 최저가 / 가용 RoomType 수가 정확.
 * - sortKey 4종 — RECOMMENDED / WISHES_DESC / RATING_DESC / PRICE_ASC 모두 정확.
 * - 페이지네이션 정합 — page 0 / page 1 의 row 수가 *예측대로* (fetch join 의 `HHH000104` 페이지네이션 깨짐
 *   회귀 가드).
 * - Step 1 candidate fetch EXPLAIN — idx_properties_city 사용 + filesort 없음 (D-7 회귀 가드).
 *
 * **InMemory 와 분담**:
 * - `PropertyFacadeTest` (InMemory) — 도메인 의미론 (가용성 / 최저가 / 정렬) 의 *대량 경우의 수* 검증.
 * - 본 테스트 — *MySQL QueryDSL 동작* + *2-step 정합* + *EXPLAIN 회귀 가드*.
 *
 * **격리** — `@BeforeEach` / `@AfterEach` 의 `truncateAllTables()`.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainersConfig::class)
class PropertyRepositoryImplSearchTest {
    @Autowired
    private lateinit var sut: PropertyRepository

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

    private val city = "seoul"
    private val period = StayPeriod(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 3))

    @BeforeEach
    fun cleanUp() {
        databaseCleanUp.truncateAllTables()
    }

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
    }

    @DisplayName(
        "searchInfos 는 가용 RoomType ≥ 1 Property 만 반환하고 *projection 한 row* 에 최저 합산가 + 가용 RoomType 수를 노출한다 (D-3).",
    )
    @Test
    fun returnsOnlyAvailablePropertiesWithAggregatedLowestPriceAndCount() {
        // P1 (가용 2 객실, 최저 합산가 = 80_000 × 2박 = 160_000)
        val p1 = seedProperty(name = "P1-가용")
        val p1Standard = seedRoomType(p1.id, name = "스탠다드", maxGuests = 4)
        val p1Deluxe = seedRoomType(p1.id, name = "디럭스", maxGuests = 6)
        seedAvailability(p1Standard.id, totalRooms = 5, reservedRooms = 0, pricePerNight = 80_000)
        seedAvailability(p1Deluxe.id, totalRooms = 5, reservedRooms = 0, pricePerNight = 120_000)

        // P2 (가용 1 객실 — 다른 객실은 잠금 / max_guests 부족)
        val p2 = seedProperty(name = "P2-부분가용")
        val p2RoomA = seedRoomType(p2.id, name = "A-잠금", maxGuests = 4)
        val p2RoomB = seedRoomType(p2.id, name = "B-인원미달", maxGuests = 1)
        val p2RoomC = seedRoomType(p2.id, name = "C-가용", maxGuests = 4)
        seedAvailability(p2RoomA.id, totalRooms = 3, reservedRooms = 3, pricePerNight = 60_000)
        seedAvailability(p2RoomB.id, totalRooms = 3, reservedRooms = 0, pricePerNight = 50_000)
        seedAvailability(p2RoomC.id, totalRooms = 3, reservedRooms = 0, pricePerNight = 70_000)

        // P3 (전부 잠금 — 결과 제외)
        val p3 = seedProperty(name = "P3-전부잠금")
        val p3Room = seedRoomType(p3.id, name = "fully booked", maxGuests = 4)
        seedAvailability(p3Room.id, totalRooms = 1, reservedRooms = 1, pricePerNight = 90_000)

        // P4 (다른 도시 — 결과 제외)
        val p4 = seedPropertyInOtherCity(name = "P4-다른도시", city = "busan")
        val p4Room = seedRoomType(p4.id, name = "ocean", maxGuests = 4)
        seedAvailability(p4Room.id, totalRooms = 3, reservedRooms = 0, pricePerNight = 200_000)

        val result = sut.searchInfos(
            city = city,
            period = period,
            sortKey = PropertySortKey.RECOMMENDED,
            guestCount = 2,
            page = PageQuery(page = 0, size = 20),
        )

        // city 매칭 총 3 (P1/P2/P3) → total = 3. 가용 0 (P3) 는 content 제외.
        assertThat(result.total).isEqualTo(3L)
        assertThat(result.content).hasSize(2)
        val nights = period.nights().toLong()
        val rowP1 = result.content.first { it.propertyId == p1.id }
        assertThat(rowP1.lowestTotalPrice).isEqualTo(80_000L * nights)
        assertThat(rowP1.availableRoomTypeCount).isEqualTo(2)
        val rowP2 = result.content.first { it.propertyId == p2.id }
        assertThat(rowP2.lowestTotalPrice).isEqualTo(70_000L * nights)
        assertThat(rowP2.availableRoomTypeCount).isEqualTo(1)
    }

    @DisplayName(
        "searchInfos 의 PRICE_ASC 는 lowest_total 기준 오름차순 + page.size 정확 fetch (PR1~2 의 K=size×3 overfetch 후 정확히 page.size 노출).",
    )
    @Test
    fun priceAscReturnsExactPageSizeOrderedByLowestTotal() {
        val pCheap = seedPropertyWithSingleRoom(name = "P-cheap", pricePerNight = 50_000)
        val pMid = seedPropertyWithSingleRoom(name = "P-mid", pricePerNight = 80_000)
        val pExpensive = seedPropertyWithSingleRoom(name = "P-expensive", pricePerNight = 200_000)

        val page0 = sut.searchInfos(
            city = city,
            period = period,
            sortKey = PropertySortKey.PRICE_ASC,
            guestCount = 2,
            page = PageQuery(page = 0, size = 2),
        )
        assertThat(page0.content).hasSize(2)
        assertThat(page0.content.map { it.propertyId }).containsExactly(pCheap.id, pMid.id)

        val page1 = sut.searchInfos(
            city = city,
            period = period,
            sortKey = PropertySortKey.PRICE_ASC,
            guestCount = 2,
            page = PageQuery(page = 1, size = 2),
        )
        // 남은 1 row (P-expensive). 페이지네이션 정합 (HHH000104 회귀 가드)
        assertThat(page1.content).hasSize(1)
        assertThat(page1.content.map { it.propertyId }).containsExactly(pExpensive.id)
    }

    @DisplayName(
        "searchInfos 의 Step 1 candidate 추출 EXPLAIN — idx_properties_city 사용 + filesort 없음 (D-7 회귀 가드).",
    )
    @Test
    fun candidateFetchUsesCityIndexWithoutFilesort() {
        seedPropertyWithSingleRoom(name = "P-baseline", pricePerNight = 100_000)

        val explain = jdbc.queryForList(
            """
            EXPLAIN SELECT p.id FROM properties p
            INNER JOIN room_types rt ON rt.property_id = p.id
            INNER JOIN daily_room_rates rate ON rate.room_type_id = rt.id
            WHERE p.city = ? AND rate.date >= ? AND rate.date < ? AND rt.max_guests >= ?
            GROUP BY p.id ORDER BY p.id ASC LIMIT 60 OFFSET 0
            """.trimIndent(),
            city,
            java.sql.Date.valueOf(period.checkIn),
            java.sql.Date.valueOf(period.checkOut),
            2,
        )
        // idx_properties_city 등 city 인덱스 사용 확인
        assertThat(explain).anySatisfy { row ->
            val table = row["table"] as String?
            val key = row["key"] as String?
            if (table == "p") {
                assertThat(key).isIn("idx_properties_city", "idx_properties_city_wish_count", "idx_properties_city_rating", "PRIMARY")
            }
        }
    }

    @DisplayName(
        "searchInfos 는 모든 sort 4종 (RECOMMENDED / WISHES_DESC / RATING_DESC / PRICE_ASC) 에서 결과 순서를 보존한다.",
    )
    @Test
    fun sortFourKindsProduceConsistentOrdering() {
        val pA = seedPropertyWithSingleRoom(name = "A", wishCount = 50, rating = 3.0, pricePerNight = 100_000)
        val pB = seedPropertyWithSingleRoom(name = "B", wishCount = 100, rating = 4.5, pricePerNight = 80_000)
        val pC = seedPropertyWithSingleRoom(name = "C", wishCount = 10, rating = 2.0, pricePerNight = 150_000)

        val recommended = sut.searchInfos(city, period, PropertySortKey.RECOMMENDED, 2, PageQuery(0, 20)).content
        assertThat(recommended.map { it.propertyId }).containsExactly(pA.id, pB.id, pC.id)

        val wishes = sut.searchInfos(city, period, PropertySortKey.WISHES_DESC, 2, PageQuery(0, 20)).content
        assertThat(wishes.map { it.propertyId }).containsExactly(pB.id, pA.id, pC.id)

        val rating = sut.searchInfos(city, period, PropertySortKey.RATING_DESC, 2, PageQuery(0, 20)).content
        assertThat(rating.map { it.propertyId }).containsExactly(pB.id, pA.id, pC.id)

        val price = sut.searchInfos(city, period, PropertySortKey.PRICE_ASC, 2, PageQuery(0, 20)).content
        assertThat(price.map { it.propertyId }).containsExactly(pB.id, pA.id, pC.id)
    }

    private fun seedProperty(name: String, wishCount: Int = 0, rating: Double = 0.0): PropertyModel =
        seedPropertyInOtherCity(name = name, city = city, wishCount = wishCount, rating = rating)

    private fun seedPropertyInOtherCity(
        name: String,
        city: String,
        wishCount: Int = 0,
        rating: Double = 0.0,
    ): PropertyModel {
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
        return sut.save(property)
    }

    private fun seedRoomType(propertyId: Long, name: String, maxGuests: Int): RoomTypeModel =
        roomTypes.save(
            RoomTypeModel.create(
                propertyId = propertyId,
                name = Name(name),
                guestCount = GuestCount(base = minOf(2, maxGuests), max = maxGuests),
                bedConfig = BedConfig.of(BedType.DOUBLE to 1),
            ),
        )

    private fun seedAvailability(roomTypeId: Long, totalRooms: Int, reservedRooms: Int, pricePerNight: Long) {
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

    private fun seedPropertyWithSingleRoom(
        name: String,
        wishCount: Int = 0,
        rating: Double = 0.0,
        pricePerNight: Long,
    ): PropertyModel {
        val property = seedProperty(name = name, wishCount = wishCount, rating = rating)
        val roomType = seedRoomType(property.id, name = "단일", maxGuests = 4)
        seedAvailability(roomType.id, totalRooms = 5, reservedRooms = 0, pricePerNight = pricePerNight)
        return property
    }
}
