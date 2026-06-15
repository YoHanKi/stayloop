package com.stayloop.application.reservation

import com.stayloop.application.reservation.command.ReserveCommand
import com.stayloop.domain.common.value.Money
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
import com.stayloop.support.test.InMemoryDailyRoomInventoryRepository
import com.stayloop.support.test.InMemoryDailyRoomRateRepository
import com.stayloop.support.test.InMemoryPropertyRepository
import com.stayloop.support.test.InMemoryReservationRepository
import com.stayloop.support.test.InMemoryRoomTypeRepository
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
        val clock = Clock.fixed(Instant.parse("2026-05-30T00:00:00Z"), ZoneOffset.UTC)
        sut = ReservationFacade(
            propertyRepository,
            roomTypeRepository,
            rateRepository,
            DailyRoomInventoryService(inventoryRepository),
            ReservationService(ReservationPriceCalculator()),
            reservationRepository,
            clock,
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
}
