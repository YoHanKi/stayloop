package com.stayloop.domain.coupon

import com.stayloop.domain.BaseEntity
import com.stayloop.domain.coupon.value.CouponName
import com.stayloop.domain.coupon.value.DiscountValue
import com.stayloop.domain.coupon.value.ExpirationPeriod
import com.stayloop.domain.coupon.value.MinOrderAmount
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDateTime

/**
 * 쿠폰 템플릿 (정책) Aggregate Root. (`docs/plan/week4.md` ① Phase A-3, `docs/plan/week4/decision.md` D-2)
 *
 * 어드민이 등록 / 수정 / 삭제하는 *정책* 자체를 표현. 사용자가 발급받는 *1회용 인스턴스* 는 별 Aggregate
 * (`CouponIssue`) 로 분리 — 생명주기 / 소유권 / 집계 단위가 다르고, 동시성 자원도 인스턴스 측에만 발생한다.
 *
 * 박제 컬럼:
 * - `code` UNIQUE — 쿠폰 코드 (어드민이 등록 시 부여; 사용자가 직접 입력해 발급받는 흐름은 6주차+ 영역)
 * - `name` — `CouponName`
 * - `discountValue` — `DiscountValue` (FIXED 정액 또는 RATE 정률)
 * - `minOrderAmount` — `MinOrderAmount?` (null = 제한 없음)
 * - `expirationPeriod` — `ExpirationPeriod` (글로벌 캠페인 마감 시각)
 *
 * 도메인 가드 (생성 시):
 * - `code` 비공백 + 1~`MAX_CODE_LENGTH(50)` 자
 * - 다른 가드는 각 VO 가 책임 (위임)
 *
 * 도메인 행동:
 * - [isExpired] / [requireUsable] — `ExpirationPeriod` 에 위임
 * - 정책 적용 (정액/정률 계산, 최소 결제 금액 검증) 은 본 모델에 두지 않고 `CouponIssueService` 가 조립한다
 *   (A-6 박제) — 도메인 서비스 = 순수 함수 + Aggregate 객체 = 자기 상태 가드.
 */
@Entity
@Table(
    name = "coupon_templates",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_coupon_templates_code", columnNames = ["code"]),
    ],
)
class CouponTemplateModel internal constructor(
    code: String,
    name: CouponName,
    discountValue: DiscountValue,
    expirationPeriod: ExpirationPeriod,
    minOrderAmount: MinOrderAmount? = null,
) : BaseEntity() {

    @Column(name = "code", nullable = false, length = MAX_CODE_LENGTH)
    var code: String = code
        protected set

    @Embedded
    var name: CouponName = name
        protected set

    @Embedded
    var discountValue: DiscountValue = discountValue
        protected set

    @Embedded
    var minOrderAmount: MinOrderAmount? = minOrderAmount
        protected set

    @Embedded
    var expirationPeriod: ExpirationPeriod = expirationPeriod
        protected set

    init {
        if (code.isBlank() || code.length > MAX_CODE_LENGTH) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "쿠폰 코드는 1~${MAX_CODE_LENGTH}자의 비공백 문자열이어야 합니다.",
            )
        }
    }

    fun isExpired(now: LocalDateTime): Boolean = expirationPeriod.isExpired(now)

    fun requireUsable(now: LocalDateTime) {
        expirationPeriod.requireUsable(now)
    }

    companion object {
        const val MAX_CODE_LENGTH: Int = 50

        fun create(
            code: String,
            name: CouponName,
            discountValue: DiscountValue,
            expirationPeriod: ExpirationPeriod,
            minOrderAmount: MinOrderAmount? = null,
        ): CouponTemplateModel = CouponTemplateModel(
            code = code,
            name = name,
            discountValue = discountValue,
            expirationPeriod = expirationPeriod,
            minOrderAmount = minOrderAmount,
        )
    }
}
