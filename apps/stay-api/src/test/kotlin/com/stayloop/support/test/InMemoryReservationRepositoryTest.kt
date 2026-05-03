package com.stayloop.support.test

import com.stayloop.domain.common.value.Money
import com.stayloop.domain.reservation.ReservationModel
import com.stayloop.domain.reservation.value.GuestInfo
import com.stayloop.domain.reservation.value.PropertySnapshot
import com.stayloop.domain.reservation.value.RoomTypeSnapshot
import com.stayloop.domain.reservation.value.StayPeriod
import com.stayloop.domain.user.value.LoginId
import com.stayloop.domain.user.value.PhoneNumber
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

class InMemoryReservationRepositoryTest {
    private val repository = InMemoryReservationRepository()
    private val loginA = LoginId("alpha01")
    private val loginB = LoginId("bravo02")

    @DisplayName("save 후 findById 로 같은 인스턴스를 조회할 수 있고, 자동 할당된 id 가 양수다.")
    @Test
    fun shouldRoundTripSaveAndFindById() {
        val reservation = newReservation(loginA, checkIn = LocalDate.of(2026, 5, 10))

        val saved = repository.save(reservation)

        assertThat(saved.id).isPositive()
        assertThat(repository.findById(saved.id)).isSameAs(saved)
    }

    @DisplayName("findByUserId overlap — 다음 4 케이스만 매치 (다른 사용자 / 비-overlap 은 제외).")
    @Test
    fun shouldMatchOverlap_andExcludeNonOverlapAndOtherUsers() {
        // (1) 검색 기간 [5/10, 5/13) — 매치되어야 하는 케이스들
        val fullyInside = newReservation(loginA, LocalDate.of(2026, 5, 10), LocalDate.of(2026, 5, 12))
        val leftOverlap = newReservation(loginA, LocalDate.of(2026, 5, 8), LocalDate.of(2026, 5, 11))
        val rightOverlap = newReservation(loginA, LocalDate.of(2026, 5, 12), LocalDate.of(2026, 5, 14))
        val coversAll = newReservation(loginA, LocalDate.of(2026, 5, 5), LocalDate.of(2026, 5, 20))
        // 비-overlap (이전·이후 / 다른 사용자) — 결과에 포함되면 안 됨
        val before = newReservation(loginA, LocalDate.of(2026, 5, 5), LocalDate.of(2026, 5, 10)) // checkOut == 5/10 → not after 5/10
        val after = newReservation(loginA, LocalDate.of(2026, 5, 13), LocalDate.of(2026, 5, 15)) // checkIn == 5/13 → not before 5/13
        val otherUser = newReservation(loginB, LocalDate.of(2026, 5, 11), LocalDate.of(2026, 5, 12))

        listOf(fullyInside, leftOverlap, rightOverlap, coversAll, before, after, otherUser).forEach(repository::save)

        val result = repository.findByUserId(
            loginA,
            StayPeriod(checkIn = LocalDate.of(2026, 5, 10), checkOut = LocalDate.of(2026, 5, 13)),
        )

        assertThat(result).hasSize(4)
        assertThat(result).containsExactlyInAnyOrder(fullyInside, leftOverlap, rightOverlap, coversAll)
    }

    @DisplayName("findByUserId 결과는 checkIn DESC, id DESC 로 정렬된다 — 입력 순서/시간 동률 모두 결정적.")
    @Test
    fun shouldSortByCheckInDescThenIdDesc() {
        // 같은 checkIn (5/10) 두 개 + 다른 checkIn 하나 — 입력은 일부러 뒤섞은 순서
        val sameCheckInLater = newReservation(loginA, LocalDate.of(2026, 5, 10), LocalDate.of(2026, 5, 12))
        val differentCheckIn = newReservation(loginA, LocalDate.of(2026, 5, 11), LocalDate.of(2026, 5, 13))
        val sameCheckInEarlier = newReservation(loginA, LocalDate.of(2026, 5, 10), LocalDate.of(2026, 5, 11))

        // save 순서: 5/10-12, 5/11-13, 5/10-11 → id 1, 2, 3 자동 할당
        repository.save(sameCheckInLater)
        repository.save(differentCheckIn)
        repository.save(sameCheckInEarlier)

        val result = repository.findByUserId(
            loginA,
            StayPeriod(checkIn = LocalDate.of(2026, 5, 9), checkOut = LocalDate.of(2026, 5, 14)),
        )

        // 1차 키 checkIn DESC: 5/11 > 5/10 → differentCheckIn 먼저
        // 2차 키 id DESC: 같은 checkIn 5/10 인 두 개 중 id 큰 것(sameCheckInEarlier id=3) 이 먼저
        assertThat(result).containsExactly(differentCheckIn, sameCheckInEarlier, sameCheckInLater)
    }

    @DisplayName("동일 인스턴스를 두 번 save 해도 id 는 한 번만 할당되고 행 수는 1 — 멱등 upsert.")
    @Test
    fun shouldKeepIdStableAcrossMultipleSaves() {
        val reservation = newReservation(loginA, LocalDate.of(2026, 5, 10))
        repository.save(reservation)
        val firstId = reservation.id

        repository.save(reservation)

        assertThat(reservation.id).isEqualTo(firstId)
        assertThat(
            repository.findByUserId(
                loginA,
                StayPeriod(LocalDate.of(2026, 5, 9), LocalDate.of(2026, 5, 13)),
            ),
        ).hasSize(1)
    }

    private fun newReservation(
        userId: LoginId,
        checkIn: LocalDate = LocalDate.of(2026, 5, 10),
        checkOut: LocalDate = checkIn.plusDays(2),
    ): ReservationModel = ReservationModel.create(
        userId = userId,
        property = PropertySnapshot(
            propertyId = 7L,
            propertyName = "Stayloop 호텔",
            propertyAddress = "서울 강남구",
        ),
        roomType = RoomTypeSnapshot(roomTypeId = 11L, roomTypeName = "디럭스 더블", maxGuests = 4),
        period = StayPeriod(checkIn = checkIn, checkOut = checkOut),
        guestCount = 2,
        guest = GuestInfo(name = "홍길동", phoneNumber = PhoneNumber("010-1234-5678")),
        totalPrice = Money.of(220_000L),
    )
}
