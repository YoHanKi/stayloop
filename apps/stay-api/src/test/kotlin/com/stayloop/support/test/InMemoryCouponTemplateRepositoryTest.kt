package com.stayloop.support.test

import com.stayloop.domain.coupon.CouponTemplateModel
import com.stayloop.domain.coupon.value.CouponName
import com.stayloop.domain.coupon.value.DiscountType
import com.stayloop.domain.coupon.value.DiscountValue
import com.stayloop.domain.coupon.value.ExpirationPeriod
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class InMemoryCouponTemplateRepositoryTest {

    private lateinit var repository: InMemoryCouponTemplateRepository

    @BeforeEach
    fun setUp() {
        repository = InMemoryCouponTemplateRepository()
    }

    private fun template(code: String) =
        CouponTemplateModel.create(
            code = code,
            name = CouponName(value = "신규가입 1만원 할인"),
            discountValue = DiscountValue(type = DiscountType.FIXED, rawValue = 10_000L),
            expirationPeriod = ExpirationPeriod(expiredAt = LocalDateTime.of(2026, 6, 30, 23, 59, 59)),
        )

    @DisplayName("save 시 신규 엔티티에 순차 id 가 할당되어 findById 로 재조회된다.")
    @Test
    fun shouldAssignIdAndPersist() {
        val saved = repository.save(template(code = "WELCOME10000"))

        assertThat(saved.id).isPositive()
        assertThat(repository.findById(saved.id)).isEqualTo(saved)
    }

    @DisplayName("findByCode 는 code 로 단건 조회한다 — 없으면 null.")
    @Test
    fun shouldFindByCode() {
        val saved = repository.save(template(code = "WELCOME10000"))

        assertThat(repository.findByCode("WELCOME10000")).isEqualTo(saved)
        assertThat(repository.findByCode("UNKNOWN")).isNull()
    }
}
