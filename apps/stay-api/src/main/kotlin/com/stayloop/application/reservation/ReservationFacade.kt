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
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
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
    private val couponIssueRepository: CouponIssueRepository,
    private val couponTemplateRepository: CouponTemplateRepository,
    private val couponIssueService: CouponIssueService,
    private val userRepository: UserRepository,
    private val clock: Clock,
) {
    /**
     * 예약 생성 — 시퀀스 2 흐름. (AC-3, AC-4, AC-5, ② 합류 — 쿠폰 적용)
     *
     * **쿠폰 적용 흐름** (`docs/plan/week4.md` ② Commit 4, `docs/plan/week4/decision.md` D-3 / D-9):
     * 1. `command.couponId == null` → 쿠폰 미적용. 기존 흐름 그대로.
     * 2. `command.couponId != null` →
     *    a. `couponIssueRepository.findById` (없으면 NOT_FOUND)
     *    b. **본인 자원 인가** — `issue.userId == users.findByLoginId(actor).id` 비교
     *       (식별자 노출 금지 — 메시지에 couponId / userId 박지 X, verify-code §12)
     *    c. `couponTemplateRepository.findById(issue.templateId)` (없으면 INTERNAL_ERROR — 데이터 정합 깨짐)
     *    d. *priceBeforeDiscount* = `rates` 합산 (Service 와 동일 합산을 한 번 더 — calculator 가 검증)
     *    e. `couponIssueService.apply(issue, template, priceBefore, now): Discount`
     *    f. `CouponSnapshot` 박제 조립 (id / name / code / type)
     * 3. `ReservationService.reserve(..., discount, couponSnapshot)` — 재고 차감 + 합산 + 박제 정합 검증
     * 4. `reservationRepository.save(reservation)` — IDENTITY id 부여
     * 5. **id 부여 후** `issue.use(actor.id, reservation.id, now)` + `couponIssueRepository.save(issue)`
     *    (CouponIssueService KDoc 의 *순수 계산 / 상태 변경 분리* 정합 — `decision.md` D-9)
     *
     * **부분 실패 롤백** — 단일 `@Transactional` 안에서 진행되므로 (a) 재고 차감 도중 throw, (b) 쿠폰 사용
     * 도중 throw, (c) 어떤 검증 실패라도 *전체 롤백* (week4-quests Implementation Quest "쿠폰, 일자별 재고,
     * 결제 금액 처리 등 하나라도 작업이 실패하면 모두 롤백" 정합).
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
        // 다일자 락 순서 = `date ASC` — Repository SQL (`orderBy(d.date.asc())`) + Facade 진입점 양쪽에 명시한다
        // (`docs/plan/week4.md` ③ Phase A, decision.md D-1, verify-code §19-B 문서 ↔ 가드 정합).
        // `period.datesToReserve()` 는 이미 [checkIn, checkOut) 오름차순이지만, 호출자 의도를 *명시적으로*
        // 박아 누락된 호출자가 등장해도 정렬 의무가 가시화되도록 `.sorted()` 를 한 번 더 통과시킨다.
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

        // 예약 저장으로 reservation.id 가 부여된 *후* 쿠폰 사용 처리 (CouponIssueService KDoc 의 시간 분리).
        // **동시성 변환** (`docs/plan/week4.md` ③ Phase B-2) — `@Version` 낙관적 락 충돌 또는
        // `used_reservation_id` UNIQUE 제약 위반은 모두 *같은 의미* (이미 사용된 쿠폰의 동시 사용) 이므로
        // CONFLICT 로 일반화. 식별자 (couponId / userId / reservationId) 는 메시지에 노출하지 않는다
        // (verify-code §12). JPA 예외는 `cause` 로 보존되어 서버 로그에서만 추적 (ApiControllerAdvice 가
        // CoreException → 409 변환).
        if (coupon != null) {
            coupon.issue.use(actor = coupon.actorId, reservationId = saved.id, now = now)
            try {
                couponIssueRepository.save(coupon.issue)
            } catch (e: OptimisticLockingFailureException) {
                throw CoreException(ErrorType.CONFLICT, "이미 사용된 쿠폰입니다.", cause = e)
            } catch (e: DataIntegrityViolationException) {
                // used_reservation_id UNIQUE 위반 — 다른 reservation 에 이미 사용 중인 쿠폰
                throw CoreException(ErrorType.CONFLICT, "이미 사용된 쿠폰입니다.", cause = e)
            }
        }

        return ReservationInfo.from(saved)
    }

    /**
     * 쿠폰 검증 + Discount 계산 + CouponSnapshot 박제 조립. **issue 의 사용 처리(`use`) 는 호출자가 별도 호출** —
     * `reservation.id` 가 부여된 *후* 호출되어야 하므로 본 메서드는 *사용 처리 직전 상태* 까지만 준비.
     *
     * 본인 자원 인가 시 메시지 일반화 — 식별자(couponId / userId) 노출 금지 (verify-code §12).
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
            // 식별자 노출 금지 — 본인 자원이 아님을 일반화된 메시지로 거절
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

    /**
     * 쿠폰 적용을 위한 *정합 묶음* — issue / actorId / discount / snapshot 의 4 인자가 흩어지지 않게
     * Facade 내부에서만 쓰는 Sealed VO. 외부 노출 X.
     */
    private data class AppliedCoupon(
        val issue: CouponIssueModel,
        val actorId: Long,
        val discount: Discount,
        val snapshot: CouponSnapshot,
    )

    /**
     * 예약 취소 — 시퀀스 3 흐름. (AC-7)
     */
    @Transactional
    fun cancel(loginId: LoginId, reservationId: Long): ReservationInfo {
        val reservation = reservationRepository.findById(reservationId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "존재하지 않는 예약입니다.")
        requireOwner(reservation, loginId, "취소")

        val period = reservation.period
        // cancel 흐름의 inventory 복원도 비관적 락으로 잡는다 — 동시에 진행 중인 reserve 가 같은 row 를 차감
        // 하는 race 를 차단 (단순 `findAllInRange` 사용 시 release 직전 reserve 가 끼어들어 음수 진입 시도 또는
        // CONFLICT 가 잘못된 위치에서 발동될 위험). `date ASC` 정렬은 reserve 와 동일 순서 — 데드락 회피.
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
