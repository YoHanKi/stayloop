package com.stayloop.application.coupon

import com.stayloop.application.coupon.command.CreateCouponTemplateCommand
import com.stayloop.application.coupon.command.IssueCouponCommand
import com.stayloop.domain.coupon.CouponService
import com.stayloop.domain.coupon.CouponTemplateModel
import com.stayloop.domain.coupon.CouponTemplateRepository
import com.stayloop.domain.coupon.IssuedCouponRepository
import com.stayloop.domain.coupon.value.DiscountValue
import com.stayloop.domain.user.value.LoginId
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 쿠폰 발급/조회 유스케이스. 트랜잭션 경계가 곧 유스케이스다. 발급 1회성·소진은 [CouponService] 가 판단하고,
 * Facade 는 동일인 중복 발급의 UNIQUE 위반(영속성 예외)을 사용자 응답(CONFLICT)으로 변환한다.
 * 쿠폰 '사용'의 예약 흐름 합류는 chunk 3(예약 트랜잭션 통합).
 */
@Service
class CouponFacade(
    private val couponTemplateRepository: CouponTemplateRepository,
    private val issuedCouponRepository: IssuedCouponRepository,
    private val couponService: CouponService,
) {
    @Transactional
    fun createTemplate(command: CreateCouponTemplateCommand): CouponTemplateInfo {
        val template = couponTemplateRepository.save(
            CouponTemplateModel(
                name = command.name,
                discount = DiscountValue.of(command.discountType, command.discountValue),
                totalQuantity = command.totalQuantity,
            ),
        )
        return CouponTemplateInfo.from(template)
    }

    @Transactional
    fun issue(command: IssueCouponCommand): IssuedCouponInfo =
        try {
            IssuedCouponInfo.from(couponService.issue(command.templateId, command.loginId))
        } catch (e: DataIntegrityViolationException) {
            // (template, user) UNIQUE 위반 — 동일인 중복 발급. 증가시킨 발급 수는 트랜잭션 롤백으로 되돌아간다.
            throw CoreException(ErrorType.CONFLICT, "이미 발급받은 쿠폰입니다.")
        }

    @Transactional(readOnly = true)
    fun getMyCoupons(loginId: LoginId, page: Int, size: Int): List<IssuedCouponInfo> =
        issuedCouponRepository.findByUser(loginId, page, size).map { IssuedCouponInfo.from(it) }
}
