package com.stayloop.application.property

import com.stayloop.application.wishlist.WishlistFacade
import com.stayloop.domain.inventory.DailyRoomInventoryModel
import com.stayloop.domain.inventory.DailyRoomInventoryRepository
import com.stayloop.domain.common.value.Money
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
import com.stayloop.domain.rate.DailyRoomRateModel
import com.stayloop.domain.rate.DailyRoomRateRepository
import com.stayloop.domain.reservation.value.StayPeriod
import com.stayloop.domain.user.PasswordEncoder
import com.stayloop.domain.user.UserModel
import com.stayloop.domain.user.UserRepository
import com.stayloop.domain.user.value.BirthDate
import com.stayloop.domain.user.value.Email
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
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDate
import java.time.LocalTime
import com.stayloop.domain.user.value.Name as UserName

/**
 * week5 PR4 A-6 — `PropertyFacade.getDetail` + `WishlistFacade.wish` 의 cache 흐름 통합 테스트.
 *
 * **Testcontainers Redis + MySQL** — 단위 테스트가 InMemory 더블로 검증한 *cache 분기* 를 *실 환경* 에서
 * 한 번 더 확인. 특히:
 * - GenericJackson2JsonRedisSerializer 가 application Info (PropertyDetailInfo) 를 round-trip 직렬화하는지
 * - WishlistFacade.wish 의 `@Transactional` + `afterCommit` 이 *실제 TX commit 후* evict 를 발화하는지
 *   (단위 테스트의 `isSynchronizationActive() == false` 분기와 다른 경로)
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class)
class PropertyDetailCacheTest {
    @Autowired
    private lateinit var sut: PropertyFacade

    @Autowired
    private lateinit var wishlist: WishlistFacade

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
    private lateinit var users: UserRepository

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

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

    @DisplayName("getDetail 첫 호출은 miss → DB → cache put, 두 번째는 hit (실 Redis round-trip).")
    @Test
    fun firstCallMissesAndSecondHitsRealRedis() {
        val property = seedProperty(name = "강남호텔")

        val first = sut.getDetail(property.id)
        assertThat(first.propertyId).isEqualTo(property.id)
        assertThat(first.name).isEqualTo("강남호텔")

        // 두 번째 호출 — cache hit. *Redis 에 직접 put 된 key 가 있어야* 함.
        val cacheKey = "property:detail:${property.id}"
        val cached = cacheStore.get(cacheKey, PropertyDetailInfo::class.java)
        assertThat(cached).isNotNull
        assertThat(cached!!.name).isEqualTo("강남호텔")

        // 응답 정합 — 두 호출의 결과가 동일
        val second = sut.getDetail(property.id)
        assertThat(second).isEqualTo(first)
    }

    @DisplayName("wish 호출 → @Transactional commit 후 property:detail:{id} cache 가 실제로 evict 된다.")
    @Test
    fun wishAfterCommitEvictsDetailCache() {
        val property = seedProperty(name = "강남호텔")
        val user = seedUser("userone")
        // detail cache 적재
        sut.getDetail(property.id)
        val cacheKey = "property:detail:${property.id}"
        assertThat(cacheStore.get(cacheKey, PropertyDetailInfo::class.java)).isNotNull

        wishlist.wish(user.loginId, property.id)

        // wish 의 @Transactional commit 후 afterCommit 가 호출되어 cache evict 된 상태.
        assertThat(cacheStore.get(cacheKey, PropertyDetailInfo::class.java)).isNull()
    }

    @DisplayName("wish 두 번째 호출 (멱등 noop) 은 cache 를 evict 하지 않는다 (실제 변경 없음).")
    @Test
    fun idempotentWishKeepsDetailCache() {
        val property = seedProperty(name = "강남호텔")
        val user = seedUser("userone")
        wishlist.wish(user.loginId, property.id) // 1차 — wishCount 1 + cache evict
        sut.getDetail(property.id) // cache 재적재
        val cacheKey = "property:detail:${property.id}"
        assertThat(cacheStore.get(cacheKey, PropertyDetailInfo::class.java)).isNotNull

        wishlist.wish(user.loginId, property.id) // 2차 — 멱등 noop

        // wishCount 가 안 바뀌었으므로 cache 도 그대로
        assertThat(cacheStore.get(cacheKey, PropertyDetailInfo::class.java)).isNotNull
    }

    private fun seedProperty(name: String): PropertyModel {
        val property = properties.save(
            PropertyModel(
                name = Name(name),
                category = PropertyCategory.HOTEL,
                description = null,
                address = Address(city = "SEOUL", fullAddress = "강남구 $name"),
                amenities = Amenities.EMPTY,
                policy = PropertyPolicy(
                    checkInTime = LocalTime.of(15, 0),
                    checkOutTime = LocalTime.of(11, 0),
                    cancellation = CancellationPolicy(type = CancellationType.FREE_UNTIL, freeUntilDaysBefore = 3),
                ),
            ),
        )
        val roomType = roomTypes.save(
            RoomTypeModel.create(
                propertyId = property.id,
                name = Name("스탠다드"),
                guestCount = GuestCount(base = 2, max = 4),
                bedConfig = BedConfig.of(BedType.DOUBLE to 1),
            ),
        )
        val period = StayPeriod(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 3))
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

    private fun seedUser(loginId: String): UserModel = users.save(
        UserModel.create(
            loginId = LoginId(loginId),
            rawPassword = "Abcd1234!",
            name = UserName("홍길동"),
            birthDate = BirthDate(LocalDate.of(2000, 1, 1)),
            email = Email("$loginId@stayloop.io"),
            phoneNumber = PhoneNumber("010-1234-5678"),
            encoder = passwordEncoder,
        ),
    )
}
