package com.stayloop.infrastructure.property.converter

import com.stayloop.domain.property.value.PropertyPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class PropertyPolicyConverterTest {
    private val converter = PropertyPolicyConverter()

    @DisplayName("PropertyPolicy 를 JSON 으로 직렬화하고 LocalTime·중첩 정책까지 동일하게 역직렬화한다.")
    @Test
    fun shouldRoundTrip() {
        val policy = PropertyPolicy.standard()

        val json = converter.convertToDatabaseColumn(policy)
        val restored = converter.convertToEntityAttribute(json)

        assertThat(json).contains("15:00:00", "11:00:00", "FREE_UNTIL")
        assertThat(restored).isEqualTo(policy)
    }

    @DisplayName("null / 빈 문자열은 null 로 역직렬화한다.")
    @Test
    fun shouldReturnNull_whenNullOrBlank() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull()
        assertThat(converter.convertToEntityAttribute(null)).isNull()
        assertThat(converter.convertToEntityAttribute("")).isNull()
    }
}
