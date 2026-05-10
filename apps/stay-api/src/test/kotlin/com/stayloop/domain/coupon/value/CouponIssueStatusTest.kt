package com.stayloop.domain.coupon.value

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class CouponIssueStatusTest {

    @DisplayName("3×3 상태 매트릭스 — 합법 전이 검증.")
    @ParameterizedTest(name = "{0} → {1} = {2}")
    @CsvSource(
        "AVAILABLE, AVAILABLE, false",
        "AVAILABLE, USED,      true",
        "AVAILABLE, EXPIRED,   true",
        "USED,      AVAILABLE, false",
        "USED,      USED,      false",
        "USED,      EXPIRED,   false",
        "EXPIRED,   AVAILABLE, false",
        "EXPIRED,   USED,      false",
        "EXPIRED,   EXPIRED,   false",
    )
    fun matrix(from: CouponIssueStatus, to: CouponIssueStatus, expected: Boolean) {
        assertThat(from.canTransitTo(to)).isEqualTo(expected)
    }
}
