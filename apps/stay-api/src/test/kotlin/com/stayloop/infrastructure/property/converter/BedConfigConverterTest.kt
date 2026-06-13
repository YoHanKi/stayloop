package com.stayloop.infrastructure.property.converter

import com.stayloop.domain.property.value.BedConfig
import com.stayloop.domain.property.value.BedType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class BedConfigConverterTest {
    private val converter = BedConfigConverter()

    @DisplayName("BedConfig 를 JSON 오브젝트로 직렬화하고 동일하게 역직렬화한다.")
    @Test
    fun shouldRoundTrip() {
        val bedConfig = BedConfig(mapOf(BedType.DOUBLE to 1, BedType.SINGLE to 2))

        val json = converter.convertToDatabaseColumn(bedConfig)
        val restored = converter.convertToEntityAttribute(json)

        assertThat(json).contains("DOUBLE", "SINGLE")
        assertThat(restored).isEqualTo(bedConfig)
    }

    @DisplayName("null / 빈 문자열은 빈 BedConfig 로 역직렬화한다.")
    @Test
    fun shouldReturnEmpty_whenNullOrBlank() {
        assertThat(converter.convertToEntityAttribute(null)).isEqualTo(BedConfig.EMPTY)
        assertThat(converter.convertToEntityAttribute(" ")).isEqualTo(BedConfig.EMPTY)
    }
}
