package com.stayloop.domain.reservation.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class PropertySnapshotTest {

    @DisplayName("정상 생성 시 박제된 propertyId / propertyName / propertyAddress / propertyPolicy 가 그대로 노출된다.")
    @Test
    fun shouldExposeFields() {
        val snapshot = PropertySnapshot(
            propertyId = 7L,
            propertyName = "Stayloop 호텔 강남점",
            propertyAddress = "서울특별시 강남구 테헤란로 1",
            propertyPolicy = "체크인 15시 / 체크아웃 11시 / 환불 D-7 50%",
        )

        assertThat(snapshot.propertyId).isEqualTo(7L)
        assertThat(snapshot.propertyName).isEqualTo("Stayloop 호텔 강남점")
        assertThat(snapshot.propertyAddress).isEqualTo("서울특별시 강남구 테헤란로 1")
        assertThat(snapshot.propertyPolicy).isEqualTo("체크인 15시 / 체크아웃 11시 / 환불 D-7 50%")
    }

    @DisplayName("propertyPolicy 는 null 허용 — 정책 미설정 Property 의 박제 시 정상 통과.")
    @Test
    fun shouldAllowNullPolicy() {
        val snapshot = PropertySnapshot(
            propertyId = 7L,
            propertyName = "이름",
            propertyAddress = "주소",
            propertyPolicy = null,
        )

        assertThat(snapshot.propertyPolicy).isNull()
    }

    @DisplayName("propertyId 가 0 이하면 BAD_REQUEST 로 거절된다.")
    @ParameterizedTest
    @ValueSource(longs = [0L, -1L, -100L])
    fun shouldReject_whenPropertyIdIsZeroOrNegative(propertyId: Long) {
        assertThatThrownBy {
            PropertySnapshot(propertyId = propertyId, propertyName = "이름", propertyAddress = "주소")
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("propertyName 이 빈 문자열이거나 MAX_NAME_LENGTH(100) 초과면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenPropertyNameViolatesGuard() {
        // 빈 문자열
        assertThatThrownBy {
            PropertySnapshot(propertyId = 7L, propertyName = "", propertyAddress = "주소")
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)

        // MAX +1 초과
        val tooLong = "가".repeat(PropertySnapshot.MAX_NAME_LENGTH + 1)
        assertThatThrownBy {
            PropertySnapshot(propertyId = 7L, propertyName = tooLong, propertyAddress = "주소")
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("propertyAddress 가 빈 문자열이거나 MAX_ADDRESS_LENGTH(255) 초과면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenPropertyAddressViolatesGuard() {
        // 빈 문자열
        assertThatThrownBy {
            PropertySnapshot(propertyId = 7L, propertyName = "이름", propertyAddress = "")
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)

        // MAX +1 초과
        val tooLong = "가".repeat(PropertySnapshot.MAX_ADDRESS_LENGTH + 1)
        assertThatThrownBy {
            PropertySnapshot(propertyId = 7L, propertyName = "이름", propertyAddress = tooLong)
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("propertyPolicy 가 비공백 문자열로 명시됐는데 MAX_POLICY_LENGTH(1000) 초과면 BAD_REQUEST 로 거절된다 — null 은 허용, 빈 문자열은 거절.")
    @Test
    fun shouldReject_whenPropertyPolicyIsBlankOrTooLong() {
        // 빈 문자열 (null 이 아닌 빈 — 명시한 의도 없음)
        assertThatThrownBy {
            PropertySnapshot(
                propertyId = 7L,
                propertyName = "이름",
                propertyAddress = "주소",
                propertyPolicy = "",
            )
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)

        // MAX +1 초과
        val tooLong = "정".repeat(PropertySnapshot.MAX_POLICY_LENGTH + 1)
        assertThatThrownBy {
            PropertySnapshot(
                propertyId = 7L,
                propertyName = "이름",
                propertyAddress = "주소",
                propertyPolicy = tooLong,
            )
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }
}
