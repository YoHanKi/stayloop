package com.stayloop.application.reservation

import com.stayloop.application.reservation.command.ReserveCommand
import com.stayloop.domain.coupon.CouponService
import com.stayloop.domain.coupon.IssuedCouponModel
import com.stayloop.domain.coupon.IssuedCouponRepository
import com.stayloop.domain.inventory.DailyRoomInventoryService
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
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.LocalDateTime
import java.util.concurrent.ThreadLocalRandom

/**
 * 예약 생성/취소/조회 유스케이스. 재고 차감·쿠폰 사용·금액 스냅샷·예약 저장을 한 트랜잭션 임계 구간으로 묶어,
 * 한 단계라도 실패하면 전체 롤백한다(04-b §5-3). 데드락 victim·락 대기 타임아웃 같은 일시 충돌은 짧은 지터
 * 백오프로 재시도해 흡수하고(사용자에겐 한 번의 결과만), 조건부 0행(매진·소진)은 비즈니스 실패라 재시도하지 않는다(04-a §8).
 *
 * 임계 구간 최소화(04-b §4): 사전 조회(요금·쿠폰)는 락 밖에서 끝내고, 락 구간엔 차감·사용·저장만 둔다.
 * 같은 자원 순서(재고 일자순 → 쿠폰)를 생성·취소가 함께 따라 데드락 표면을 줄인다.
 *
 * 재시도가 매 시도 새 트랜잭션을 열어야 하므로 reserve/cancel 은 선언적 `@Transactional` 대신
 * [TransactionTemplate] 로 트랜잭션 경계를 잡는다(조회는 `@Transactional(readOnly)` 그대로).
 */
@Service
class ReservationFacade(
    private val propertyRepository: PropertyRepository,
    private val roomTypeRepository: RoomTypeRepository,
    private val rateRepository: DailyRoomRateRepository,
    private val inventoryService: DailyRoomInventoryService,
    private val couponService: CouponService,
    private val issuedCouponRepository: IssuedCouponRepository,
    private val reservationService: ReservationService,
    private val reservationRepository: ReservationRepository,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val txTemplate = TransactionTemplate(transactionManager)

    fun reserve(command: ReserveCommand): ReservationInfo {
        // 락 밖 — 존재/소유 검증과 사전 조회.
        val property = propertyRepository.findById(command.propertyId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "존재하지 않는 숙소입니다.")
        val roomType = roomTypeRepository.findById(command.roomTypeId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "존재하지 않는 객실 타입입니다.")
        if (roomType.propertyId != command.propertyId) {
            throw CoreException(ErrorType.BAD_REQUEST, "객실 타입이 해당 숙소에 속하지 않습니다.")
        }
        val period = command.period()
        val rates = rateRepository.findAllInRange(roomType.id, period.checkIn, period.checkOut)
        val coupon = command.issuedCouponId?.let { loadOwnedCoupon(it, command.loginId) }

        // 임계 구간 — 재고 차감 → 쿠폰 사용 → 예약 저장. 한 단계 실패 시 전체 롤백, 일시 충돌은 재시도로 흡수.
        return retryOnTransientLock {
            txTemplate.execute {
                inventoryService.reserve(roomType.id, period.datesToReserve())
                coupon?.let { couponService.use(it.id, LocalDateTime.now(clock)) }
                val reservation = reservationService.reserve(
                    userId = command.loginId,
                    property = PropertySnapshot.from(property),
                    roomType = RoomTypeSnapshot.from(roomType),
                    period = period,
                    guestCount = command.guestCount,
                    guest = command.guestInfo(),
                    rates = rates,
                    discount = coupon?.discount,
                    couponId = coupon?.id,
                )
                ReservationInfo.from(reservationRepository.save(reservation))
            }!!
        }
    }

    fun cancel(loginId: LoginId, reservationId: Long): ReservationInfo =
        retryOnTransientLock {
            txTemplate.execute {
                val reservation = reservationRepository.findById(reservationId)
                    ?: throw CoreException(ErrorType.NOT_FOUND, "존재하지 않는 예약입니다.")
                requireOwner(reservation, loginId)

                reservationService.cancel(reservation, LocalDateTime.now(clock))
                inventoryService.release(reservation.roomTypeId, reservation.period.datesToReserve())
                reservation.couponId?.let { couponService.restore(it) }
                ReservationInfo.from(reservationRepository.save(reservation))
            }!!
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

    /** 본인 소유 발급 쿠폰을 찾는다. 없으면 NOT_FOUND, 타인 쿠폰이면 FORBIDDEN. */
    private fun loadOwnedCoupon(issuedCouponId: Long, loginId: LoginId): IssuedCouponModel {
        val coupon = issuedCouponRepository.findById(issuedCouponId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "존재하지 않는 쿠폰입니다.")
        if (coupon.userId != loginId) {
            throw CoreException(ErrorType.FORBIDDEN, "본인의 쿠폰만 사용할 수 있습니다.")
        }
        return coupon
    }

    /** 일시적 락 충돌(데드락 victim·락 대기 타임아웃)을 짧은 지터 백오프로 재시도하고, 한계 초과 시 CONFLICT. */
    private fun <T> retryOnTransientLock(action: () -> T): T {
        var attempts = 0
        while (true) {
            try {
                return action()
            } catch (e: PessimisticLockingFailureException) {
                attempts++
                if (attempts >= MAX_LOCK_RETRIES) {
                    throw CoreException(ErrorType.CONFLICT, "예약 요청이 몰려 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.")
                }
                Thread.sleep(ThreadLocalRandom.current().nextLong(BACKOFF_BASE_MS, BACKOFF_BASE_MS * 2))
            }
        }
    }

    private fun requireOwner(reservation: ReservationModel, loginId: LoginId) {
        if (reservation.userId != loginId) {
            throw CoreException(ErrorType.FORBIDDEN, "본인의 예약만 접근할 수 있습니다.")
        }
    }

    companion object {
        private const val MAX_LOCK_RETRIES = 3
        private const val BACKOFF_BASE_MS = 20L
    }
}
