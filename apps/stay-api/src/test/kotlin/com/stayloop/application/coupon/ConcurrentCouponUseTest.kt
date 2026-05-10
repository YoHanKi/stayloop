package com.stayloop.application.coupon

import com.stayloop.application.coupon.command.IssueCouponCommand
import com.stayloop.application.reservation.ReservationFacade
import com.stayloop.application.reservation.command.ReserveCommand
import com.stayloop.domain.common.value.Money
import com.stayloop.domain.coupon.CouponIssueRepository
import com.stayloop.domain.coupon.CouponTemplateModel
import com.stayloop.domain.coupon.CouponTemplateRepository
import com.stayloop.domain.coupon.value.CouponIssueStatus
import com.stayloop.domain.coupon.value.CouponName
import com.stayloop.domain.coupon.value.DiscountType
import com.stayloop.domain.coupon.value.DiscountValue
import com.stayloop.domain.coupon.value.ExpirationPeriod
import com.stayloop.domain.coupon.value.MinOrderAmount
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
import com.stayloop.domain.user.PasswordEncoder
import com.stayloop.domain.user.UserModel
import com.stayloop.domain.user.UserRepository
import com.stayloop.domain.user.value.BirthDate
import com.stayloop.domain.user.value.Email
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
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import com.stayloop.domain.user.value.Name as UserName

/**
 * **동시성 E2E** — 같은 사용자 / 같은 쿠폰 × 5 스레드 (서로 다른 reservation 5건) 동시 reserve 시나리오.
 * (`docs/plan/week4.md` ③ Phase B-3, week4-quests 동시성 ② "동일 쿠폰으로 여러 기기에서 동시 예약")
 *
 * **검증 의도**:
 * - 쿠폰은 한 번만 사용된다 — 성공 1건 + 실패 4건.
 * - DB `coupon_issues.status = USED`, `used_reservation_id` 는 단 한 reservation 만 가리킨다.
 * - `@Version` 낙관적 락 (version 0 → 1) + DB UNIQUE (`used_reservation_id`) 다층 가드가 협력해
 *   *두 번째 시도부터* `OptimisticLockingFailureException` 또는 `DataIntegrityViolationException` 으로 거절.
 * - ReservationFacade 가 위 두 예외를 *모두* `CoreException(CONFLICT, "이미 사용된 쿠폰입니다.")` 로 변환 —
 *   응답 정합 + 식별자 노출 차단 (verify-code §12).
 *
 * **시나리오 설계** — 같은 사용자가 5개의 *서로 다른 일자* (재고 race 제외) 에 reserve 시도 + 모두 같은
 * couponId 사용. inventory race 가 끼어들지 않도록 각 일자는 다른 period + 충분한 가용 (totalRooms=10).
 *
 * **InMemory 더블 사용 금지** — `synchronized` 만으로는 `@Version` 의 낙관적 락 의미론을 재현 못 한다
 * (verify-code R9). 본 테스트는 Testcontainers MySQL 8.0 으로만 의미가 있다.
 *
 * **격리** — `@AfterEach` 의 `truncateAllTables()` (`docs/plan/week4.md` Q7).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainersConfig::class)
class ConcurrentCouponUseTest {
    @Autowired
    private lateinit var sut: ReservationFacade

    @Autowired
    private lateinit var couponFacade: CouponFacade

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
    private lateinit var couponTemplates: CouponTemplateRepository

    @Autowired
    private lateinit var couponIssues: CouponIssueRepository

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

    @Autowired
    private lateinit var clock: Clock

    @Autowired
    private lateinit var databaseCleanUp: DatabaseCleanUp

    private val loginId = LoginId("alen01")
    private val periods = listOf(
        StayPeriod(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 3)),
        StayPeriod(LocalDate.of(2026, 6, 5), LocalDate.of(2026, 6, 7)),
        StayPeriod(LocalDate.of(2026, 6, 10), LocalDate.of(2026, 6, 12)),
        StayPeriod(LocalDate.of(2026, 6, 15), LocalDate.of(2026, 6, 17)),
        StayPeriod(LocalDate.of(2026, 6, 20), LocalDate.of(2026, 6, 22)),
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
        "같은 사용자 / 같은 쿠폰 × 5 스레드 동시 reserve 시 — 정확히 1건만 성공하고 쿠폰은 1회만 사용되며 " +
            "모든 CONFLICT 응답이 동일 메시지 (\"이미 사용된 쿠폰입니다.\") 로 일관된다.",
    )
    @Test
    fun shouldUseCouponExactlyOnce_whenFiveThreadsCompeteWithSameCoupon() {
        // given — 사용자 1명, 쿠폰 1장 (AVAILABLE), 5개의 비-겹침 일자 + 각 일자 가용 10실
        val user = seedUser()
        val (property, roomType) = seedPropertyWithRoomType()
        periods.forEach { period -> seedDates(roomType.id, period, totalRooms = 10) }
        val template = seedCouponTemplate()
        val issued = couponFacade.issue(IssueCouponCommand(actor = loginId, templateId = template.id))
        val couponId = issued.issueId

        val threadCount = periods.size // = 5
        val executor = Executors.newFixedThreadPool(threadCount)
        val ready = CountDownLatch(threadCount)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threadCount)
        val successes = AtomicInteger(0)
        val others = AtomicInteger(0)
        val failures: MutableList<Throwable> = java.util.Collections.synchronizedList(mutableListOf())
        // CONFLICT 로 분류된 예외만 별도 수집 — 메시지 일관성 검증용 (verify-code §0-B CE-3 / §19-B).
        val conflictExceptions: MutableList<CoreException> = java.util.Collections.synchronizedList(mutableListOf())

        // when — 5 스레드가 같은 couponId 로 *서로 다른 일자* 의 reserve 를 동시 호출
        periods.forEachIndexed { i, period ->
            executor.submit {
                ready.countDown()
                start.await()
                try {
                    sut.reserve(
                        ReserveCommand(
                            userId = loginId,
                            propertyId = property.id,
                            roomTypeId = roomType.id,
                            period = period,
                            guestCount = 2,
                            guest = GuestInfo(name = "홍길동", phoneNumber = PhoneNumber("010-1234-5678")),
                            couponId = couponId,
                        ),
                    )
                    successes.incrementAndGet()
                } catch (e: CoreException) {
                    if (e.errorType == ErrorType.CONFLICT) {
                        conflictExceptions.add(e)
                    } else {
                        others.incrementAndGet()
                        failures.add(e)
                    }
                } catch (e: Exception) {
                    // ApiControllerAdvice 매핑 이전의 raw 예외 (Spring 의 OptimisticLockingFailureException 등) 가
                    // 흘러나오는 경로 — Facade 가 모든 케이스를 CoreException 으로 흡수해야 정상이지만 회귀 가드.
                    others.incrementAndGet()
                    failures.add(e)
                    System.err.println("worker $i raw exception: ${e::class.simpleName}: ${e.message}")
                } finally {
                    done.countDown()
                }
            }
        }
        check(ready.await(5, TimeUnit.SECONDS)) {
            "ready latch 가 5초 안에 모두 도달하지 못했습니다 — 동시 쿠폰 사용 경쟁 전제 깨짐 (verify-code §9 latch 동기화 정합)."
        }
        start.countDown()
        check(done.await(30, TimeUnit.SECONDS)) { "동시 쿠폰 사용 흐름이 30초 안에 완료되지 않았습니다." }
        executor.shutdown()

        // then — 성공 1건 + 실패 4건 모두 CONFLICT 로 일반화 + 메시지 일관성
        val sample = failures.take(3).joinToString("\n  ") { "${it::class.simpleName}: ${it.message}" }
        val conflictCount = conflictExceptions.size
        assertThat(successes.get())
            .withFailMessage(
                "성공=%d, conflict=%d, others=%d. 첫 실패 샘플:\n  %s",
                successes.get(),
                conflictCount,
                others.get(),
                sample,
            )
            .isEqualTo(1)
        assertThat(conflictCount).isEqualTo(threadCount - 1)
        assertThat(others.get())
            .withFailMessage("CONFLICT 외 raw 예외 발생: others=%d, sample:\n  %s", others.get(), sample)
            .isZero()
        // **메시지 일관성 회귀 가드** (verify-code §0-B CE-3 / §19-B) — 같은 도메인 사고가 *3 흐름*
        // (낙관적 락 / UNIQUE / 도메인 throw) 으로 발생할 수 있어 Facade 가 *동일 customMessage* 로 정규화한다.
        // catch 한 모든 CONFLICT 의 customMessage 가 단일 set 으로 모이는지 검증 — 한 흐름이라도 다른
        // 메시지가 새어나오면 UX 일관성 깨짐.
        val conflictMessages = conflictExceptions.mapNotNull { it.customMessage }.toSet()
        assertThat(conflictMessages)
            .withFailMessage(
                "CONFLICT 메시지 일관성 깨짐: %s (catch scope 정합 위반 — verify-code §0-B CE-1)",
                conflictMessages,
            )
            .containsExactly("이미 사용된 쿠폰입니다.")

        // DB 의 쿠폰 사용 박제 — status=USED, used_reservation_id 가 정확히 1건 (UNIQUE 제약 동작 확인)
        val finalIssue = couponIssues.findById(couponId)
            ?: error("쿠폰 발급 인스턴스가 누락되었습니다 (couponId=$couponId)")
        assertThat(finalIssue.status).isEqualTo(CouponIssueStatus.USED)
        assertThat(finalIssue.usedReservationId).isNotNull()
        assertThat(finalIssue.usedAt).isNotNull()
        // Hibernate @Version 의미론 — INSERT 시 0 유지 + 첫 UPDATE 후 1. 본 테스트는 정확히 1번의 UPDATE 가 성공.
        assertThat(finalIssue.version).isEqualTo(1L)
    }

    private fun seedUser(): UserModel = users.save(
        UserModel.create(
            loginId = loginId,
            rawPassword = "Abcd1234!",
            name = UserName("홍길동"),
            birthDate = BirthDate(LocalDate.of(2000, 1, 1)),
            email = Email("alen01@stayloop.io"),
            phoneNumber = PhoneNumber("010-1234-5678"),
            encoder = passwordEncoder,
        ),
    )

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

    private fun seedDates(roomTypeId: Long, period: StayPeriod, totalRooms: Int) {
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

    private fun seedCouponTemplate(): CouponTemplateModel = couponTemplates.save(
        CouponTemplateModel.create(
            code = "WELCOME10",
            name = CouponName("WELCOME10 쿠폰"),
            discountValue = DiscountValue(type = DiscountType.RATE, rawValue = 10L),
            expirationPeriod = ExpirationPeriod(
                expiredAt = LocalDateTime.now(clock).plusDays(30),
            ),
            minOrderAmount = MinOrderAmount(Money.of(100_000L)),
        ),
    )
}
