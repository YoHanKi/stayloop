package com.stayloop.application.reservation

import com.stayloop.application.reservation.command.ReserveCommand
import com.stayloop.domain.common.value.Money
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
 * **동시성 E2E** — *체크인-체크아웃 겹치는 다일자* 동시 예약 시나리오.
 * (`docs/plan/week4.md` ③ Phase D-1, week4-quests 동시성 ④ "다일자 겹침")
 *
 * **시나리오 설계**:
 * - 같은 객실 (`roomTypeId=R`), 마지막 1실 가정 (`totalRooms=1, reservedRooms=0`).
 * - **A**: 3박, `checkIn=2026-06-10 / checkOut=2026-06-13`. dates = `[6/10, 6/11, 6/12]`.
 * - **B**: 2박, `checkIn=2026-06-11 / checkOut=2026-06-13`. dates = `[6/11, 6/12]`.
 * - **겹침**: `[6/11, 6/12]` (2 일자). A 의 unique 일자: `6/10`.
 *
 * **검증 의도**:
 * - **부분 차감 0** — 한 명만 성공해야 하며, *실패한 측의 unique 일자도 차감되어선 안 됨*. `@Transactional`
 *   rollback 이 다일자 차감의 atomicity 를 보장하는지 회귀 가드.
 * - **`date ASC` 락 순서가 데드락을 회피** — A 와 B 모두 `findInventoriesForUpdate(roomTypeId, dates)` 가 발행하는
 *   `SELECT ... WHERE date IN (?, ?, ...) FOR UPDATE ORDER BY date ASC` 의 ORDER BY 로 락을 *동일 순서* 로 획득.
 *   순서 정합 → cycle 미발생 → 데드락 0. 본 테스트의 *완료 시간 (30s 안)* 이 데드락 회피의 묵시적 회귀 가드.
 * - **5주차+ 부하 합류 전 가드** — 본 라운드는 default `innodb_lock_wait_timeout=50s` 의존. 2 스레드 환경에서는
 *   첫 스레드가 commit 한 후 두 번째 스레드가 락 획득 → `reserveOne()` 의 가용 0 가드 → CONFLICT 흐름.
 *
 * **InMemory 더블 사용 금지** — `synchronized` 만으로는 InnoDB 행 락 / `ORDER BY ... FOR UPDATE` / deadlock
 * 검출 의미론을 재현 불가 (verify-code R9). 본 테스트는 Testcontainers MySQL 8.0 으로만 의미가 있다.
 *
 * **격리** — `@AfterEach` 의 `truncateAllTables()` (`docs/plan/week4.md` Q7).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainersConfig::class)
class ConcurrentMultiDateReservationTest {
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

    private val periodA = StayPeriod(LocalDate.of(2026, 6, 10), LocalDate.of(2026, 6, 13))
    private val periodB = StayPeriod(LocalDate.of(2026, 6, 11), LocalDate.of(2026, 6, 13))
    private val allDates = listOf(
        LocalDate.of(2026, 6, 10),
        LocalDate.of(2026, 6, 11),
        LocalDate.of(2026, 6, 12),
    )

    @BeforeEach
    fun cleanUp() {
        databaseCleanUp.truncateAllTables()
    }

    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
    }

    @DisplayName(
        "겹치는 다일자 동시 예약 (3박 ↔ 2박, 5/11/5/12 겹침) × 마지막 1실 — " +
            "정확히 1명만 성공하고 부분 차감이 발생하지 않으며 데드락 없이 30초 안에 종료된다.",
    )
    @Test
    fun shouldAllowExactlyOneReservation_whenTwoOverlappingMultiDateRequestsCompete() {
        // given — 가용 1실 / 3 일자 (6/10, 6/11, 6/12) 모두 reserved=0
        val (property, roomType) = seedPropertyWithRoomType()
        seedAllDates(roomType.id, totalRooms = 1)

        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val successesA = AtomicInteger(0)
        val successesB = AtomicInteger(0)
        val conflicts = AtomicInteger(0)
        val others = AtomicInteger(0)
        val failures: MutableList<Throwable> = java.util.Collections.synchronizedList(mutableListOf())

        // when — A (3박) 와 B (2박) 가 동시에 reserve 진입
        executor.submit {
            ready.countDown()
            start.await()
            try {
                sut.reserve(reserveCommand(property.id, roomType.id, LoginId("userA"), periodA))
                successesA.incrementAndGet()
            } catch (e: CoreException) {
                if (e.errorType == ErrorType.CONFLICT) {
                    conflicts.incrementAndGet()
                } else {
                    others.incrementAndGet()
                    failures.add(e)
                }
            } catch (e: org.springframework.dao.PessimisticLockingFailureException) {
                // NOWAIT 합류 시 LockTimeoutException 흡수 — 카테고리 한정.
                conflicts.incrementAndGet()
                failures.add(e)
            } catch (e: Exception) {
                // 그 외 raw 예외는 회귀 신호 — others 로 분리 (verify-code §0-B CE-3 / §11).
                others.incrementAndGet()
                failures.add(e)
                System.err.println("workerA raw exception: ${e::class.simpleName}: ${e.message}")
            } finally {
                done.countDown()
            }
        }
        executor.submit {
            ready.countDown()
            start.await()
            try {
                sut.reserve(reserveCommand(property.id, roomType.id, LoginId("userB"), periodB))
                successesB.incrementAndGet()
            } catch (e: CoreException) {
                if (e.errorType == ErrorType.CONFLICT) {
                    conflicts.incrementAndGet()
                } else {
                    others.incrementAndGet()
                    failures.add(e)
                }
            } catch (e: org.springframework.dao.PessimisticLockingFailureException) {
                // NOWAIT 합류 시 LockTimeoutException 흡수 — 카테고리 한정.
                conflicts.incrementAndGet()
                failures.add(e)
            } catch (e: Exception) {
                // 그 외 raw 예외는 회귀 신호 — others 로 분리 (verify-code §0-B CE-3 / §11).
                others.incrementAndGet()
                failures.add(e)
                System.err.println("workerB raw exception: ${e::class.simpleName}: ${e.message}")
            } finally {
                done.countDown()
            }
        }
        check(ready.await(5, TimeUnit.SECONDS)) {
            "ready latch 가 5초 안에 두 워커 모두 도달하지 못했습니다 — 동시 출발 전제 깨짐 (데드락/부분차감 회귀 미발동)."
        }
        start.countDown()
        // 데드락 회피 회귀 가드 — date ASC 락 순서가 깨지면 두 thread 가 cycle 로 hang.
        check(done.await(30, TimeUnit.SECONDS)) {
            "다일자 겹침 동시 예약이 30초 안에 종료되지 않았습니다 — date ASC 락 순서가 깨져 데드락 가능성."
        }
        executor.shutdown()

        // then — 정확히 1명만 성공 + 부분 차감 0
        val sample = failures.take(3).joinToString("\n  ") { "${it::class.simpleName}: ${it.message}" }
        val totalSuccesses = successesA.get() + successesB.get()
        assertThat(totalSuccesses)
            .withFailMessage(
                "성공=A:%d/B:%d, conflict=%d, others=%d. 첫 실패 샘플:\n  %s",
                successesA.get(),
                successesB.get(),
                conflicts.get(),
                others.get(),
                sample,
            )
            .isEqualTo(1)
        assertThat(others.get())
            .withFailMessage("CONFLICT 외 도메인 예외 발생: others=%d, sample:\n  %s", others.get(), sample)
            .isZero()

        // **부분 차감 0 검증** — 성공한 reservation 의 *모든* 일자가 reserved=1, 실패한 reservation 의 *unique*
        // 일자는 영향 없음. 5주차+ 결제 라운드까지 사용자 약정의 핵심 회귀 가드.
        // *각 일자 inventory 는 *한 번만 조회* — verify-code §14 DRY (재조회 회피).
        val inventoryByDate = allDates.associateWith { date ->
            inventories.findById(roomType.id, date)
                ?: error("inventory 가 누락되었습니다 (date=$date)")
        }
        // 6/11, 6/12 는 어느 쪽이 성공하든 reserved=1 (둘 다 사용)
        assertThat(inventoryByDate.getValue(LocalDate.of(2026, 6, 11)).reservedRooms)
            .withFailMessage(
                "6/11 reserved 가 1 이 아님: %d (성공=A:%d/B:%d)",
                inventoryByDate.getValue(LocalDate.of(2026, 6, 11)).reservedRooms,
                successesA.get(),
                successesB.get(),
            )
            .isEqualTo(1)
        assertThat(inventoryByDate.getValue(LocalDate.of(2026, 6, 12)).reservedRooms)
            .withFailMessage(
                "6/12 reserved 가 1 이 아님: %d (성공=A:%d/B:%d)",
                inventoryByDate.getValue(LocalDate.of(2026, 6, 12)).reservedRooms,
                successesA.get(),
                successesB.get(),
            )
            .isEqualTo(1)
        // 6/10 은 A 성공 시 reserved=1 (A 의 unique 일자), B 성공 시 reserved=0 (B 가 6/10 사용 안 함)
        val expectedDate10 = if (successesA.get() == 1) 1 else 0
        assertThat(inventoryByDate.getValue(LocalDate.of(2026, 6, 10)).reservedRooms)
            .withFailMessage(
                "6/10 reserved 정합 깨짐: actual=%d, expected=%d (A 성공 시 1, B 성공 시 0). 성공=A:%d/B:%d",
                inventoryByDate.getValue(LocalDate.of(2026, 6, 10)).reservedRooms,
                expectedDate10,
                successesA.get(),
                successesB.get(),
            )
            .isEqualTo(expectedDate10)
        // **음수 진입 / 더블부킹 0** — 어떤 일자도 reserved > total 이 안 됨
        inventoryByDate.forEach { (date, inventory) ->
            assertThat(inventory.reservedRooms)
                .withFailMessage(
                    "date=%s 더블부킹: reserved=%d > total=%d",
                    date,
                    inventory.reservedRooms,
                    inventory.totalRooms,
                )
                .isLessThanOrEqualTo(inventory.totalRooms)
        }
    }

    private fun seedPropertyWithRoomType(): Pair<PropertyModel, RoomTypeModel> {
        val property = properties.save(
            PropertyModel.create(
                name = Name("강남호텔"),
                category = PropertyCategory.HOTEL,
                description = "다일자 겹침 동시성 E2E 시드",
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
        allDates.forEach { date ->
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

    private fun reserveCommand(
        propertyId: Long,
        roomTypeId: Long,
        userId: LoginId,
        period: StayPeriod,
    ) = ReserveCommand(
        userId = userId,
        propertyId = propertyId,
        roomTypeId = roomTypeId,
        period = period,
        guestCount = 2,
        guest = GuestInfo(name = "홍길동", phoneNumber = PhoneNumber("010-1234-5678")),
        couponId = null,
    )
}
