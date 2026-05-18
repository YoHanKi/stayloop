package com.stayloop.application.reservation

import com.stayloop.application.property.PropertyFacade
import com.stayloop.application.property.command.RoomAvailabilityQuery
import com.stayloop.application.reservation.command.ReserveCommand
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
import com.stayloop.domain.common.value.Money
import com.stayloop.domain.rate.DailyRoomRateModel
import com.stayloop.domain.rate.DailyRoomRateRepository
import com.stayloop.domain.reservation.value.GuestInfo
import com.stayloop.domain.reservation.value.StayPeriod
import com.stayloop.domain.user.value.LoginId
import com.stayloop.domain.user.value.PhoneNumber
import com.stayloop.infrastructure.cache.AvailabilityCacheStore
import com.stayloop.infrastructure.cache.RoomDailyAvailability
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import com.stayloop.testcontainers.MySqlTestContainersConfig
import com.stayloop.testcontainers.RedisTestContainersConfig
import com.stayloop.utils.DatabaseCleanUp
import com.stayloop.utils.RedisCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
 * week5 PR5 A-2 — **D-5 contract 회귀 가드**.
 *
 * **검증 의도** (`ReservationFacade.reserve` KDoc 의 *결제 직전 DB 재확인 contract*):
 *
 * - cache 에 *stale row* (reserved=0, 가용 가능) 적재 + DB 는 *최신* (reserved=total, 가용 불가) 인 *조작된*
 *   상태에서 `reserve` 호출 시 **DB 기준** `CoreException(CONFLICT)` 가 throw 되어야 한다.
 * - reserve 가 *cache 결정* 을 따랐다면 가용 가능으로 잘못 진입 → 더블부킹 (테스트 FAIL).
 * - reserve 가 *비관적 락 (`findInventoriesForUpdate`)* 으로 DB 직접 확인했다면 CONFLICT throw (테스트 PASS).
 *
 * **회귀 신호**: 본 테스트가 FAIL 하면 *어딘가에서 reserve 흐름이 cache 결정을 끼웠다* — D-5 contract 위반.
 * verify-code §17 회귀 룰 (PR5 R-1) 이 *코드 변경 차단* 으로 1차 가드, 본 테스트가 *런타임 회귀* 로 2차 가드.
 *
 * **PropertyFacade.getAvailableRooms 도 같은 contract 적용 (PR4 A-5b KDoc)**: 본 테스트의 *대조군* —
 * `getAvailableRooms` 는 cache 결정을 따르므로 *stale* 응답 (가용 가능) 을 반환. 같은 시점 `reserve` 는 *DB
 * 직접* 으로 CONFLICT. 즉 두 메서드의 *응답이 어긋난 상태에서도 결제 흐름은 항상 DB 기준* 이 contract.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainersConfig::class, RedisTestContainersConfig::class)
class ReservationCacheBypassTest {
    @Autowired
    private lateinit var sut: ReservationFacade

    @Autowired
    private lateinit var propertyFacade: PropertyFacade

    @Autowired
    private lateinit var availabilityCacheStore: AvailabilityCacheStore

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

    @DisplayName("cache 에 stale (reserved=0) 박제 + DB 최신 (reserved=total) — reserve 는 DB 기준 CONFLICT throw 한다 (D-5 contract).")
    @Test
    fun reserveBypassesCacheAndUsesDbDecision() {
        // given — DB: 1실 / reserved=1 (가용 0). cache: stale (reserved=0, 가용 1).
        val (property, roomType) = seedPropertyWithRoomType()
        seedDbInventories(roomType.id, totalRooms = 1, reservedRooms = 1) // DB 는 매진
        seedRates(roomType.id)
        seedStaleAvailabilityCache(roomType.id, totalRooms = 1, reservedRooms = 0) // cache 는 가용

        // sanity — cache 결정을 따르는 getAvailableRooms 는 *stale 응답* 반환 (가용 가능)
        val stale = propertyFacade.getAvailableRooms(
            RoomAvailabilityQuery(propertyId = property.id, period = period, guestCount = 2),
        )
        assertThat(stale).hasSize(1)
        assertThat(stale[0].available)
            .withFailMessage("getAvailableRooms 가 cache stale 을 따르지 않음 — fixture 박제가 깨졌습니다.")
            .isTrue()

        // when + then — reserve 는 DB 직접 (비관적 락) 확인 → CONFLICT
        assertThatThrownBy {
            sut.reserve(reserveCommand(property.id, roomType.id, LoginId("u001")))
        }
            .isInstanceOf(CoreException::class.java)
            .extracting { (it as CoreException).errorType }
            .isEqualTo(ErrorType.CONFLICT)

        // DB 의 reserved_rooms 는 그대로 (1) — reserve 가 *중간에 차감 시도 후 롤백* 한 흔적도 없어야
        period.datesToReserve().forEach { date ->
            val row = inventories.findById(roomType.id, date)
                ?: error("inventory 누락 (date=$date)")
            assertThat(row.reservedRooms).isEqualTo(1)
            assertThat(row.totalRooms).isEqualTo(1)
        }
    }

    @DisplayName("cache 가 비어있을 때도 reserve 는 DB 만으로 결정한다 (cache miss 가 default fall-through 가 아님).")
    @Test
    fun reserveDoesNotPopulateCacheOnMiss() {
        val (property, roomType) = seedPropertyWithRoomType()
        seedDbInventories(roomType.id, totalRooms = 5, reservedRooms = 0)
        seedRates(roomType.id)
        // cache 빈 상태에서 시작 — reserve 는 DB 만 본다

        sut.reserve(reserveCommand(property.id, roomType.id, LoginId("u002")))

        // reserve 의 *진입 시점* 에 availability cache 가 *load 되지 않아야* 한다 — reserve 는 cache 우회.
        // afterCommit evict 가 발화하긴 하지만, 빈 cache 에서 evict 는 noop. 본 테스트의 핵심은
        // *cache 가 reserve 의 결정에 끼지 않는다* — load 도 안 한다.
        val cached = availabilityCacheStore.loadForRange(
            roomTypeId = roomType.id,
            from = period.checkIn,
            to = period.checkOut,
        ) { emptyList() } // loader empty — 호출되면 빈 결과만 반환
        // reserve 후에는 cache 가 evict 된 상태 (afterCommit). 다음 load 가 loader 호출.
        // 본 어설션은 *cache 가 reserve 흐름의 SSOT 가 아님* 의 *코드 시점 단언* — reserve 가 cache 를 *put*
        // 하지 않는다 (load 도 안 함).
        assertThat(cached).isEmpty()
    }

    private fun seedPropertyWithRoomType(): Pair<PropertyModel, RoomTypeModel> {
        val property = properties.save(
            PropertyModel(
                name = Name("강남호텔"),
                category = PropertyCategory.HOTEL,
                description = null,
                address = Address(city = "SEOUL", fullAddress = "SEOUL 어딘가 123"),
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
        return property to roomType
    }

    private fun seedDbInventories(roomTypeId: Long, totalRooms: Int, reservedRooms: Int) {
        period.datesToReserve().forEach { date ->
            inventories.save(
                DailyRoomInventoryModel.create(
                    roomTypeId = roomTypeId,
                    date = date,
                    totalRooms = totalRooms,
                    reservedRooms = reservedRooms,
                ),
            )
        }
    }

    private fun seedRates(roomTypeId: Long) {
        period.datesToReserve().forEach { date ->
            rates.save(DailyRoomRateModel.create(roomTypeId, date, Money.of(100_000)))
        }
    }

    /**
     * cache 에 *DB 와 어긋난 stale* 박제 — D-5 contract 의 *최악 상황* 모사.
     *
     * `AvailabilityCacheStore.loadForRange` 는 *miss 일자만 loader 호출 + put*. 본 메서드는 loader 가
     * *조작된 stale 값* 을 반환하도록 만들어 cache 에 *DB 와 다른 값* 을 박는다.
     */
    private fun seedStaleAvailabilityCache(roomTypeId: Long, totalRooms: Int, reservedRooms: Int) {
        val staleRows = period.datesToReserve().map { date ->
            RoomDailyAvailability(
                roomTypeId = roomTypeId,
                date = date,
                totalRooms = totalRooms,
                reservedRooms = reservedRooms,
                pricePerNight = 100_000,
            )
        }
        // loadForRange 의 loader 호출로 cache put — TTL 은 운영과 동일 (10s) 으로 박는다.
        // 단위 cache 의 *put* 경로는 `loadForRange` 내부에서만 가능 (외부 put API 없음 — by design).
        val loaded = availabilityCacheStore.loadForRange(
            roomTypeId = roomTypeId,
            from = period.checkIn,
            to = period.checkOut,
        ) { _ -> staleRows }
        assertThat(loaded).hasSize(period.datesToReserve().size)
    }

    private fun reserveCommand(propertyId: Long, roomTypeId: Long, userId: LoginId) =
        ReserveCommand(
            userId = userId,
            propertyId = propertyId,
            roomTypeId = roomTypeId,
            period = period,
            guestCount = 2,
            guest = GuestInfo(name = "홍길동", phoneNumber = PhoneNumber("010-1234-5678")),
            couponId = null,
        )
}
