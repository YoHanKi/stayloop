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
import com.stayloop.domain.user.value.LoginId
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

/**
 * 예약 생성/취소/조회 유스케이스. 트랜잭션 경계가 곧 유스케이스라, 부분 차감 후 실패 시 전체 롤백으로
 * 재고 원자성을 보장한다(AC-4). 동시 차감(더블부킹) 안전성은 4주차 락 영역의 알려진 공백이다.
 *
 * 본인 자원 인가는 [requireOwner] 로 일관화한다 — 헤더의 LoginId 와 예약 소유자를 비교해 다르면 403.
 * 도메인 서비스는 헤더를 모른 채 남는다.
 */
@Service
class ReservationFacade(
    private val propertyRepository: PropertyRepository,
    private val roomTypeRepository: RoomTypeRepository,
    private val inventoryRepository: DailyRoomInventoryRepository,
    private val rateRepository: DailyRoomRateRepository,
    private val reservationService: ReservationService,
    private val reservationRepository: ReservationRepository,
    private val clock: Clock,
) {
    @Transactional
    fun reserve(command: ReserveCommand): ReservationInfo {
        val property = propertyRepository.findById(command.propertyId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "존재하지 않는 숙소입니다.")
        val roomType = roomTypeRepository.findById(command.roomTypeId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "존재하지 않는 객실 타입입니다.")
        if (roomType.propertyId != command.propertyId) {
            throw CoreException(ErrorType.BAD_REQUEST, "객실 타입이 해당 숙소에 속하지 않습니다.")
        }

        val period = command.period()
        val inventories = inventoryRepository.findAllInRange(roomType.id, period.checkIn, period.checkOut)
        val rates = rateRepository.findAllInRange(roomType.id, period.checkIn, period.checkOut)

        val reservation = reservationService.reserve(
            userId = command.loginId,
            property = PropertySnapshot.from(property),
            roomType = RoomTypeSnapshot.from(roomType),
            period = period,
            guestCount = command.guestCount,
            guest = command.guestInfo(),
            inventories = inventories,
            rates = rates,
        )
        inventoryRepository.saveAll(inventories)
        return ReservationInfo.from(reservationRepository.save(reservation))
    }

    @Transactional
    fun cancel(loginId: LoginId, reservationId: Long): ReservationInfo {
        val reservation = reservationRepository.findById(reservationId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "존재하지 않는 예약입니다.")
        requireOwner(reservation, loginId)

        val period = reservation.period
        val inventories = inventoryRepository.findAllInRange(reservation.roomTypeId, period.checkIn, period.checkOut)
        reservationService.cancel(reservation, inventories, LocalDateTime.now(clock))
        inventoryRepository.saveAll(inventories)
        return ReservationInfo.from(reservationRepository.save(reservation))
    }

    @Transactional(readOnly = true)
    fun getReservation(loginId: LoginId, reservationId: Long): ReservationInfo {
        val reservation = reservationRepository.findById(reservationId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "존재하지 않는 예약입니다.")
        requireOwner(reservation, loginId)
        return ReservationInfo.from(reservation)
    }

    @Transactional(readOnly = true)
    fun getMyReservations(loginId: LoginId, page: Int, size: Int): List<ReservationInfo> =
        reservationRepository.findByUserId(loginId, page, size).map { ReservationInfo.from(it) }

    private fun requireOwner(reservation: ReservationModel, loginId: LoginId) {
        if (reservation.userId != loginId) {
            throw CoreException(ErrorType.FORBIDDEN, "본인의 예약만 접근할 수 있습니다.")
        }
    }
}
