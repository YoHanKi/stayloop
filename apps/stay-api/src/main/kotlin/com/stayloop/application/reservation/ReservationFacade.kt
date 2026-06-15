package com.stayloop.application.reservation

import com.stayloop.application.reservation.command.ReserveCommand
import com.stayloop.domain.inventory.DailyRoomInventoryService
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
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

/**
 * 예약 생성/취소/조회 유스케이스. 트랜잭션 경계가 곧 유스케이스라, 임계 구간에서 한 일자라도 실패하면
 * 전체 롤백으로 재고 원자성을 보장한다(AC-4). 동시 차감(더블부킹)은 [DailyRoomInventoryService] 가
 * 조건부 원자 차감으로 막는다(04-b §5-1).
 *
 * 임계 구간 최소화(04-b §4): 사전 조회·요금 견적·예약 모델 생성은 락 밖에서 끝내고, 락 구간에는
 * 재고 차감/복원만 둔다. 같은 자원 순서(재고 일자 오름차순)로 접근해 생성·취소 사이 데드락을 줄인다.
 *
 * 본인 자원 인가는 [requireOwner] 로 일관화한다 — 헤더의 LoginId 와 예약 소유자를 비교해 다르면 403.
 * 도메인 서비스는 헤더를 모른 채 남는다.
 */
@Service
class ReservationFacade(
    private val propertyRepository: PropertyRepository,
    private val roomTypeRepository: RoomTypeRepository,
    private val rateRepository: DailyRoomRateRepository,
    private val inventoryService: DailyRoomInventoryService,
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
        // 락 밖 — 요금 견적과 예약 모델 생성(인원·요금 검증 포함).
        val rates = rateRepository.findAllInRange(roomType.id, period.checkIn, period.checkOut)
        val reservation = reservationService.reserve(
            userId = command.loginId,
            property = PropertySnapshot.from(property),
            roomType = RoomTypeSnapshot.from(roomType),
            period = period,
            guestCount = command.guestCount,
            guest = command.guestInfo(),
            rates = rates,
        )
        // 임계 구간 — 재고 차감만. 한 일자라도 매진이면 CONFLICT 로 전체 롤백(부분 차감 없음).
        reserveInventory(roomType.id, period)
        return ReservationInfo.from(reservationRepository.save(reservation))
    }

    @Transactional
    fun cancel(loginId: LoginId, reservationId: Long): ReservationInfo {
        val reservation = reservationRepository.findById(reservationId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "존재하지 않는 예약입니다.")
        requireOwner(reservation, loginId)

        reservationService.cancel(reservation, LocalDateTime.now(clock))
        releaseInventory(reservation.roomTypeId, reservation.period)
        return ReservationInfo.from(reservationRepository.save(reservation))
    }

    /**
     * 재고 차감을 임계 구간에서 수행한다. 행 락 경합으로 인한 락 대기 타임아웃·데드락 victim 은
     * 5xx 가 아니라 CONFLICT(혼잡)로 사용자에게 보인다(04-a §8). 실패 종류별 재시도는 chunk 3 로 미룬다.
     */
    private fun reserveInventory(roomTypeId: Long, period: StayPeriod) {
        try {
            inventoryService.reserve(roomTypeId, period.datesToReserve())
        } catch (e: PessimisticLockingFailureException) {
            throw CoreException(ErrorType.CONFLICT, "예약 요청이 몰려 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.")
        }
    }

    private fun releaseInventory(roomTypeId: Long, period: StayPeriod) {
        try {
            inventoryService.release(roomTypeId, period.datesToReserve())
        } catch (e: PessimisticLockingFailureException) {
            throw CoreException(ErrorType.CONFLICT, "취소 요청이 몰려 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.")
        }
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
