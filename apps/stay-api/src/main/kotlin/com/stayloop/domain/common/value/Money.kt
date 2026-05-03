package com.stayloop.domain.common.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Embeddable

/**
 * 본 라운드는 KRW 원 단위 정수만 다룬다. (`04-erd.md §0`)
 * `currency` 필드는 의식적으로 두지 않는다. (`05-domain-landscape.md §8.2` — 다국적은 8주차+)
 */
@Embeddable
data class Money(
    @Column(nullable = false)
    val amount: Long,
) {
    init {
        if (amount < 0L) {
            throw CoreException(ErrorType.BAD_REQUEST, "금액은 음수가 될 수 없습니다.")
        }
    }

    operator fun plus(other: Money): Money = Money(amount + other.amount)

    operator fun times(multiplier: Int): Money {
        if (multiplier < 0) {
            throw CoreException(ErrorType.BAD_REQUEST, "배수는 음수가 될 수 없습니다.")
        }
        return Money(amount * multiplier)
    }

    fun isZero(): Boolean = amount == 0L

    override fun toString(): String = "$amount KRW"

    companion object {
        val ZERO: Money = Money(0L)

        fun of(amount: Long): Money = Money(amount)
    }
}
