package com.stayloop.application.reservation

import com.stayloop.application.reservation.command.ReserveCommand
import com.stayloop.domain.common.value.Money
import com.stayloop.domain.coupon.CouponService
import com.stayloop.domain.coupon.CouponTemplateModel
import com.stayloop.domain.coupon.value.CouponStatus
import com.stayloop.domain.coupon.value.DiscountType
import com.stayloop.domain.coupon.value.DiscountValue
import com.stayloop.domain.inventory.DailyRoomInventoryModel
import com.stayloop.domain.inventory.DailyRoomInventoryService
import com.stayloop.domain.property.PropertyModel
import com.stayloop.domain.property.RoomTypeModel
import com.stayloop.domain.property.value.Address
import com.stayloop.domain.property.value.BedConfig
import com.stayloop.domain.property.value.BedType
import com.stayloop.domain.property.value.GuestCount
import com.stayloop.domain.property.value.PropertyCategory
import com.stayloop.domain.property.value.PropertyName
import com.stayloop.domain.property.value.PropertyPolicy
import com.stayloop.domain.rate.DailyRoomRateModel
import com.stayloop.domain.reservation.ReservationPriceCalculator
import com.stayloop.domain.reservation.ReservationService
import com.stayloop.domain.reservation.value.ReservationStatus
import com.stayloop.domain.user.value.LoginId
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import com.stayloop.support.test.InMemoryCouponTemplateRepository
import com.stayloop.support.test.InMemoryDailyRoomInventoryRepository
import com.stayloop.support.test.InMemoryDailyRoomRateRepository
import com.stayloop.support.test.InMemoryIssuedCouponRepository
import com.stayloop.support.test.InMemoryPropertyRepository
import com.stayloop.support.test.InMemoryReservationRepository
import com.stayloop.support.test.InMemoryRoomTypeRepository
import com.stayloop.support.test.NoOpTransactionManager
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class ReservationFacadeTest {
    private lateinit var propertyRepository: InMemoryPropertyRepository
    private lateinit var roomTypeRepository: InMemoryRoomTypeRepository
    private lateinit var inventoryRepository: InMemoryDailyRoomInventoryRepository
    private lateinit var rateRepository: InMemoryDailyRoomRateRepository
    private lateinit var reservationRepository: InMemoryReservationRepository
    private lateinit var couponTemplateRepository: InMemoryCouponTemplateRepository
    private lateinit var issuedCouponRepository: InMemoryIssuedCouponRepository
    private lateinit var couponService: CouponService
    private lateinit var sut: ReservationFacade

    private val alice = LoginId("alice01")
    private val bob = LoginId("bobby02")
    private val checkIn = LocalDate.of(2026, 6, 1)
    private val checkOut = LocalDate.of(2026, 6, 3)
    private var propertyId: Long = 0
    private var roomTypeId: Long = 0

    @BeforeEach
    fun setUp() {
        propertyRepository = InMemoryPropertyRepository()
        roomTypeRepository = InMemoryRoomTypeRepository()
        inventoryRepository = InMemoryDailyRoomInventoryRepository()
        rateRepository = InMemoryDailyRoomRateRepository()
        reservationRepository = InMemoryReservationRepository()
        couponTemplateRepository = InMemoryCouponTemplateRepository()
        issuedCouponRepository = InMemoryIssuedCouponRepository()
        couponService = CouponService(couponTemplateRepository, issuedCouponRepository)
        val clock = Clock.fixed(Instant.parse("2026-05-30T00:00:00Z"), ZoneOffset.UTC)
        sut = ReservationFacade(
            propertyRepository,
            roomTypeRepository,
            rateRepository,
            DailyRoomInventoryService(inventoryRepository),
            couponService,
            issuedCouponRepository,
            ReservationService(ReservationPriceCalculator()),
            reservationRepository,
            clock,
            NoOpTransactionManager(),
        )

        propertyId = propertyRepository.save(
            PropertyModel.create(
                name = PropertyName("스테이루프 호텔"),
                category = PropertyCategory.HOTEL,
                address = Address("seoul", "서울특별시 중구 세종대로 110"),
                policy = PropertyPolicy.standard(),
            ),
        ).id
        roomTypeId = roomTypeRepository.save(
            RoomTypeModel.create(
                propertyId = propertyId,
                name = "디럭스 더블",
                guestCount = GuestCount(2, 4),
                bedConfig = BedConfig(mapOf(BedType.DOUBLE to 1)),
            ),
        ).id
    }

    private fun seedFullInventoryAndRate() {
        inventoryRepository.saveAll(
            listOf(
                DailyRoomInventoryModel(roomTypeId, checkIn, totalRooms = 2),
                DailyRoomInventoryModel(roomTypeId, checkIn.plusDays(1), totalRooms = 2),
            ),
        )
        rateRepository.saveAll(
            listOf(
                DailyRoomRateModel(roomTypeId, checkIn, Money.of(100_000)),
                DailyRoomRateModel(roomTypeId, checkIn.plusDays(1), Money.of(120_000)),
            ),
        )
    }

    private fun command(loginId: LoginId = alice, guestCount: Int = 2) =
        ReserveCommand(
            loginId = loginId,
            propertyId = propertyId,
            roomTypeId = roomTypeId,
            checkIn = checkIn,
            checkOut = checkOut,
            guestCount = guestCount,
            guestName = "홍길동",
            guestPhoneNumber = "010-1234-5678",
        )

    @DisplayName("예약은 PENDING 으로 생성되고 체크아웃 당일을 제외한 날짜별 재고가 1 씩 차감된다(AC-3).")
    @Test
    fun shouldReserveAndDecrementInventoriesPerDate() {
        seedFullInventoryAndRate()

        val info = sut.reserve(command())

        assertThat(info.status).isEqualTo(ReservationStatus.PENDING.name)
        assertThat(info.totalPrice).isEqualByComparingTo("220000")
        assertThat(inventoryRepository.findById(roomTypeId, checkIn)!!.reservedRooms).isEqualTo(1)
        assertThat(inventoryRepository.findById(roomTypeId, checkIn.plusDays(1))!!.reservedRooms).isEqualTo(1)
    }

    @DisplayName("어느 한 일자의 재고 정보가 없으면 CONFLICT 로 거절되고 어떤 재고도 차감되지 않는다(부분 차감 금지).")
    @Test
    fun shouldRejectAndNotPartiallyDecrementOnMissingInventory() {
        inventoryRepository.save(DailyRoomInventoryModel(roomTypeId, checkIn, totalRooms = 2))
        rateRepository.saveAll(
            listOf(
                DailyRoomRateModel(roomTypeId, checkIn, Money.of(100_000)),
                DailyRoomRateModel(roomTypeId, checkIn.plusDays(1), Money.of(120_000)),
            ),
        )

        assertThatThrownBy { sut.reserve(command()) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT)
        assertThat(inventoryRepository.findById(roomTypeId, checkIn)!!.reservedRooms).isEqualTo(0)
    }

    @DisplayName("어느 한 일자라도 매진이면 CONFLICT 로 거절되고 어떤 재고도 차감되지 않는다(부분 차감 금지).")
    @Test
    fun shouldRejectAndNotPartiallyDecrementOnSoldOut() {
        inventoryRepository.saveAll(
            listOf(
                DailyRoomInventoryModel(roomTypeId, checkIn, totalRooms = 2),
                DailyRoomInventoryModel(roomTypeId, checkIn.plusDays(1), totalRooms = 1, reservedRooms = 1),
            ),
        )
        rateRepository.saveAll(
            listOf(
                DailyRoomRateModel(roomTypeId, checkIn, Money.of(100_000)),
                DailyRoomRateModel(roomTypeId, checkIn.plusDays(1), Money.of(120_000)),
            ),
        )

        assertThatThrownBy { sut.reserve(command()) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT)
        assertThat(inventoryRepository.findById(roomTypeId, checkIn)!!.reservedRooms).isEqualTo(0)
    }

    @DisplayName("요청 인원이 객실 최대 인원을 넘으면 BAD_REQUEST 로 거절된다(AC-5).")
    @Test
    fun shouldRejectGuestCountOverMax() {
        seedFullInventoryAndRate()

        assertThatThrownBy { sut.reserve(command(guestCount = 5)) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("객실 타입이 요청 숙소에 속하지 않으면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldRejectWhenRoomTypeNotInProperty() {
        seedFullInventoryAndRate()
        val otherProperty = propertyRepository.save(
            PropertyModel.create(
                name = PropertyName("다른 호텔"),
                category = PropertyCategory.HOTEL,
                address = Address("busan", "부산 어딘가"),
                policy = PropertyPolicy.standard(),
            ),
        )

        assertThatThrownBy { sut.reserve(command().copy(propertyId = otherProperty.id)) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("취소는 본인 예약을 CANCELLED 로 바꾸고 재고를 복원한다.")
    @Test
    fun shouldCancelAndRestoreInventory() {
        seedFullInventoryAndRate()
        val reserved = sut.reserve(command())

        val cancelled = sut.cancel(alice, reserved.reservationId)

        assertThat(cancelled.status).isEqualTo(ReservationStatus.CANCELLED.name)
        assertThat(inventoryRepository.findById(roomTypeId, checkIn)!!.reservedRooms).isEqualTo(0)
    }

    @DisplayName("타인이 내 예약을 취소하려 하면 403 으로 거절된다(AC-7).")
    @Test
    fun shouldRejectCancelByAnotherUser() {
        seedFullInventoryAndRate()
        val reserved = sut.reserve(command())

        assertThatThrownBy { sut.cancel(bob, reserved.reservationId) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.FORBIDDEN)
    }

    @DisplayName("타인이 내 예약을 조회하려 하면 403 으로 거절된다(AC-7).")
    @Test
    fun shouldRejectGetReservationByAnotherUser() {
        seedFullInventoryAndRate()
        val reserved = sut.reserve(command())

        assertThat(sut.getReservation(alice, reserved.reservationId).reservationId).isEqualTo(reserved.reservationId)
        assertThatThrownBy { sut.getReservation(bob, reserved.reservationId) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.FORBIDDEN)
    }

    @DisplayName("내 예약 목록을 조회한다.")
    @Test
    fun shouldListMyReservations() {
        seedFullInventoryAndRate()
        sut.reserve(command())

        assertThat(sut.getMyReservations(alice, 0, 20)).hasSize(1)
    }

    @DisplayName("쿠폰을 적용하면 할인액을 빼 최종 금액을 매기고 쿠폰은 USED 가 되며 금액 3종이 스냅샷된다.")
    @Test
    fun shouldApplyCouponDiscount() {
        seedFullInventoryAndRate()
        val couponId = issueCoupon()

        val info = sut.reserve(command().copy(issuedCouponId = couponId))

        assertThat(info.priceBeforeDiscount).isEqualByComparingTo("220000")
        assertThat(info.discountAmount).isEqualByComparingTo("20000")
        assertThat(info.totalPrice).isEqualByComparingTo("200000")
        assertThat(info.couponId).isEqualTo(couponId)
        assertThat(issuedCouponRepository.findById(couponId)!!.status).isEqualTo(CouponStatus.USED)
    }

    @DisplayName("예약을 취소하면 재고와 함께 사용한 쿠폰도 AVAILABLE 로 복원된다.")
    @Test
    fun shouldRestoreCouponOnCancel() {
        seedFullInventoryAndRate()
        val couponId = issueCoupon()
        val reserved = sut.reserve(command().copy(issuedCouponId = couponId))

        sut.cancel(alice, reserved.reservationId)

        assertThat(issuedCouponRepository.findById(couponId)!!.status).isEqualTo(CouponStatus.AVAILABLE)
        assertThat(inventoryRepository.findById(roomTypeId, checkIn)!!.reservedRooms).isEqualTo(0)
    }

    @DisplayName("타인의 쿠폰으로 예약하려 하면 403 으로 거절된다.")
    @Test
    fun shouldRejectReserveWithOthersCoupon() {
        seedFullInventoryAndRate()
        val couponId = issueCoupon(loginId = bob)

        assertThatThrownBy { sut.reserve(command().copy(issuedCouponId = couponId)) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.FORBIDDEN)
    }

    private fun issueCoupon(
        loginId: LoginId = alice,
        discount: DiscountValue = DiscountValue.of(DiscountType.FIXED, 20_000),
    ): Long {
        val templateId = couponTemplateRepository.save(CouponTemplateModel("선착순", discount, totalQuantity = 100)).id
        return couponService.issue(templateId, loginId).id
    }
}
