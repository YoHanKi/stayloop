package com.stayloop.domain.coupon

import com.stayloop.domain.user.value.LoginId
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/**
 * 쿠폰 발급/사용의 도메인 규칙을 소유한다(04-a §6.3). 두 경로의 경합 특성이 정반대다.
 *
 * - 발급(선착순, 핫스팟): 한정 수량을 조건부 원자 증가([CouponTemplateRepository.increaseIssuedIfAvailable])로
 *   다투고, 갱신 행 수가 1 이 아니면 소진(CONFLICT)으로 해석한다. 동일인 중복 발급은 발급 row 의 UNIQUE 가
 *   막고, 그 위반(영속성 예외)의 사용자 응답 변환은 Facade 가 한다.
 * - 사용(본인 소유, 저경합): 조건부 상태 전이([IssuedCouponRepository.markUsedIfAvailable])로 1 회성을 보장하고,
 *   갱신 행 수가 1 이 아니면 이미 사용/불가(CONFLICT)로 해석한다.
 *
 * 영향 행 수의 해석(0=소진/이미 사용)을 쿼리 계층이 아니라 도메인이 한다.
 */
@Service
class CouponService(
    private val couponTemplateRepository: CouponTemplateRepository,
    private val issuedCouponRepository: IssuedCouponRepository,
) {
    /** 선착순 발급. 소진이면 CONFLICT. 발급 시점 할인 정책을 스냅샷한다. */
    fun issue(templateId: Long, userId: LoginId): IssuedCouponModel {
        val template = couponTemplateRepository.findById(templateId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "존재하지 않는 쿠폰입니다.")
        val claimed = couponTemplateRepository.increaseIssuedIfAvailable(templateId)
        if (claimed != 1) {
            throw CoreException(ErrorType.CONFLICT, "쿠폰이 모두 소진되었습니다.")
        }
        return issuedCouponRepository.save(IssuedCouponModel(templateId, userId, template.discount))
    }

    /** 발급 쿠폰 사용(AVAILABLE → USED). 이미 사용했거나 불가하면 CONFLICT. */
    fun use(issuedCouponId: Long, now: LocalDateTime) {
        val used = issuedCouponRepository.markUsedIfAvailable(issuedCouponId, now)
        if (used != 1) {
            throw CoreException(ErrorType.CONFLICT, "이미 사용했거나 사용할 수 없는 쿠폰입니다.")
        }
    }
}
