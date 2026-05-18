package com.stayloop.application.reservation

import com.stayloop.application.reservation.command.ReserveCommand
import com.stayloop.domain.coupon.CouponIssueModel
import com.stayloop.domain.coupon.CouponIssueRepository
import com.stayloop.domain.coupon.CouponIssueService
import com.stayloop.domain.coupon.CouponTemplateRepository
import com.stayloop.domain.common.value.Money
import com.stayloop.domain.coupon.value.Discount
import com.stayloop.domain.inventory.DailyRoomInventoryRepository
import com.stayloop.domain.property.PropertyRepository
import com.stayloop.domain.property.RoomTypeRepository
import com.stayloop.domain.rate.DailyRoomRateModel
import com.stayloop.domain.rate.DailyRoomRateRepository
import com.stayloop.domain.reservation.ReservationModel
import com.stayloop.domain.reservation.ReservationRepository
import com.stayloop.domain.reservation.ReservationService
import com.stayloop.domain.reservation.value.CouponSnapshot
import com.stayloop.domain.reservation.value.PropertySnapshot
import com.stayloop.domain.reservation.value.RoomTypeSnapshot
import com.stayloop.domain.reservation.value.StayPeriod
import com.stayloop.domain.user.UserRepository
import com.stayloop.domain.user.value.LoginId
import com.stayloop.infrastructure.cache.AvailabilityCacheStore
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 예약 생성·취소·조회 Facade.
 *
 * - `reserve` / `cancel` = `@Transactional` (재고 차감/복원 + 예약 저장 atomic).
 * - AC-4 부분 차감 후 실패 시 자동 롤백 — 단위 InMemory 더블로 검증 불가, 통합 테스트 책임.
 * - 본인 자원 인가 / `roomType.propertyId` 상호 검증 모두 본 Facade — 도메인 서비스는 인가 모름.
 */
@Service
class ReservationFacade(
    private val reservationRepository: ReservationRepository,
    private val inventoryRepository: DailyRoomInventoryRepository,
    private val rateRepository: DailyRoomRateRepository,
    private val propertyRepository: PropertyRepository,
    private val roomTypeRepository: RoomTypeRepository,
    private val reservationService: ReservationService,
    private val couponIssueRepository: CouponIssueRepository,
    private val couponTemplateRepository: CouponTemplateRepository,
    private val couponIssueService: CouponIssueService,
    private val userRepository: UserRepository,
    private val availabilityCacheStore: AvailabilityCacheStore,
    private val clock: Clock,
) {
    /**
     * 예약 생성. (AC-3/4/5 + ② 쿠폰 적용)
     *
     * 쿠폰 흐름: `couponId` 있으면 `resolveAndApplyCoupon` 으로 검증 + Discount 계산, reserve 후 *id 부여 다음*
     * `issue.use(reservation.id)` (CouponIssueService 의 *순수 계산 / 상태 변경 시간 분리* 정합).
     *
     * **결제 직전 DB 재확인 contract (D-5)**: 본 메서드는 `availabilityCacheStore` / `search:result:*` /
     * `getAvailableRooms` 의 응답을 *결정 근거로 사용하지 않는다*. 재고 / 가용성은 항상 비관적 락
     * (`findInventoriesForUpdate`) 으로 DB 직접 확인. cache stale → 더블부킹 위험. 회귀 가드 =
     * `ReservationCacheBypassTest`. reserve commit 후 cache evict 는 의무 (`evictAvailabilityAfterCommit`).
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
        // 다일자 락 순서 = date ASC — Repository SQL 과 Facade 양쪽 명시 (deadlock 회피, D-1).
        val sortedDates = period.datesToReserve().sorted()
        val inventories = inventoryRepository.findInventoriesForUpdate(roomType.id, sortedDates)
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

        val now = LocalDateTime.now(clock)
        val coupon = command.couponId?.let { resolveAndApplyCoupon(command.userId, it, rates, now) }

        val (updatedInventories, reservation) = reservationService.reserve(
            userId = command.userId,
            propertySnapshot = propertySnapshot,
            roomTypeSnapshot = roomTypeSnapshot,
            period = period,
            guestCount = command.guestCount,
            guest = command.guest,
            inventories = inventories,
            rates = rates,
            discount = coupon?.discount,
            couponSnapshot = coupon?.snapshot,
        )

        inventoryRepository.saveAll(updatedInventories)
        val saved = reservationRepository.save(reservation)
        // afterCommit evict — TX 안 evict 시 rollback 으로 cache 만 비어 stale 재진입 위험 (D-4).
        evictAvailabilityAfterCommit(roomType.id, sortedDates)

        // 쿠폰 사용 처리는 reservation.id 부여 후. 동시성 정합 — 같은 도메인 사고가 3 경로
        // (OptimisticLocking / UNIQUE / 도메인 throw) 로 발생 가능, 동일 메시지로 정규화 (식별자 노출 0).
        if (coupon != null) {
            try {
                coupon.issue.use(actor = coupon.actorId, reservationId = saved.id, now = now)
                couponIssueRepository.save(coupon.issue)
            } catch (e: OptimisticLockingFailureException) {
                throw CoreException(ErrorType.CONFLICT, COUPON_ALREADY_USED_MESSAGE, cause = e)
            } catch (e: DataIntegrityViolationException) {
                throw CoreException(ErrorType.CONFLICT, COUPON_ALREADY_USED_MESSAGE, cause = e)
            } catch (e: CoreException) {
                // CONFLICT 만 메시지 일반화, 그 외 도메인 throw (BAD_REQUEST / FORBIDDEN) 는 그대로 전파.
                if (e.errorType == ErrorType.CONFLICT) {
                    throw CoreException(ErrorType.CONFLICT, COUPON_ALREADY_USED_MESSAGE, cause = e)
                }
                throw e
            }
        }

        return ReservationInfo.from(saved)
    }

    /**
     * 쿠폰 검증 + Discount 계산 + snapshot 조립. `issue.use()` 는 호출자가 reservation.id 부여 후 별도 호출.
     * 본인 자원 인가 실패 시 일반화된 메시지 (식별자 노출 0).
     */
    private fun resolveAndApplyCoupon(
        actor: LoginId,
        couponId: Long,
        rates: List<DailyRoomRateModel>,
        now: LocalDateTime,
    ): AppliedCoupon {
        val issue = couponIssueRepository.findById(couponId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "사용할 수 없는 쿠폰입니다.")
        val user = userRepository.findByLoginId(actor)
            ?: throw CoreException(ErrorType.UNAUTHORIZED, "인증된 사용자 정보를 찾을 수 없습니다.")
        if (issue.userId != user.id) {
            throw CoreException(ErrorType.BAD_REQUEST, "사용할 수 없는 쿠폰입니다.")
        }
        val template = couponTemplateRepository.findById(issue.templateId)
            ?: throw CoreException(
                ErrorType.INTERNAL_ERROR,
                "쿠폰 템플릿이 누락되어 사용할 수 없습니다.",
            )

        val priceBefore = rates.fold(Money.ZERO) { acc, rate -> acc + rate.pricePerNight }
        val discount: Discount = couponIssueService.apply(issue, template, priceBefore, now)
        val snapshot = CouponSnapshot(
            couponId = issue.id,
            couponName = template.name.value,
            couponCode = template.code,
            discountType = template.discountValue.type,
        )
        return AppliedCoupon(
            issue = issue,
            actorId = user.id,
            discount = discount,
            snapshot = snapshot,
        )
    }

    private data class AppliedCoupon(
        val issue: CouponIssueModel,
        val actorId: Long,
        val discount: Discount,
        val snapshot: CouponSnapshot,
    )

    /** 예약 취소. cancel 의 inventory 복원도 비관적 락 (reserve 와 같은 date ASC 순서, deadlock 회피). */
    @Transactional
    fun cancel(loginId: LoginId, reservationId: Long): ReservationInfo {
        val reservation = reservationRepository.findById(reservationId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "존재하지 않는 예약입니다.")
        requireOwner(reservation, loginId, "취소")

        val period = reservation.period
        val sortedDates = period.datesToReserve().sorted()
        val inventories = inventoryRepository.findInventoriesForUpdate(
            reservation.roomTypeId,
            sortedDates,
        )

        val (updatedInventories, cancelled) = reservationService.cancel(
            reservation = reservation,
            inventories = inventories,
            now = LocalDateTime.now(clock),
        )

        inventoryRepository.saveAll(updatedInventories)
        val savedCancelled = reservationRepository.save(cancelled)
        evictAvailabilityAfterCommit(reservation.roomTypeId, sortedDates)
        return ReservationInfo.from(savedCancelled)
    }

    @Transactional(readOnly = true)
    fun getReservation(loginId: LoginId, reservationId: Long): ReservationInfo {
        val reservation = reservationRepository.findById(reservationId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "존재하지 않는 예약입니다.")
        requireOwner(reservation, loginId, "조회")
        return ReservationInfo.from(reservation)
    }

    @Transactional(readOnly = true)
    fun getMyReservations(loginId: LoginId, period: StayPeriod): List<ReservationInfo> =
        reservationRepository.findByUserId(loginId, period).map(ReservationInfo::from)

    /** 본인 자원 인가 가드. action = 메시지 행위명, 외부 식별자는 박지 않는다. */
    private fun requireOwner(reservation: ReservationModel, loginId: LoginId, action: String) {
        if (reservation.userId != loginId) {
            throw CoreException(ErrorType.FORBIDDEN, "본인 예약만 ${action}할 수 있습니다.")
        }
    }

    /**
     * AvailabilityCacheStore evict 를 TX commit 후 수행 (D-4).
     * TX 안 evict 후 rollback 시 cache 만 비어 stale 재진입. TX 없으면 즉시 evict (단위 테스트 호환).
     */
    private fun evictAvailabilityAfterCommit(roomTypeId: Long, dates: Collection<LocalDate>) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        availabilityCacheStore.evictForDates(roomTypeId, dates)
                    }
                },
            )
        } else {
            availabilityCacheStore.evictForDates(roomTypeId, dates)
        }
    }

    companion object {
        private const val COUPON_ALREADY_USED_MESSAGE = "이미 사용된 쿠폰입니다."
    }
}
