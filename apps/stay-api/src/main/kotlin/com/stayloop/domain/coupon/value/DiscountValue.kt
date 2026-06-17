package com.stayloop.domain.coupon.value

import com.stayloop.domain.common.value.Money
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import java.math.BigDecimal

/**
 * 할인 정책 값 객체. 방식([DiscountType])과 값([amount]: 정액=원, 정률=%)을 담고, 주문 금액에 대한 할인액을 계산한다.
 * 정액은 주문 금액을 넘지 않도록 캡하고, 정률은 [Money.times] 가 scale 2 로 반올림한다.
 *
 * 생성은 [of] 를 거친다 — 방식별 가드(정액>0, 정률 1~100)를 한곳에 모은다.
 */
@Embeddable
class DiscountValue private constructor(
    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 10)
    val type: DiscountType,
    @Column(name = "discount_value", nullable = false)
    val amount: Int,
) {
    /** [orderAmount] 에 적용할 할인액. */
    fun discount(orderAmount: Money): Money =
        when (type) {
            DiscountType.FIXED -> minOf(Money.of(amount.toLong()), orderAmount)
            DiscountType.PERCENT -> orderAmount.times(BigDecimal(amount).movePointLeft(2))
        }

    companion object {
        fun of(type: DiscountType, value: Int): DiscountValue {
            when (type) {
                DiscountType.FIXED ->
                    if (value <= 0) throw CoreException(ErrorType.BAD_REQUEST, "정액 할인 금액은 0보다 커야 합니다.")
                DiscountType.PERCENT ->
                    if (value !in 1..100) throw CoreException(ErrorType.BAD_REQUEST, "정률 할인율은 1~100 사이여야 합니다.")
            }
            return DiscountValue(type, value)
        }
    }
}
