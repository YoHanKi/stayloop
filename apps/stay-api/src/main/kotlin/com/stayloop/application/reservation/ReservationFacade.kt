package com.stayloop.application.reservation

import com.stayloop.application.reservation.command.ReserveCommand
import com.stayloop.domain.inventory.DailyRoomInventoryRepository
import com.stayloop.domain.property.PropertyRepository
import com.stayloop.domain.property.RoomTypeRepository
import com.stayloop.domain.rate.DailyRoomRateRepository
import com.stayloop.domain.reservation.ReservationModel
import com.stayloop.domain.reservation.ReservationRepository
import com.stayloop.domain.reservation.ReservationService
import com.stayloop.domain.reservation.value.PropertySnapshot
import com.stayloop.domain.reservation.value.RoomTypeSnapshot
import com.stayloop.domain.reservation.value.StayPeriod
import com.stayloop.domain.user.value.LoginId
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

/**
 * 예약 생성·취소·조회 (시퀀스 2/3) Facade. (`docs/design/02-sequence-diagram.md §2/§3`,
 * `docs/plan/week2-3.md §⑧ Phase C`)
 *
 * **트랜잭션 정책**:
 * - `reserve` / `cancel` — `@Transactional` (재고 차감/복원 + 예약 저장이 한 TX 안에서 atomic)
 * - 조회 — `readOnly = true`
 *
 * **AC-4 부분 차감 후 실패 시 롤백** — `ReservationService.reserve` 가 도중에 throw 하면 같은 TX 의 inventory
 * 차감이 자동 롤백된다. 도메인 모델의 `reserveOne()` 은 인메모리 상태를 변경하지만, JPA dirty checking +
 * 트랜잭션 롤백이 DB 행을 원복. **단위 테스트로는 InMemory Repository 가 *돌이킬 수 없으므로*** 이
 * AC 는 `@SpringBootTest` 통합 테스트에서 검증한다 (Phase C-Test 분리 결정).
 *
 * **본인 자원 인가** (AC-7) — `cancel` / `getReservation` / `getMyReservations` 모두 본 Facade 에서
 * `reservation.userId == loginId` 비교 후 FORBIDDEN. 도메인 서비스는 인가를 모름 (CLAUDE.md 정책).
 *
 * **상호 검증** — `roomType.propertyId == request.propertyId` 도 본 Facade 에서 검증 (다른 숙소 객실 잘못
 * 조합으로 예약하는 사고 차단).
 */
@Service
class ReservationFacade(
    private val reservationRepository: ReservationRepository,
    private val inventoryRepository: DailyRoomInventoryRepository,
    private val rateRepository: DailyRoomRateRepository,
    private val propertyRepository: PropertyRepository,
    private val roomTypeRepository: RoomTypeRepository,
    private val reservationService: ReservationService,
    private val clock: Clock,
) {
    /**
     * 예약 생성 — 시퀀스 2 흐름. (AC-3, AC-4, AC-5)
     */
    @Transactional
    fun reserve(command: ReserveCommand): ReservationInfo {
        val property = propertyRepository.findById(command.propertyId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "존재하지 않는 숙소입니다.")
        val roomType = roomTypeRepository.findById(command.roomTypeId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "존재하지 않는 객실 타입입니다.")
        if (roomType.propertyId != command.propertyId) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "객실(${command.roomTypeId}) 이 숙소(${command.propertyId}) 에 속하지 않습니다.",
            )
        }

        val period = command.period
        val inventories = inventoryRepository.findAllInRange(roomType.id, period.checkIn, period.checkOut)
        val rates = rateRepository.findAllInRange(roomType.id, period.checkIn, period.checkOut)

        val propertySnapshot = PropertySnapshot(
            propertyId = property.id,
            propertyName = property.name.value,
            propertyAddress = property.address.fullAddress,
            propertyPolicy = null,
        )
        val roomTypeSnapshot = RoomTypeSnapshot(
            roomTypeId = roomType.id,
            roomTypeName = roomType.name.value,
            maxGuests = roomType.guestCount.max,
        )

        val (updatedInventories, reservation) = reservationService.reserve(
            userId = command.userId,
            propertySnapshot = propertySnapshot,
            roomTypeSnapshot = roomTypeSnapshot,
            period = period,
            guestCount = command.guestCount,
            guest = command.guest,
            inventories = inventories,
            rates = rates,
        )

        inventoryRepository.saveAll(updatedInventories)
        return ReservationInfo.from(reservationRepository.save(reservation))
    }

    /**
     * 예약 취소 — 시퀀스 3 흐름. (AC-7)
     */
    @Transactional
    fun cancel(loginId: LoginId, reservationId: Long): ReservationInfo {
        val reservation = reservationRepository.findById(reservationId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "존재하지 않는 예약입니다.")
        requireOwner(reservation, loginId, "취소")

        val period = reservation.period
        val inventories = inventoryRepository.findAllInRange(
            reservation.roomTypeId,
            period.checkIn,
            period.checkOut,
        )

        val (updatedInventories, cancelled) = reservationService.cancel(
            reservation = reservation,
            inventories = inventories,
            now = LocalDateTime.now(clock),
        )

        inventoryRepository.saveAll(updatedInventories)
        return ReservationInfo.from(reservationRepository.save(cancelled))
    }

    /**
     * 본인 예약 단건 조회. (AC-7)
     */
    @Transactional(readOnly = true)
    fun getReservation(loginId: LoginId, reservationId: Long): ReservationInfo {
        val reservation = reservationRepository.findById(reservationId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "존재하지 않는 예약입니다.")
        requireOwner(reservation, loginId, "조회")
        return ReservationInfo.from(reservation)
    }

    /**
     * 본인 예약 목록 — `period` 와 *겹치는* 예약. (AC-7)
     * 정렬은 Repository 가 `checkIn DESC, id DESC` 고정.
     */
    @Transactional(readOnly = true)
    fun getMyReservations(loginId: LoginId, period: StayPeriod): List<ReservationInfo> =
        reservationRepository.findByUserId(loginId, period).map(ReservationInfo::from)

    /**
     * 본인 자원 인가 가드 — `reservation.userId == loginId` 가 아니면 FORBIDDEN.
     * 도메인 서비스는 `X-Loopers-LoginId` 를 모르므로 인가는 Application Layer 책임 (CLAUDE.md / verify-architecture).
     *
     * @param action  메시지에 노출되는 행위명 (`"취소"` / `"조회"` 등). 외부 식별자는 박지 않는다 — verify-code §12.
     */
    private fun requireOwner(reservation: ReservationModel, loginId: LoginId, action: String) {
        if (reservation.userId != loginId) {
            throw CoreException(ErrorType.FORBIDDEN, "본인 예약만 ${action}할 수 있습니다.")
        }
    }
}
