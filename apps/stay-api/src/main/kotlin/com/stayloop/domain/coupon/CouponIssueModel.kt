package com.stayloop.domain.coupon

import com.stayloop.domain.BaseEntity
import com.stayloop.domain.coupon.value.CouponIssueStatus
import com.stayloop.domain.user.value.LoginId
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.AttributeOverride
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * 발급된 쿠폰 인스턴스 Aggregate Root. (`docs/plan/week4.md` ① Phase A-4, `docs/plan/week4/decision.md` D-2)
 *
 * 사용자가 *발급받아 1회 사용하는 쿠폰*. `CouponTemplate` (정책) 과 분리된 별 Aggregate — 생명주기 / 소유권 /
 * 동시성 자원이 다르다.
 *
 * 박제 컬럼:
 * - `templateId` (FK) — `CouponTemplate.id` 참조 (소프트 FK, JPA `@ManyToOne` 미사용 — Aggregate 경계)
 * - `userId: LoginId` (Embedded) — 본인 자원 인가에 사용
 * - `status: CouponIssueStatus` — AVAILABLE / USED / EXPIRED (3 상태)
 * - `issuedAt` — 발급 시각 (정렬 / 통계 기준)
 * - `usedAt?` — 사용 시각 (USED 시점 박제)
 * - `usedReservationId?` — 사용 시 reservation FK. **DB UNIQUE 제약 (A-5 시점)** 으로 *한 예약에 한 쿠폰만*
 *   다층 가드 (`docs/plan/week4/decision.md` D-1 #2 — 도메인 가드 + DB UNIQUE = defense in depth).
 *
 * 도메인 행동:
 * - [use] — `requireOwner(actor)` + `canTransitTo(USED)` 검증 → 변경 (Strong Exception Safety).
 * - [expire] — 조회 시점 lazy 만료 전이 (배치 트리거 합류 전 본 라운드는 모델 자리만).
 *
 * **`requireOwner` 는 *마지막 방어선* — 정상 흐름에서는 Facade 가 사전에 본인 소유 검증 (BAD_REQUEST 메시지
 * 일반화) 하므로 도달하지 않지만, 외부 진입점 / 미래 다른 호출자가 가드를 빠뜨려도 도메인이 자기 자신을 지킨다.**
 */
@Entity
@Table(name = "coupon_issues")
class CouponIssueModel internal constructor(
    templateId: Long,
    userId: LoginId,
    issuedAt: LocalDateTime,
) : BaseEntity() {

    @Column(name = "template_id", nullable = false)
    var templateId: Long = templateId
        protected set

    @Embedded
    @AttributeOverride(name = "value", column = Column(name = "user_login_id", nullable = false, length = 20))
    var userId: LoginId = userId
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = STATUS_COLUMN_LENGTH)
    var status: CouponIssueStatus = CouponIssueStatus.AVAILABLE
        protected set

    @Column(name = "issued_at", nullable = false)
    var issuedAt: LocalDateTime = issuedAt
        protected set

    @Column(name = "used_at", nullable = true)
    var usedAt: LocalDateTime? = null
        protected set

    @Column(name = "used_reservation_id", nullable = true)
    var usedReservationId: Long? = null
        protected set

    init {
        if (templateId <= 0L) {
            throw CoreException(ErrorType.BAD_REQUEST, "templateId 는 양수여야 합니다.")
        }
    }

    /**
     * 쿠폰을 사용 처리. 검증 → 변경 순서 (Strong Exception Safety) — 검증 실패 시 *모든 필드는 변경되지 않는다*.
     *
     * 검증:
     * 1. `requireOwner(actor)` — 본인 소유 (FORBIDDEN, 마지막 방어선)
     * 2. `status.canTransitTo(USED)` — 현재 상태가 AVAILABLE 인지 (CONFLICT)
     * 3. `reservationId > 0` — 입력 정합성 (BAD_REQUEST)
     *
     * 변경 (모든 검증 통과 후):
     * - `status = USED`
     * - `usedAt = now`
     * - `usedReservationId = reservationId`
     */
    fun use(actor: LoginId, reservationId: Long, now: LocalDateTime) {
        requireOwner(actor)
        if (!status.canTransitTo(CouponIssueStatus.USED)) {
            throw CoreException(
                ErrorType.CONFLICT,
                "현재 상태($status) 에서 USED 로 전이할 수 없습니다.",
            )
        }
        if (reservationId <= 0L) {
            throw CoreException(ErrorType.BAD_REQUEST, "reservationId 는 양수여야 합니다.")
        }
        // 검증 통과 후 변경 (대입 3개라 throw 없음 — atomic 변경 보장)
        status = CouponIssueStatus.USED
        usedAt = now
        usedReservationId = reservationId
    }

    /**
     * 쿠폰을 만료 처리 — `AVAILABLE → EXPIRED` 전이.
     *
     * 본 라운드는 *조회 시점 lazy 판정* (`CouponFacade.getMyCoupons` 가 현재 시각 비교) + *명시 호출* 의 자리.
     * 일자별 배치 트리거는 6주차+ 합류 시점.
     */
    fun expire() {
        if (!status.canTransitTo(CouponIssueStatus.EXPIRED)) {
            throw CoreException(
                ErrorType.CONFLICT,
                "현재 상태($status) 에서 EXPIRED 로 전이할 수 없습니다.",
            )
        }
        status = CouponIssueStatus.EXPIRED
    }

    private fun requireOwner(actor: LoginId) {
        if (this.userId != actor) {
            throw CoreException(ErrorType.FORBIDDEN, "본인 소유의 쿠폰이 아닙니다.")
        }
    }

    companion object {
        private const val STATUS_COLUMN_LENGTH: Int = 20

        /**
         * 발급 팩토리. 호출자(`CouponFacade.issue`) 는 *영속화된* 템플릿을 넘긴다 (id > 0 보장).
         * 만료된 정책으로의 발급은 본 팩토리에서 가드 — `template.requireUsable(now)` 를 호출자가 책임진다
         * (모델은 templateId 만 받으므로 정책 검증 책임은 Facade 영역).
         */
        fun issue(templateId: Long, userId: LoginId, issuedAt: LocalDateTime): CouponIssueModel =
            CouponIssueModel(
                templateId = templateId,
                userId = userId,
                issuedAt = issuedAt,
            )
    }
}
