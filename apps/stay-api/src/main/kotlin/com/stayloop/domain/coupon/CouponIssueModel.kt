package com.stayloop.domain.coupon

import com.stayloop.domain.BaseEntity
import com.stayloop.domain.coupon.value.CouponIssueStatus
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import java.time.LocalDateTime

/**
 * 발급된 쿠폰 인스턴스 Aggregate Root. (`docs/plan/week4.md` ① Phase A-4 / A-5, `docs/plan/week4/decision.md` D-2)
 *
 * 사용자가 *발급받아 1회 사용하는 쿠폰*. `CouponTemplate` (정책) 과 분리된 별 Aggregate — 생명주기 / 소유권 /
 * 동시성 자원이 다르다.
 *
 * **`userId` 는 `users.id` (BIGINT FK)** — `LoginId` 직접 임베드가 아니다 (`Wishlist` 패턴 답습;
 * `Reservation` 의 LoginId 박제와 다른 결정). 인덱스 효율 (BIGINT 8 byte vs VARCHAR 가변) + 사용자별 통계 /
 * 페이지네이션이 빈번한 *집계 자원* 의 자연 결과. boundary 는 여전히 `LoginId` 이며,
 * `LoginId ↔ users.id` 변환은 `CouponIssueRepositoryImpl` (그리고 InMemory 더블) 의 책임.
 *
 * 박제 컬럼:
 * - `templateId` (FK) — `CouponTemplate.id` 참조 (소프트 FK, JPA `@ManyToOne` 미사용 — Aggregate 경계)
 * - `userId` (FK BIGINT) — `users.id`
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
 *
 * **동시성 — `@Version` 낙관적 락 + DB UNIQUE 다층 가드** (`docs/plan/week4.md` ③ Phase B, decision.md D-1 #2)
 * - **`@Version` (`Long`)** — 동일 row 의 동시 UPDATE 를 잡는다. 같은 사용자가 다중 기기에서 같은 쿠폰을 동시
 *   사용 시도하면 *한 commit 만 성공*, 나머지는 `OptimisticLockingFailureException` 으로 거절. 처리량이 핵심
 *   인 자원이 아니므로 retry 비용보다 *충돌 즉시 사용자 안내* 가 자연스럽다 (CONFLICT 응답).
 * - **DB UNIQUE (`used_reservation_id`)** — 서로 다른 reservation 에 같은 쿠폰을 동시 사용 시도를 잡는다.
 *   `@Version` 만으로 충분한 시나리오지만, 애플리케이션 우회 (관리 도구 / 마이그레이션 / 미래의 다른 진입점)
 *   에도 살아남는 *DB 레벨 가드* — defense in depth.
 * - **`Long` 채택 근거** — 단조 증가 + 운영 디버깅 용이성 (Phase 0 E-3 / Round 1 E-3-r1 박제). Timestamp 는
 *   동일 ms 안 false negative 위험 + 분산 노드 시계 불일치 위험.
 */
@Entity
@Table(
    name = "coupon_issues",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_coupon_issues_used_reservation_id",
            columnNames = ["used_reservation_id"],
        ),
    ],
)
class CouponIssueModel internal constructor(
    templateId: Long,
    userId: Long,
    issuedAt: LocalDateTime,
) : BaseEntity() {

    @Column(name = "template_id", nullable = false)
    var templateId: Long = templateId
        protected set

    @Column(name = "user_id", nullable = false)
    var userId: Long = userId
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

    /**
     * 낙관적 락 버전. Hibernate `@Version` 의미론 — *INSERT 시 0 유지*, *각 UPDATE 시 1 증가*. JPA 가 UPDATE 시
     * `WHERE version = ?` 자동 부착 — 동일 row 의 동시 UPDATE 시 stale version 을 만난 두 번째 commit 은
     * `OptimisticLockingFailureException` 으로 실패. `Long` 단조 증가 — false negative (충돌 미감지) 0 보장
     * (Timestamp 와의 비교: Phase 0 E-3 박제).
     */
    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
        protected set

    init {
        if (templateId <= 0L) {
            throw CoreException(ErrorType.BAD_REQUEST, "templateId 는 양수여야 합니다.")
        }
        if (userId <= 0L) {
            throw CoreException(ErrorType.BAD_REQUEST, "userId 는 양수여야 합니다 (영속화된 User 의 id).")
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
    fun use(actor: Long, reservationId: Long, now: LocalDateTime) {
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

    private fun requireOwner(actor: Long) {
        if (this.userId != actor) {
            throw CoreException(ErrorType.FORBIDDEN, "본인 소유의 쿠폰이 아닙니다.")
        }
    }

    companion object {
        private const val STATUS_COLUMN_LENGTH: Int = 20

        /**
         * 발급 팩토리. 호출자(`CouponFacade.issue`) 는 *영속화된* 템플릿의 id 를 넘긴다 (id > 0 보장).
         * 만료된 정책으로의 발급은 본 팩토리에서 가드 — `template.requireUsable(now)` 를 호출자가 책임진다
         * (모델은 templateId 만 받으므로 정책 검증 책임은 Facade 영역).
         */
        fun issue(templateId: Long, userId: Long, issuedAt: LocalDateTime): CouponIssueModel =
            CouponIssueModel(
                templateId = templateId,
                userId = userId,
                issuedAt = issuedAt,
            )
    }
}
