package com.stayloop.application.reservation

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
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
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
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * **동시성 E2E** — 같은 객실 / 같은 일자 / 가용 1실 × 10 스레드 동시 `reserve` 요청 시나리오.
 * (`docs/plan/week4.md` ③ Phase A-4, `documents/feature/concurrency-control/experiments-results.md` E-1)
 *
 * **검증 의도**:
 * - 더블부킹이 발생하지 않는다 — 성공 1건 + 실패 9건.
 * - DB `daily_room_inventories.reserved_rooms` 가 `total_rooms` 와 정확히 일치 (1).
 * - 비관적 락 (`findInventoriesForUpdate` + `@Transactional`) 없이는 *원시* `findAllInRange` 후 차감 흐름이
 *   read-modify-write race 로 더블부킹을 만든다 — 본 테스트는 락이 *실제로* 차단하는지의 회귀 가드.
 *
 * **InMemory 더블 사용 금지** — `synchronized` 만으로는 InnoDB 행 락 / NOWAIT / deadlock 의미론을 재현
 * 못 한다 (`db-lock-low-level.md` LQ20, verify-code R9). 본 테스트는 Testcontainers MySQL 8.0 으로만 의미가 있다.
 *
 * **격리** — `@AfterEach` 에서 `DatabaseCleanUp.truncateAllTables()` 로 테스트 간 상태 누수를 차단.
 * `@Transactional` rollback 으로는 *동시 트랜잭션* 의 본질을 깨므로 사용 불가 (`docs/plan/week4.md` Q7).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainersConfig::class)
class ConcurrentReservationTest {
    @Autowired
    private lateinit var sut: ReservationFacade

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

    private val period = StayPeriod(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 3))

    @BeforeEach
    fun cleanUp() {
        databaseCleanUp.truncateAllTables()
    }

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
    }

    @DisplayName("같은 객실 / 같은 일자 / 가용 1실 × 10 스레드 동시 예약 시 — 정확히 1건만 성공하고 더블부킹이 발생하지 않는다.")
    @Test
    fun shouldAllowExactlyOneReservation_whenTenThreadsCompeteForLastRoom() {
        // given — 가용 1실 / 2박 / 10명의 사용자
        val (property, roomType) = seedPropertyWithRoomType()
        seedAllDates(roomType.id, totalRooms = 1)
        val threadCount = 10

        val executor = Executors.newFixedThreadPool(threadCount)
        val ready = CountDownLatch(threadCount)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threadCount)
        val successes = AtomicInteger(0)
        val conflicts = AtomicInteger(0)
        val others = AtomicInteger(0)
        val failures: MutableList<Throwable> = java.util.Collections.synchronizedList(mutableListOf())

        // when — 10 스레드가 동시에 reserve 진입 (start latch 로 동시 출발 보장)
        repeat(threadCount) { i ->
            executor.submit {
                ready.countDown()
                start.await()
                try {
                    sut.reserve(reserveCommand(property.id, roomType.id, LoginId("user${i.toString().padStart(2, '0')}")))
                    successes.incrementAndGet()
                } catch (e: CoreException) {
                    if (e.errorType == ErrorType.CONFLICT) {
                        conflicts.incrementAndGet()
                    } else {
                        others.incrementAndGet()
                        failures.add(e)
                    }
                } catch (e: Exception) {
                    // 본 라운드는 default `innodb_lock_wait_timeout` 의존 — 첫 스레드가 차감/커밋 후 후속 스레드는
                    // 락 획득 시점에 가용 0 → `reserveOne()` 의 CONFLICT 로 거절 (위 CoreException 분기로 흡수).
                    // 향후 NOWAIT 합류 (`db-lock-low-level.md` LQ3) 시 LockTimeoutException → Spring 의
                    // PessimisticLockingFailureException 도 발동 가능 — 본 분기가 미래 회귀까지 함께 흡수.
                    conflicts.incrementAndGet()
                    failures.add(e)
                } finally {
                    done.countDown()
                }
            }
        }
        ready.await(5, TimeUnit.SECONDS)
        start.countDown()
        check(done.await(30, TimeUnit.SECONDS)) { "동시 예약 흐름이 30초 안에 완료되지 않았습니다." }
        executor.shutdown()

        // then — 성공 정확히 1건 + 나머지는 충돌 (CONFLICT 또는 락 타임아웃)
        val sample = failures.take(3).joinToString("\n  ") { "${it::class.simpleName}: ${it.message}" }
        assertThat(successes.get())
            .withFailMessage(
                "성공=%d, conflict=%d, others=%d. 첫 실패 샘플:\n  %s",
                successes.get(),
                conflicts.get(),
                others.get(),
                sample,
            )
            .isEqualTo(1)
        assertThat(conflicts.get() + others.get()).isEqualTo(threadCount - 1)
        assertThat(others.get())
            .withFailMessage("CONFLICT 외 도메인 예외 발생: others=%d, sample:\n  %s", others.get(), sample)
            .isZero()

        // DB 의 reserved_rooms 가 total_rooms 를 초과하지 않고 정확히 1로 마감되었는지 — 더블부킹 회귀 가드
        period.datesToReserve().forEach { date ->
            val inventory = inventories.findById(roomType.id, date)
                ?: error("inventory 가 누락되었습니다 (date=$date)")
            assertThat(inventory.reservedRooms).isEqualTo(1)
            assertThat(inventory.totalRooms).isEqualTo(1)
            assertThat(inventory.available()).isZero()
        }
    }

    private fun seedPropertyWithRoomType(): Pair<PropertyModel, RoomTypeModel> {
        val property = properties.save(
            PropertyModel.create(
                name = Name("강남호텔"),
                category = PropertyCategory.HOTEL,
                description = "동시성 E2E 시드",
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

    private fun seedAllDates(roomTypeId: Long, totalRooms: Int) {
        period.datesToReserve().forEach { date ->
            inventories.save(
                DailyRoomInventoryModel.create(
                    roomTypeId = roomTypeId,
                    date = date,
                    totalRooms = totalRooms,
                    reservedRooms = 0,
                ),
            )
            rates.save(DailyRoomRateModel.create(roomTypeId, date, Money.of(100_000)))
        }
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
