package com.stayloop.support.concurrency

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class HotKeyGuardTest {
    @DisplayName("같은 키의 동시 실행이 한도를 넘으면 TOO_MANY_REQUESTS 로 거절된다.")
    @Test
    fun rejectsWhenSaturated() {
        val guard = HotKeyGuard(maxConcurrentPerKey = 1)

        assertThatThrownBy {
            guard.withPermit("k") {
                guard.withPermit("k") { } // 유일한 permit 점유 중 → 거절
            }
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.TOO_MANY_REQUESTS)
    }

    @DisplayName("키가 다르면 서로 독립적으로 permit 을 가진다.")
    @Test
    fun differentKeysIndependent() {
        val guard = HotKeyGuard(maxConcurrentPerKey = 1)

        assertThatCode {
            guard.withPermit("a") { guard.withPermit("b") { } }
        }.doesNotThrowAnyException()
    }

    @DisplayName("작업이 끝나면 permit 이 반환돼 다시 획득할 수 있다.")
    @Test
    fun releasesPermitAfterAction() {
        val guard = HotKeyGuard(maxConcurrentPerKey = 1)

        guard.withPermit("k") { }

        assertThatCode { guard.withPermit("k") { } }.doesNotThrowAnyException()
    }
}
