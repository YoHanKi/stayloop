package com.stayloop.application.wishlist

import com.stayloop.application.property.PropertyFacade
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
 * week5 PR2 Phase A-1 — `WishesDescConsistencyTest` (`docs/plan/week5.md` PR2 A-1).
 *
 * **검증 의도** — D-2 (W-1 비정규화) 채택안의 *진짜 채택 조건* 박제.
 *
 * `properties.wish_count` 컬럼이 atomic UPDATE 로 갱신된다는 것은 week4 ③ Phase C / `ConcurrentWishToggleTest`
 * 가 이미 박제. 본 테스트는 그 다음 단계 — *wish/unwish 의 atomic UPDATE 가 **별도 TX 의 WISHES_DESC 검색
 * 쿼리에 commit 후 visible*** 한지 검증. 즉 W-1 채택의 *검색 노출 정합* — atomic UPDATE 가 잘 돌더라도
 * 검색이 그 변화를 *볼 수 없으면* 비정규화 채택 자체가 무의미.
 *
 * **기존 테스트와의 분담** (`docs/plan/week5-b.md` §6 Loop 5):
 * - `ConcurrentWishToggleTest` — 동시성 시나리오, 최종 row/wish_count 정합만 (정렬 의미 검증 X).
 * - `PropertyFacadeSearchSortTest` — *pre-seeded* data 의 정렬 의미론 + EXPLAIN 어설션.
 * - `WishlistFacadeTest` — InMemory 더블의 wish/unwish 카운트 변화만.
 * - **본 테스트** — *write (`wish`/`unwish`) → read (검색)* 의 흐름 정합.
 *
 * **시나리오 단순화 정책** — wish_count 동률 시의 *tie-break 결정성* 은 검증하지 않는다 (운영 SQL
 * `ORDER BY wish_count DESC` 만 — secondary key 없어 동률 비결정, verify-code §4 영구 한계 박제).
 * 본 테스트는 *명백 비동률* 상태에서의 *반영 시점* 만 검증.
 *
 * **InMemory 더블 사용 금지** — atomic UPDATE / TX commit visibility 의미론은 InnoDB 만의 동작
 * (verify-code R9). 본 테스트는 Testcontainers MySQL 8.0 으로만 의미가 있다.
 *
 * **격리** — `@BeforeEach` / `@AfterEach` 의 `truncateAllTables()`.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class)
class WishesDescConsistencyTest {
    @Autowired
    private lateinit var sut: PropertyFacade

    @Autowired
    private lateinit var wishlist: WishlistFacade

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

    private val city = "seoul"
    private val period = StayPeriod(LocalDate.of(2026, 7, 30), LocalDate.of(2026, 8, 4))

    @BeforeEach
    fun cleanUp() {
        databaseCleanUp.truncateAllTables()
        // PR4 search cache (TTL 5m) 가 wish/unwish 의 WISHES_DESC 회귀 검증을 가리지 않게 매 테스트 cache 비움.
        // 운영의 *cache stale 5m 수용* trade-off 와 분리 — 본 테스트는 DB→search 노출 정합만 검증.
        redisCleanUp.truncateAll()
    }

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
        redisCleanUp.truncateAll()
    }

    @DisplayName(
        "wish 호출 → WISHES_DESC 검색 결과 순서에 commit 후 즉시 반영된다 " +
            "(W-1 비정규화 채택의 검색 노출 정합, D-2).",
    )
    @Test
    fun wishCommitVisibleInNextSearchInWishesDescOrder() {
        // given — 3 Property 모두 wish_count = 0 + 가용 inventory + 사용자 2명
        val p1 = seedPropertyWithAvailability(name = "P1-강남호텔")
        val p2 = seedPropertyWithAvailability(name = "P2-서울게스트하우스")
        val p3 = seedPropertyWithAvailability(name = "P3-광화문호텔")
        val user1 = seedUser(loginId = "userone")
        val user2 = seedUser(loginId = "usertwo")

        // **search cache 우회 정책 (본 테스트 의도)**: 매 검색 직전 redis 비움 — *DB→search 노출 정합* 만
        // 검증. PR4 search cache (TTL 5m) 의 stale 수용은 별도 trade-off 박제 (KDoc 정합).

        // when (1) — user1 이 P2 에 wish → P2.wish_count = 1
        wishlist.wish(user1.loginId, p2.id)
        redisCleanUp.truncateAll()

        // then (1) — 별도 TX 의 WISHES_DESC 검색에 즉시 visible. P2 가 1등.
        val afterFirstWish = sut.search(searchCriteria(PropertySortKey.WISHES_DESC)).content
        assertThat(afterFirstWish.first().propertyId).isEqualTo(p2.id)
        assertThat(afterFirstWish.first().wishCount).isEqualTo(1)
        assertThat(persistedWishCount(p2.id)).isEqualTo(1)

        // when (2) — user2 도 P2 에 wish → P2.wish_count = 2 (서로 다른 사용자, wishlist UNIQUE 통과)
        wishlist.wish(user2.loginId, p2.id)
        redisCleanUp.truncateAll()

        // then (2) — P2 여전히 1등 (2 > 0)
        val afterSecondWish = sut.search(searchCriteria(PropertySortKey.WISHES_DESC)).content
        assertThat(afterSecondWish.first().propertyId).isEqualTo(p2.id)
        assertThat(afterSecondWish.first().wishCount).isEqualTo(2)
        assertThat(persistedWishCount(p2.id)).isEqualTo(2)

        // when (3) — user1 이 P3 에 wish → P3.wish_count = 1. P2 = 2 > P3 = 1 > P1 = 0 으로 *명백 비동률*.
        wishlist.wish(user1.loginId, p3.id)
        redisCleanUp.truncateAll()

        // then (3) — 검색 결과 순서가 정확히 P2 → P3 → P1 (명백 비동률, 결정적)
        val afterThirdWish = sut.search(searchCriteria(PropertySortKey.WISHES_DESC)).content
        assertThat(afterThirdWish.map { it.propertyId }).containsExactly(p2.id, p3.id, p1.id)
        assertThat(afterThirdWish.map { it.wishCount }).containsExactly(2, 1, 0)

        // when (4) — user1 이 P2 에 unwish → P2.wish_count = 1. atomic decrement `WHERE wish_count > 0` 정합.
        wishlist.unwish(user1.loginId, p2.id)
        redisCleanUp.truncateAll()

        // then (4) — P2 = 1 = P3, P1 = 0. P2 vs P3 의 tie-break 는 비결정 (KDoc 정책) — 두 properties 가
        // *상위 2등 안에* 있으면 OK, 마지막이 P1 인 것만 검증.
        val afterUnwish = sut.search(searchCriteria(PropertySortKey.WISHES_DESC)).content
        assertThat(afterUnwish.map { it.propertyId }.take(2)).containsExactlyInAnyOrder(p2.id, p3.id)
        assertThat(afterUnwish.last().propertyId).isEqualTo(p1.id)
        assertThat(persistedWishCount(p2.id)).isEqualTo(1)
        assertThat(persistedWishCount(p3.id)).isEqualTo(1)
        assertThat(persistedWishCount(p1.id)).isEqualTo(0)
    }

    @DisplayName(
        "unwish 멱등 — 찜되지 않은 사용자의 unwish 호출은 wish_count 를 변화시키지 않고 검색 순서도 유지한다.",
    )
    @Test
    fun unwishByNonWisherIsNoopForSearchOrder() {
        // given — P1.wish_count=1 (user1 이 미리 wish), P2.wish_count=0
        val p1 = seedPropertyWithAvailability(name = "P1")
        val p2 = seedPropertyWithAvailability(name = "P2")
        val user1 = seedUser(loginId = "userone")
        val user2 = seedUser(loginId = "usertwo")
        wishlist.wish(user1.loginId, p1.id)

        // when — user2 (찜한 적 없음) 가 P1 에 unwish 호출 (멱등 noop, AC-6)
        wishlist.unwish(user2.loginId, p1.id)
        // 본 테스트 의도 (DB→search 노출 정합) 보존 위해 search cache 우회
        redisCleanUp.truncateAll()

        // then — wish_count 불변, 검색 순서 그대로 P1 → P2.
        val result = sut.search(searchCriteria(PropertySortKey.WISHES_DESC)).content
        assertThat(result.map { it.propertyId }).containsExactly(p1.id, p2.id)
        assertThat(persistedWishCount(p1.id)).isEqualTo(1)
        assertThat(persistedWishCount(p2.id)).isEqualTo(0)
    }

    private fun searchCriteria(sortKey: PropertySortKey) = PropertySearchCriteria(
        city = city,
        period = period,
        guestCount = 2,
        page = PageQuery(page = 0, size = 20),
        sortKey = sortKey,
    )

    private fun persistedWishCount(propertyId: Long): Int =
        properties.findById(propertyId)?.wishCount ?: error("Property $propertyId 누락")

    private fun seedPropertyWithAvailability(name: String): PropertyModel {
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
                wishCount = 0,
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
                    totalRooms = 10,
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
