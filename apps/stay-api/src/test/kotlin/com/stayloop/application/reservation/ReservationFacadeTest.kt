package com.stayloop.application.reservation

import com.stayloop.application.reservation.command.ReserveCommand
import com.stayloop.domain.common.value.Money
import com.stayloop.domain.coupon.CouponIssueModel
import com.stayloop.domain.coupon.CouponIssueService
import com.stayloop.domain.coupon.CouponTemplateModel
import com.stayloop.domain.coupon.value.CouponIssueStatus
import com.stayloop.domain.coupon.value.CouponName
import com.stayloop.domain.coupon.value.DiscountType
import com.stayloop.domain.coupon.value.DiscountValue
import com.stayloop.domain.coupon.value.ExpirationPeriod
import com.stayloop.domain.coupon.value.MinOrderAmount
import com.stayloop.domain.inventory.DailyRoomInventoryModel
import com.stayloop.domain.property.PropertyModel
import com.stayloop.domain.property.RoomTypeModel
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
import com.stayloop.domain.rate.ReservationPriceCalculator
import com.stayloop.domain.reservation.ReservationService
import com.stayloop.domain.reservation.value.GuestInfo
import com.stayloop.domain.reservation.value.ReservationStatus
import com.stayloop.domain.reservation.value.StayPeriod
import com.stayloop.domain.user.UserModel
import com.stayloop.domain.user.value.BirthDate
import com.stayloop.domain.user.value.Email
import com.stayloop.domain.user.value.LoginId
import com.stayloop.domain.user.value.PhoneNumber
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import com.stayloop.support.test.FakePasswordEncoder
import com.stayloop.support.test.InMemoryCouponIssueRepository
import com.stayloop.support.test.InMemoryCouponTemplateRepository
import com.stayloop.support.test.InMemoryDailyRoomInventoryRepository
import com.stayloop.support.test.InMemoryDailyRoomRateRepository
import com.stayloop.support.test.InMemoryPropertyRepository
import com.stayloop.support.test.InMemoryReservationRepository
import com.stayloop.support.test.InMemoryRoomTypeRepository
import com.stayloop.support.test.InMemoryUserRepository
import java.time.LocalDateTime
import com.stayloop.domain.user.value.Name as UserName
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class ReservationFacadeTest {
    private lateinit var properties: InMemoryPropertyRepository
    private lateinit var roomTypes: InMemoryRoomTypeRepository
    private lateinit var inventories: InMemoryDailyRoomInventoryRepository
    private lateinit var rates: InMemoryDailyRoomRateRepository
    private lateinit var reservations: InMemoryReservationRepository
    private lateinit var users: InMemoryUserRepository
    private lateinit var couponTemplates: InMemoryCouponTemplateRepository
    private lateinit var couponIssues: InMemoryCouponIssueRepository
    private lateinit var sut: ReservationFacade

    private val loginId = LoginId("alen01")
    private val otherLoginId = LoginId("other02")
    private val period = StayPeriod(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 3))
    private val fixedClock: Clock = Clock.fixed(
        java.time.Instant.parse("2026-05-04T10:00:00Z"),
        ZoneId.of("Asia/Seoul"),
    )
    private val now: LocalDateTime = LocalDateTime.parse("2026-05-04T19:00:00")
    private val encoder = FakePasswordEncoder()

    @BeforeEach
    fun setUp() {
        properties = InMemoryPropertyRepository()
        roomTypes = InMemoryRoomTypeRepository()
        inventories = InMemoryDailyRoomInventoryRepository()
        rates = InMemoryDailyRoomRateRepository()
        reservations = InMemoryReservationRepository()
        users = InMemoryUserRepository()
        couponTemplates = InMemoryCouponTemplateRepository()
        couponIssues = InMemoryCouponIssueRepository(users)
        sut = ReservationFacade(
            reservationRepository = reservations,
            inventoryRepository = inventories,
            rateRepository = rates,
            propertyRepository = properties,
            roomTypeRepository = roomTypes,
            reservationService = ReservationService(ReservationPriceCalculator()),
            couponIssueRepository = couponIssues,
            couponTemplateRepository = couponTemplates,
            couponIssueService = CouponIssueService(),
            userRepository = users,
            availabilityCacheStore = com.stayloop.infrastructure.cache.AvailabilityCacheStore(
                com.stayloop.support.test.InMemoryCacheStore(),
            ),
            clock = fixedClock,
        )
    }

    @DisplayName("reserve 는 일자별 재고를 차감하고 PENDING 상태의 예약을 반환한다 (AC-3).")
    @Test
    fun shouldReserveAndDecrementInventoriesPerDate() {
        val (property, roomType) = seedPropertyWithRoomType()
        seedAllDates(roomType.id, totalRooms = 3, reservedRooms = 0, pricePerNight = 100_000)

        val info = sut.reserve(reserveCommand(property.id, roomType.id))

        assertThat(info.status).isEqualTo(ReservationStatus.PENDING)
        assertThat(info.totalPrice).isEqualTo(Money.of(200_000)) // 100k * 2박
        assertThat(info.nights).isEqualTo(2)
        // 체크아웃 당일(6/3) 은 차감 X — 6/1, 6/2 만 차감
        assertThat(inventories.findById(roomType.id, period.checkIn)?.reservedRooms).isEqualTo(1)
        assertThat(inventories.findById(roomType.id, period.checkIn.plusDays(1))?.reservedRooms).isEqualTo(1)
        assertThat(inventories.findById(roomType.id, period.checkOut)).isNull() // 체크아웃 당일은 seed 자체가 안 됨
    }

    @DisplayName("reserve 는 인원 수가 maxGuests 를 초과하면 BAD_REQUEST 를 던진다 (AC-5).")
    @Test
    fun shouldRejectGuestCountOverMax() {
        val (property, roomType) = seedPropertyWithRoomType(maxGuests = 2)
        seedAllDates(roomType.id, totalRooms = 3, reservedRooms = 0, pricePerNight = 100_000)

        assertThatThrownBy {
            sut.reserve(reserveCommand(property.id, roomType.id, guestCount = 3))
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("reserve 는 다른 숙소의 객실 타입을 잘못 지정하면 BAD_REQUEST 를 던진다.")
    @Test
    fun shouldRejectMismatchedPropertyAndRoomType() {
        val (property1, _) = seedPropertyWithRoomType()
        val property2 = saveProperty(name = "다른호텔")
        val roomType2 = saveRoomType(propertyId = property2.id, name = "다른방", maxGuests = 2)
        seedAllDates(roomType2.id, totalRooms = 3, reservedRooms = 0, pricePerNight = 50_000)

        assertThatThrownBy {
            sut.reserve(reserveCommand(propertyId = property1.id, roomTypeId = roomType2.id))
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("reserve 는 재고 누락 / 일자 누락 시 BAD_REQUEST 를 던지고 부분 차감을 남기지 않는다.")
    @Test
    fun shouldRejectAndNotPartiallyDecrementOnMissingInventory() {
        val (property, roomType) = seedPropertyWithRoomType()
        // 6/1 만 등록 / 6/2 누락 — 일자 1:1 매칭 실패
        inventories.save(DailyRoomInventoryModel.create(roomType.id, period.checkIn, totalRooms = 3))
        rates.save(DailyRoomRateModel.create(roomType.id, period.checkIn, Money.of(100_000)))

        assertThatThrownBy {
            sut.reserve(reserveCommand(property.id, roomType.id))
        }.isInstanceOf(CoreException::class.java)

        // ReservationService 가 검증 단계에서 거절하므로 reserveOne() 자체가 호출되지 않아야 함 (cheap input early guard)
        assertThat(inventories.findById(roomType.id, period.checkIn)?.reservedRooms).isEqualTo(0)
    }

    @DisplayName("cancel 은 본인 예약만 취소할 수 있다 — 다른 LoginId 면 FORBIDDEN (AC-7).")
    @Test
    fun shouldRejectCancelByAnotherUser() {
        val (property, roomType) = seedPropertyWithRoomType()
        seedAllDates(roomType.id, totalRooms = 3, reservedRooms = 0, pricePerNight = 100_000)
        val info = sut.reserve(reserveCommand(property.id, roomType.id))

        assertThatThrownBy {
            sut.cancel(otherLoginId, info.reservationId)
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.FORBIDDEN)
    }

    @DisplayName("cancel 은 PENDING 상태에서 CANCELLED 로 전이하고 일자별 재고를 복원한다.")
    @Test
    fun shouldCancelAndRestoreInventories() {
        val (property, roomType) = seedPropertyWithRoomType()
        seedAllDates(roomType.id, totalRooms = 3, reservedRooms = 0, pricePerNight = 100_000)
        val info = sut.reserve(reserveCommand(property.id, roomType.id))
        assertThat(inventories.findById(roomType.id, period.checkIn)?.reservedRooms).isEqualTo(1)

        val cancelled = sut.cancel(loginId, info.reservationId)

        assertThat(cancelled.status).isEqualTo(ReservationStatus.CANCELLED)
        assertThat(cancelled.cancelledAt).isNotNull()
        // 6/1, 6/2 모두 0 으로 복원
        assertThat(inventories.findById(roomType.id, period.checkIn)?.reservedRooms).isEqualTo(0)
        assertThat(inventories.findById(roomType.id, period.checkIn.plusDays(1))?.reservedRooms).isEqualTo(0)
    }

    @DisplayName("cancel 은 존재하지 않는 reservationId 에 NOT_FOUND 를 던진다.")
    @Test
    fun shouldThrowNotFoundOnUnknownReservation() {
        assertThatThrownBy { sut.cancel(loginId, 999L) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.NOT_FOUND)
    }

    @DisplayName("getReservation 은 본인 예약만 조회할 수 있다 — 다른 LoginId 면 FORBIDDEN (AC-7).")
    @Test
    fun shouldRejectGetReservationByAnotherUser() {
        val (property, roomType) = seedPropertyWithRoomType()
        seedAllDates(roomType.id, totalRooms = 3, reservedRooms = 0, pricePerNight = 100_000)
        val info = sut.reserve(reserveCommand(property.id, roomType.id))

        assertThatThrownBy {
            sut.getReservation(otherLoginId, info.reservationId)
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.FORBIDDEN)
    }

    @DisplayName("getMyReservations 는 기간과 겹치는 본인 예약을 반환한다.")
    @Test
    fun shouldReturnMyReservationsOverlappingPeriod() {
        val (property, roomType) = seedPropertyWithRoomType()
        seedAllDates(roomType.id, totalRooms = 5, reservedRooms = 0, pricePerNight = 100_000)
        val mine = sut.reserve(reserveCommand(property.id, roomType.id))

        // 다른 사용자의 같은 기간 예약은 제외
        val other = sut.reserve(reserveCommand(property.id, roomType.id, userId = otherLoginId))

        val results = sut.getMyReservations(loginId, period)

        assertThat(results).hasSize(1)
        assertThat(results.first().reservationId).isEqualTo(mine.reservationId)
        assertThat(results.first().userId).isEqualTo(loginId.value)
        assertThat(other.reservationId).isNotEqualTo(mine.reservationId)
    }

    @DisplayName("쿠폰 적용 — issue 가 USED 로 전이되고 reservation 박제에 priceBeforeDiscount / discountAmount / couponId 가 채워진다.")
    @Test
    fun shouldApplyCouponAndMarkIssueUsed() {
        val (property, roomType) = seedPropertyWithRoomType()
        seedAllDates(roomType.id, totalRooms = 3, reservedRooms = 0, pricePerNight = 100_000)
        val user = saveUser(loginId.value)
        val template = saveCouponTemplate(code = "WELCOME10", rate = 10L, expiredAt = now.plusDays(30))
        val issue = saveCouponIssue(template.id, user.id)

        val info = sut.reserve(reserveCommand(property.id, roomType.id, couponId = issue.id))

        // 200_000 (2박 × 100_000) → 10% 할인 → 180_000
        assertThat(info.priceBeforeDiscount).isEqualTo(Money.of(200_000L))
        assertThat(info.discountAmount).isEqualTo(Money.of(20_000L))
        assertThat(info.totalPrice).isEqualTo(Money.of(180_000L))
        assertThat(info.couponId).isEqualTo(issue.id)
        assertThat(info.couponCode).isEqualTo("WELCOME10")
        // issue 가 USED 로 전이되었는지 회귀 가드
        val updated = couponIssues.findById(issue.id)!!
        assertThat(updated.status).isEqualTo(CouponIssueStatus.USED)
        assertThat(updated.usedReservationId).isEqualTo(info.reservationId)
    }

    @DisplayName("쿠폰 미적용 (couponId = null) — 기존 흐름 회귀 — couponSnapshot 은 null, discountAmount = 0.")
    @Test
    fun shouldKeepLegacyFlowWhenCouponIdNull() {
        val (property, roomType) = seedPropertyWithRoomType()
        seedAllDates(roomType.id, totalRooms = 3, reservedRooms = 0, pricePerNight = 100_000)

        val info = sut.reserve(reserveCommand(property.id, roomType.id))

        assertThat(info.couponId).isNull()
        assertThat(info.discountAmount).isEqualTo(Money.ZERO)
        assertThat(info.priceBeforeDiscount).isEqualTo(info.totalPrice)
    }

    @DisplayName("타 사용자 쿠폰 — 식별자 노출 없이 BAD_REQUEST 로 거절한다 (verify-code §12).")
    @Test
    fun shouldRejectOtherUsersCoupon() {
        val (property, roomType) = seedPropertyWithRoomType()
        seedAllDates(roomType.id, totalRooms = 3, reservedRooms = 0, pricePerNight = 100_000)
        saveUser(loginId.value)
        val others = saveUser(otherLoginId.value)
        val template = saveCouponTemplate(code = "OTHERS", rate = 10L, expiredAt = now.plusDays(30))
        val foreignIssue = saveCouponIssue(template.id, others.id)

        assertThatThrownBy {
            sut.reserve(reserveCommand(property.id, roomType.id, couponId = foreignIssue.id))
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("만료된 쿠폰 — BAD_REQUEST 로 거절하고 inventory 부분 차감이 일어나지 않는다.")
    @Test
    fun shouldRejectExpiredCouponWithoutPartialDecrement() {
        val (property, roomType) = seedPropertyWithRoomType()
        seedAllDates(roomType.id, totalRooms = 3, reservedRooms = 0, pricePerNight = 100_000)
        val user = saveUser(loginId.value)
        val expired = saveCouponTemplate(code = "EXPIRED", rate = 10L, expiredAt = now.minusSeconds(1))
        val issue = saveCouponIssue(expired.id, user.id)

        assertThatThrownBy {
            sut.reserve(reserveCommand(property.id, roomType.id, couponId = issue.id))
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)

        // 쿠폰 검증 실패는 재고 차감 *전* 에 발동되어야 함 (Strong Exception Safety 회귀 가드)
        assertThat(inventories.findById(roomType.id, period.checkIn)?.reservedRooms).isEqualTo(0)
        // issue 도 AVAILABLE 그대로 (silent 사고 차단)
        assertThat(couponIssues.findById(issue.id)?.status).isEqualTo(CouponIssueStatus.AVAILABLE)
    }

    @DisplayName("이미 사용된 쿠폰 — CONFLICT (issue.use 가 검증) 로 거절.")
    @Test
    fun shouldRejectAlreadyUsedCoupon() {
        val (property, roomType) = seedPropertyWithRoomType()
        seedAllDates(roomType.id, totalRooms = 3, reservedRooms = 0, pricePerNight = 100_000)
        val user = saveUser(loginId.value)
        val template = saveCouponTemplate(code = "USED", rate = 10L, expiredAt = now.plusDays(30))
        val issue = saveCouponIssue(template.id, user.id)
        // 첫 사용
        sut.reserve(reserveCommand(property.id, roomType.id, couponId = issue.id))

        // 두 번째 사용 시도 — 같은 issue, 다른 일자
        seedAllDates(roomType.id, totalRooms = 3, reservedRooms = 0, pricePerNight = 100_000) // 다일자 차감 후 재초기화 (단일 inMemory 큰 카운트 가정)
        assertThatThrownBy {
            sut.reserve(reserveCommand(property.id, roomType.id, couponId = issue.id))
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT)
    }

    private fun saveUser(loginIdString: String): UserModel {
        val user = UserModel.create(
            loginId = LoginId(loginIdString),
            rawPassword = "Abcd1234!",
            name = UserName("홍길동"),
            birthDate = BirthDate(LocalDate.of(2000, 1, 1)),
            email = Email("$loginIdString@stayloop.io"),
            phoneNumber = PhoneNumber("010-1234-5678"),
            encoder = encoder,
        )
        return users.save(user)
    }

    private fun saveCouponTemplate(
        code: String,
        rate: Long,
        expiredAt: LocalDateTime,
    ): CouponTemplateModel {
        val template = CouponTemplateModel.create(
            code = code,
            name = CouponName("$code 쿠폰"),
            discountValue = DiscountValue(type = DiscountType.RATE, rawValue = rate),
            expirationPeriod = ExpirationPeriod(expiredAt = expiredAt),
            // 본 테스트는 200,000 결제 + 100,000 최소 — 적용 가능
            minOrderAmount = MinOrderAmount(Money.of(100_000L)),
        )
        return couponTemplates.save(template)
    }

    private fun saveCouponIssue(templateId: Long, userId: Long): CouponIssueModel =
        couponIssues.save(CouponIssueModel.issue(templateId, userId, now))

    private fun seedPropertyWithRoomType(maxGuests: Int = 2): Pair<PropertyModel, RoomTypeModel> {
        val property = saveProperty()
        val roomType = saveRoomType(propertyId = property.id, name = "스탠다드", maxGuests = maxGuests)
        return property to roomType
    }

    private fun saveProperty(name: String = "강남호텔"): PropertyModel {
        val property = PropertyModel.create(
            name = Name(name),
            category = PropertyCategory.HOTEL,
            description = "테스트용 숙소",
            address = Address(city = "SEOUL", fullAddress = "SEOUL 어딘가 123"),
            amenities = Amenities.EMPTY,
            policy = PropertyPolicy(
                checkInTime = LocalTime.of(15, 0),
                checkOutTime = LocalTime.of(11, 0),
                cancellation = CancellationPolicy(type = CancellationType.FREE_UNTIL, freeUntilDaysBefore = 3),
            ),
        )
        return properties.save(property)
    }

    private fun saveRoomType(propertyId: Long, name: String, maxGuests: Int): RoomTypeModel {
        val roomType = RoomTypeModel.create(
            propertyId = propertyId,
            name = Name(name),
            guestCount = GuestCount(base = 2, max = maxGuests),
            bedConfig = BedConfig.of(BedType.DOUBLE to 1),
        )
        return roomTypes.save(roomType)
    }

    private fun seedAllDates(roomTypeId: Long, totalRooms: Int, reservedRooms: Int, pricePerNight: Long) {
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

    private fun reserveCommand(
        propertyId: Long,
        roomTypeId: Long,
        userId: LoginId = loginId,
        guestCount: Int = 2,
        couponId: Long? = null,
    ) = ReserveCommand(
        userId = userId,
        propertyId = propertyId,
        roomTypeId = roomTypeId,
        period = period,
        guestCount = guestCount,
        guest = GuestInfo(name = "홍길동", phoneNumber = PhoneNumber("010-1234-5678")),
        couponId = couponId,
    )
}
