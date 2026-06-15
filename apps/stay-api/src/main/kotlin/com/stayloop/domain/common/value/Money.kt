package com.stayloop.domain.common.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 금액 값 객체. 단일 통화(KRW) 전제라 통화 필드를 두지 않는다(03 §6).
 *
 * 내부 표현은 [BigDecimal] 이고 항상 scale [SCALE] 로 정규화해 보관한다.
 * 정규화 덕분에 `1000` 과 `1000.00` 은 같은 금액으로 취급된다(BigDecimal 의 scale 민감
 * equals 함정 회피). DB 는 `DECIMAL(19,2)` 로 매핑한다.
 *
 * 생성은 항상 [of] 를 거친다. 음수 거부와 scale 정규화를 한곳에 모으기 위해 주 생성자는 private 이다.
 */
@Embeddable
class Money private constructor(
    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    val amount: BigDecimal,
) : Comparable<Money> {

    operator fun plus(other: Money): Money = Money(amount + other.amount)

    /** 차감. 결과가 음수면 [of] 가 거절한다(예: 할인이 원금 초과). */
    operator fun minus(other: Money): Money = of(amount - other.amount)

    /** 할인율·세율 등 소수 곱셈 자리. 결과는 [SCALE] 로 반올림한다(쿠폰·세금은 6주차, 03 §7). */
    operator fun times(multiplier: BigDecimal): Money =
        Money(amount.multiply(multiplier).setScale(SCALE, RoundingMode.HALF_UP))

    fun isZero(): Boolean = amount.signum() == 0

    override fun compareTo(other: Money): Int = amount.compareTo(other.amount)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Money) return false
        return amount.compareTo(other.amount) == 0
    }

    override fun hashCode(): Int = amount.stripTrailingZeros().hashCode()

    override fun toString(): String = amount.toPlainString()

    companion object {
        private const val SCALE = 2

        val ZERO: Money = Money(BigDecimal.ZERO.setScale(SCALE))

        fun of(amount: BigDecimal): Money {
            if (amount.signum() < 0) {
                throw CoreException(ErrorType.BAD_REQUEST, "금액은 음수일 수 없습니다.")
            }
            return Money(amount.setScale(SCALE, RoundingMode.HALF_UP))
        }

        fun of(amount: Long): Money = of(BigDecimal.valueOf(amount))
    }
}
