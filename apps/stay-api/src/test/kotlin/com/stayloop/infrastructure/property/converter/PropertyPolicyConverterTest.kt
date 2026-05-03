package com.stayloop.infrastructure.property.converter

import com.stayloop.domain.property.value.CancellationPolicy
import com.stayloop.domain.property.value.CancellationType
import com.stayloop.domain.property.value.PropertyPolicy
import org.assertj.core.api.Assertions.assertThat
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

    @DisplayName("null 은 그대로 null 로 처리한다.")
    @Test
    fun shouldHandleNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull()
        assertThat(converter.convertToEntityAttribute(null)).isNull()
    }
}
