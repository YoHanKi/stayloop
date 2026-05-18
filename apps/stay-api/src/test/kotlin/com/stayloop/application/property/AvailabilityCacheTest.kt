package com.stayloop.application.property

import com.stayloop.application.property.command.RoomAvailabilityQuery
import com.stayloop.application.reservation.ReservationFacade
import com.stayloop.application.reservation.command.ReserveCommand
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
import com.stayloop.domain.reservation.value.GuestInfo
import com.stayloop.domain.reservation.value.StayPeriod
import com.stayloop.domain.user.PasswordEncoder
import com.stayloop.domain.user.UserModel
import com.stayloop.domain.user.UserRepository
import com.stayloop.domain.user.value.BirthDate
import com.stayloop.domain.user.value.Email
import com.stayloop.domain.user.value.LoginId
import com.stayloop.domain.user.value.PhoneNumber
import com.stayloop.infrastructure.cache.CacheStore
import com.stayloop.infrastructure.cache.RoomDailyAvailability
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
import java.time.format.DateTimeFormatter
import com.stayloop.domain.user.value.Name as UserName

/**
 * week5 PR4 A-6 — `PropertyFacade.getAvailableRooms` (cache 통과) + `ReservationFacade.reserve` /
 * `cancel` (afterCommit evict) 의 통합 흐름 검증.
 *
 * **Testcontainers Redis + MySQL** — `@Transactional` commit 의 실제 동작 + Lettuce round-trip + Jackson
 * 직렬화 (`RoomDailyAvailability`) 를 한 번에 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class)
class AvailabilityCacheTest {
    @Autowired
    private lateinit var sut: PropertyFacade

    @Autowired
    private lateinit var reservationFacade: ReservationFacade

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

    private val period = StayPeriod(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 3))
    private val dateFormat = DateTimeFormatter.ofPattern("yyyyMMdd")

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

    @DisplayName("getAvailableRooms 첫 호출은 각 일자별 miss → DB → 일자별 cache put.")
    @Test
    fun firstCallSeedsPerDateCacheEntries() {
        val seeded = seedAvailableProperty()
        val (property, roomType) = seeded

        val first = sut.getAvailableRooms(
            RoomAvailabilityQuery(propertyId = property.id, period = period, guestCount = 2),
        )
        assertThat(first.single().available).isTrue()

        // 각 일자별 별도 cache key 가 생성되어야 함
        period.datesToReserve().forEach { date ->
            val key = "availability:${roomType.id}:${date.format(dateFormat)}"
            val cached = cacheStore.get(key, RoomDailyAvailability::class.java)
            assertThat(cached).isNotNull
            assertThat(cached!!.totalRooms).isEqualTo(5)
            assertThat(cached.reservedRooms).isEqualTo(0)
            assertThat(cached.pricePerNight).isEqualTo(100_000L)
        }
    }

    @DisplayName("ReservationFacade.reserve @Transactional commit 후 afterCommit 이 변경된 RT × dates 의 cache 를 evict 한다.")
    @Test
    fun reserveAfterCommitEvictsAvailabilityCache() {
        val (property, roomType) = seedAvailableProperty()
        val user = seedUser("userone")

        // cache 적재
        sut.getAvailableRooms(
            RoomAvailabilityQuery(propertyId = property.id, period = period, guestCount = 2),
        )
        period.datesToReserve().forEach { date ->
            val key = "availability:${roomType.id}:${date.format(dateFormat)}"
            assertThat(cacheStore.get(key, RoomDailyAvailability::class.java)).isNotNull
        }

        // reserve — @Transactional commit 후 afterCommit evict 발화
        reservationFacade.reserve(
            ReserveCommand(
                userId = user.loginId,
                propertyId = property.id,
                roomTypeId = roomType.id,
                period = period,
                guestCount = 2,
                guest = GuestInfo(name = "홍길동", phoneNumber = PhoneNumber("010-1234-5678")),
            ),
        )

        // 모든 일자 cache 가 evict 됨
        period.datesToReserve().forEach { date ->
            val key = "availability:${roomType.id}:${date.format(dateFormat)}"
            assertThat(cacheStore.get(key, RoomDailyAvailability::class.java)).isNull()
        }

        // 다음 getAvailableRooms 는 새 inventory (reserved+1) 를 cache 에 적재
        val refreshed = sut.getAvailableRooms(
            RoomAvailabilityQuery(propertyId = property.id, period = period, guestCount = 2),
        )
        assertThat(refreshed.single().available).isTrue() // 5 - 1 = 4 잔여
        period.datesToReserve().forEach { date ->
            val key = "availability:${roomType.id}:${date.format(dateFormat)}"
            val cached = cacheStore.get(key, RoomDailyAvailability::class.java)
            assertThat(cached!!.reservedRooms).isEqualTo(1)
        }
    }

    private fun seedAvailableProperty(): Pair<PropertyModel, RoomTypeModel> {
        val property = properties.save(
            PropertyModel(
                name = Name("강남호텔"),
                category = PropertyCategory.HOTEL,
                description = null,
                address = Address(city = "SEOUL", fullAddress = "강남구 강남호텔"),
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
        return property to roomType
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
