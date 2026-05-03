package com.stayloop.infrastructure.property.converter

import com.stayloop.domain.property.value.CancellationPolicy
import com.stayloop.domain.property.value.CancellationType
import com.stayloop.domain.property.value.PropertyPolicy
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalTime

class PropertyPolicyConverterTest {
    private val converter = PropertyPolicyConverter()

    @DisplayName("PropertyPolicy 는 JSON 으로 round-trip 직렬화/역직렬화된다.")
    @Test
    fun shouldRoundTripPolicy() {
        val original = PropertyPolicy(
            checkInTime = LocalTime.of(15, 0),
            checkOutTime = LocalTime.of(11, 0),
            cancellation = CancellationPolicy(CancellationType.FREE_UNTIL, freeUntilDaysBefore = 3),
            smokingAllowed = false,
            petAllowed = true,
        )

        val json = converter.convertToDatabaseColumn(original)
        val restored = converter.convertToEntityAttribute(json)

        assertThat(restored).isEqualTo(original)
    }

    @DisplayName("attribute 가 null 이면 INTERNAL_ERROR 로 거절된다 — NOT NULL 컬럼 일관성.")
    @Test
    fun shouldReject_whenAttributeIsNull() {
        assertThatThrownBy { converter.convertToDatabaseColumn(null) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR)
    }

    @DisplayName("dbData 가 null/blank 이면 INTERNAL_ERROR 로 거절된다.")
    @Test
    fun shouldReject_whenDbDataIsNullOrBlank() {
        assertThatThrownBy { converter.convertToEntityAttribute(null) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR)

        assertThatThrownBy { converter.convertToEntityAttribute("") }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR)
    }
}
