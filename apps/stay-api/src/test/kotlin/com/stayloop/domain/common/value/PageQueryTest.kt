package com.stayloop.domain.common.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class PageQueryTest {
    @DisplayName("page 가 음수이면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenPageIsNegative() {
        assertThatThrownBy { PageQuery(page = -1, size = 10) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("size 가 0 이하이면 BAD_REQUEST 로 거절된다.")
    @ParameterizedTest
    @ValueSource(ints = [0, -1, -100])
    fun shouldReject_whenSizeIsZeroOrNegative(size: Int) {
        assertThatThrownBy { PageQuery(page = 0, size = size) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("offset 은 page * size — 항상 size 의 배수.")
    @Test
    fun shouldComputeOffsetAsPageTimesSize() {
        assertThat(PageQuery(page = 0, size = 10).offset).isEqualTo(0)
        assertThat(PageQuery(page = 1, size = 10).offset).isEqualTo(10)
        assertThat(PageQuery(page = 3, size = 7).offset).isEqualTo(21)
    }

    @DisplayName("of() 정적 팩토리는 음수 page 를 0 으로 정규화한다.")
    @Test
    fun shouldCoerceNegativePageInOf() {
        assertThat(PageQuery.of(page = -5, size = 10).page).isEqualTo(0)
    }

    @DisplayName("size 가 MAX_PAGE_SIZE 를 초과하면 BAD_REQUEST 로 거절된다 — 무제한 LIMIT 차단.")
    @Test
    fun shouldReject_whenSizeExceedsMax() {
        assertThatThrownBy { PageQuery(page = 0, size = PageQuery.MAX_PAGE_SIZE + 1) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("of() 정적 팩토리는 size 도 [1, MAX_PAGE_SIZE] 범위로 클램프한다.")
    @Test
    fun shouldClampSizeInOf() {
        assertThat(PageQuery.of(page = 0, size = 0).size).isEqualTo(1)
        assertThat(PageQuery.of(page = 0, size = PageQuery.MAX_PAGE_SIZE + 999).size)
            .isEqualTo(PageQuery.MAX_PAGE_SIZE)
    }
}
