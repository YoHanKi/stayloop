package com.stayloop.domain.reservation

import com.stayloop.domain.common.value.Money
import com.stayloop.domain.reservation.value.GuestInfo
import com.stayloop.domain.reservation.value.PropertySnapshot
import com.stayloop.domain.reservation.value.ReservationStatus
import com.stayloop.domain.reservation.value.RoomTypeSnapshot
import com.stayloop.domain.reservation.value.StayPeriod
import com.stayloop.domain.user.value.LoginId
import com.stayloop.domain.user.value.PhoneNumber
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ReservationModelTest {

    @DisplayName("정상 생성 시 status 는 PENDING, cancelledAt 은 null 이며 모든 박제 필드가 노출된다.")
    @Test
    fun shouldCreateInPendingState() {
        val reservation = newReservation()

        assertThat(reservation.status).isEqualTo(ReservationStatus.PENDING)
        assertThat(reservation.cancelledAt).isNull()
        assertThat(reservation.userId).isEqualTo(LoginId("alpha01"))
        assertThat(reservation.propertyId).isEqualTo(7L)
        assertThat(reservation.roomTypeId).isEqualTo(11L)
        assertThat(reservation.guestCount).isEqualTo(2)
    }

    @DisplayName("guestCount 가 maxGuests 를 초과하면 BAD_REQUEST (AC-5 — 인원 초과 거절).")
    @Test
    fun shouldReject_whenGuestCountExceedsMax() {
        assertThatThrownBy {
            newReservation(guestCount = 5, maxGuests = 4)
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("guestCount 가 0 이하면 BAD_REQUEST.")
    @Test
    fun shouldReject_whenGuestCountIsZeroOrNegative() {
        assertThatThrownBy {
            newReservation(guestCount = 0, maxGuests = 4)
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("propertyId 와 박제된 property.propertyId 가 다르면 BAD_REQUEST (FK ↔ 박제 정합).")
    @Test
    fun shouldReject_whenPropertyIdMismatchesSnapshot() {
        assertThatThrownBy {
            newReservation(propertyId = 7L, snapshotPropertyId = 999L)
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("roomTypeId 와 박제된 roomType.roomTypeId 가 다르면 BAD_REQUEST (FK ↔ 박제 정합).")
    @Test
    fun shouldReject_whenRoomTypeIdMismatchesSnapshot() {
        assertThatThrownBy {
            newReservation(roomTypeId = 11L, snapshotRoomTypeId = 999L)
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("confirm() 은 PENDING → CONFIRMED 로 전이하고, 다시 confirm() 하면 CONFLICT.")
    @Test
    fun shouldConfirm_andRejectDoubleConfirm() {
        val reservation = newReservation()

        reservation.confirm()
        assertThat(reservation.status).isEqualTo(ReservationStatus.CONFIRMED)

        // CONFIRMED → CONFIRMED 는 자기 전이 — 매트릭스에서 거절
        assertThatThrownBy { reservation.confirm() }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT)
    }

    @DisplayName("checkIn() 은 PENDING 에서 호출 시 CONFLICT — 결제(CONFIRMED) 단계를 건너뛴 입실 차단.")
    @Test
    fun shouldRejectCheckIn_whenPendingNotConfirmed() {
        val reservation = newReservation()

        assertThatThrownBy { reservation.checkIn() }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT)
    }

    @DisplayName("checkIn() / checkOut() 은 합법 흐름 PENDING → CONFIRMED → CHECKED_IN → CHECKED_OUT 을 통과한다.")
    @Test
    fun shouldPassFullHappyPath() {
        val reservation = newReservation()

        reservation.confirm()
        reservation.checkIn()
        assertThat(reservation.status).isEqualTo(ReservationStatus.CHECKED_IN)

        reservation.checkOut()
        assertThat(reservation.status).isEqualTo(ReservationStatus.CHECKED_OUT)
    }

    @DisplayName("CHECKED_OUT 이후 cancel() 은 CONFLICT — terminal 상태에서 어떤 전이도 거절.")
    @Test
    fun shouldRejectCancel_whenCheckedOut() {
        val reservation = newReservation()
        reservation.confirm()
        reservation.checkIn()
        reservation.checkOut()

        assertThatThrownBy { reservation.cancel(now = LocalDateTime.of(2026, 5, 11, 10, 0)) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT)
    }

    @DisplayName("cancel(now) 은 CANCELLED 로 전이하고 cancelledAt 을 주입된 시각으로 박제한다.")
    @Test
    fun shouldCancelAndStampCancelledAt() {
        val reservation = newReservation()
        val cancelTime = LocalDateTime.of(2026, 5, 1, 14, 30)

        reservation.cancel(now = cancelTime)

        assertThat(reservation.status).isEqualTo(ReservationStatus.CANCELLED)
        assertThat(reservation.cancelledAt).isEqualTo(cancelTime)
    }

    @DisplayName("CHECKED_IN 이후 cancel() 은 CONFLICT — 입실 후 취소는 환불 정책 동반(05 §8.2), 본 라운드 거절.")
    @Test
    fun shouldRejectCancel_whenCheckedIn() {
        val reservation = newReservation()
        reservation.confirm()
        reservation.checkIn()

        assertThatThrownBy { reservation.cancel(now = LocalDateTime.of(2026, 5, 11, 10, 0)) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT)
    }

    @DisplayName("markNoShow() 는 CONFIRMED 에서만 합법 — PENDING 에서 호출 시 CONFLICT.")
    @Test
    fun shouldRejectNoShow_whenPending() {
        val reservation = newReservation()

        assertThatThrownBy { reservation.markNoShow() }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT)
    }

    @DisplayName("상태 전이 거절 시 객체는 불변 — Strong Exception Safety. status / cancelledAt 모두 변하지 않는다.")
    @Test
    fun shouldKeepStateUnchanged_onTransitionRejection() {
        val reservation = newReservation()
        reservation.confirm()
        val statusBefore = reservation.status
        val cancelledAtBefore = reservation.cancelledAt

        // CONFIRMED 에서 checkOut 은 거절 (CHECKED_IN 을 거치지 않음)
        runCatching { reservation.checkOut() }

        assertThat(reservation.status).isEqualTo(statusBefore)
        assertThat(reservation.cancelledAt).isEqualTo(cancelledAtBefore)
    }

    private fun newReservation(
        propertyId: Long = 7L,
        roomTypeId: Long = 11L,
        snapshotPropertyId: Long = propertyId,
        snapshotRoomTypeId: Long = roomTypeId,
        guestCount: Int = 2,
        maxGuests: Int = 4,
    ): ReservationModel = ReservationModel.create(
        userId = LoginId("alpha01"),
        propertyId = propertyId,
        roomTypeId = roomTypeId,
        property = PropertySnapshot(
            propertyId = snapshotPropertyId,
            propertyName = "Stayloop 호텔 강남점",
            propertyAddress = "서울특별시 강남구 테헤란로 1",
            propertyPolicy = "체크인 15시 / 체크아웃 11시",
        ),
        roomType = RoomTypeSnapshot(
            roomTypeId = snapshotRoomTypeId,
            roomTypeName = "디럭스 더블",
            maxGuests = maxGuests,
        ),
        period = StayPeriod(
            checkIn = LocalDate.of(2026, 5, 10),
            checkOut = LocalDate.of(2026, 5, 12),
        ),
        guestCount = guestCount,
        guest = GuestInfo(name = "홍길동", phoneNumber = PhoneNumber("010-1234-5678")),
        totalPrice = Money.of(220_000L),
    )
}
