package com.stayloop.application.property

import com.stayloop.application.property.command.PropertySearchCriteria
import com.stayloop.domain.common.value.PageQuery
import com.stayloop.domain.inventory.DailyRoomInventoryModel
import com.stayloop.domain.inventory.DailyRoomInventoryRepository
import com.stayloop.domain.common.value.Money
import com.stayloop.domain.common.value.PageResult
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
import com.stayloop.domain.rate.DailyRoomRateModel
import com.stayloop.domain.rate.DailyRoomRateRepository
import com.stayloop.domain.reservation.value.StayPeriod
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
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDate
import java.time.LocalTime

/**
 * week5 PR4 A-6 — `PropertyFacade.search` cache 통합 테스트 (Testcontainers Redis + MySQL).
 *
 * **검증 의도**:
 * - 첫 search = miss → DB 4-SQL → cache put. 두 번째 = hit (실 Redis round-trip)
 * - 다른 cache key (도시/sort/일자/페이지) 는 분리 — *카디널리티 폭발 위험 트레이드오프* 직접 확인
 * - 명시적 evictPattern 후 새 결과 반영
 *
 * **TTL 만료 자동 검증 X** — 운영 TTL 5분, 테스트로 *기다리기 어렵움*. evict 로 대체.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class)
class PropertySearchCacheTest {
    @Autowired
    private lateinit var sut: PropertyFacade

    @Autowired
    private lateinit var cacheStore: CacheStore

    @Autowired
    private lateinit var properties: PropertyRepository

    @Autowired
    private lateinit var roomTypes: RoomTypeRepository

    @Autowired
    private lateinit var inventories: DailyRoomInventoryRepository

    @Autowired
    private lateinit var rates: DailyRoomRateRepository

    @Autowired
    private lateinit var databaseCleanUp: DatabaseCleanUp

    @Autowired
    private lateinit var redisCleanUp: RedisCleanUp

    private val city = "SEOUL"
    private val period = StayPeriod(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 3))

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

    @DisplayName("search 첫 호출은 miss → DB → cache put, 두 번째는 hit (실 Redis round-trip).")
    @Test
    fun firstCallMissesAndSecondHitsRealRedis() {
        seedAvailableProperty(name = "강남호텔")
        val criteria = standardCriteria(PropertySortKey.RECOMMENDED)

        val first = sut.search(criteria)
        assertThat(first.content).hasSize(1)

        // Redis 에 직접 search:result:* 키가 적재되어야 함
        val cacheKey = "search:result:$city:RECOMMENDED:${period.checkIn}:${period.checkOut}:2:0:20"
        val cached = cacheStore.get(cacheKey, PageResult::class.java)
        assertThat(cached).isNotNull

        val second = sut.search(criteria)
        assertThat(second.content.map { it.propertyId }).containsExactlyElementsOf(first.content.map { it.propertyId })
    }

    @DisplayName("search cache 가 hit 인 상태에서 새 Property 가 추가되어도 응답은 stale (5분 TTL 의 trade-off 직접 증거).")
    @Test
    fun cacheHitReturnsStaleResultUntilEvict() {
        seedAvailableProperty(name = "P1")
        val criteria = standardCriteria(PropertySortKey.RECOMMENDED)
        val first = sut.search(criteria)
        assertThat(first.content).hasSize(1)

        // 새 Property 추가 — DB 상태 변경
        seedAvailableProperty(name = "P2")

        val second = sut.search(criteria)
        // 새 Property 가 응답에 *없음* — cache stale
        assertThat(second.content).hasSize(1)

        // 명시적 evictPattern 후에는 새 상태 반영
        cacheStore.evictPattern("search:result:*")
        val third = sut.search(criteria)
        assertThat(third.content).hasSize(2)
    }

    @DisplayName("search cache key 가 다르면 (sort 분리) 각각 별도 fetch → 별도 응답 (카디널리티 분리 직접 증거).")
    @Test
    fun differentSortYieldsSeparateCacheEntries() {
        val p1 = seedAvailableProperty(name = "P1", wishCount = 1)
        val p2 = seedAvailableProperty(name = "P2", wishCount = 100)

        val recommended = sut.search(standardCriteria(PropertySortKey.RECOMMENDED)).content.map { it.propertyId }
        val wishesDesc = sut.search(standardCriteria(PropertySortKey.WISHES_DESC)).content.map { it.propertyId }

        // RECOMMENDED = id ASC, WISHES_DESC = wishCount 큰 순 — 두 응답이 *다름* → 두 cache 엔트리 분리 박제
        assertThat(recommended).containsExactly(p1.id, p2.id)
        assertThat(wishesDesc).containsExactly(p2.id, p1.id)
    }

    private fun standardCriteria(sortKey: PropertySortKey) = PropertySearchCriteria(
        city = city,
        period = period,
        guestCount = 2,
        page = PageQuery(page = 0, size = 20),
        sortKey = sortKey,
    )

    private fun seedAvailableProperty(name: String, wishCount: Int = 0): PropertyModel {
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
                wishCount = wishCount,
            ),
        )
        val roomType = roomTypes.save(
            RoomTypeModel.create(
                propertyId = property.id,
                name = Name("$name-스탠다드"),
                guestCount = GuestCount(base = 2, max = 4),
                bedConfig = BedConfig.of(BedType.DOUBLE to 1),
            ),
        )
        period.datesToReserve().forEach { date ->
            inventories.save(
                DailyRoomInventoryModel.create(
                    roomTypeId = roomType.id,
                    date = date,
                    totalRooms = 5,
                    reservedRooms = 0,
                ),
            )
            rates.save(DailyRoomRateModel.create(roomType.id, date, Money.of(100_000)))
        }
        return property
    }
}
