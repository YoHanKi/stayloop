package com.stayloop.infrastructure.property.converter

import com.stayloop.domain.property.value.BedConfig
import com.stayloop.domain.property.value.BedType
import com.stayloop.support.error.CoreException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class BedConfigConverterTest {
    private val converter = BedConfigConverter()

    @DisplayName("BedConfig 는 JSON 으로 round-trip 직렬화/역직렬화된다.")
    @Test
    fun shouldRoundTripBedConfig() {
        val original = BedConfig.of(BedType.DOUBLE to 1, BedType.SINGLE to 2)

        val json = converter.convertToDatabaseColumn(original)
        val restored = converter.convertToEntityAttribute(json)

        assertThat(restored).isEqualTo(original)
    }

    @DisplayName("null / 빈 문자열은 INTERNAL_ERROR 로 거절된다 — BedConfig 는 NOT NULL 컬럼.")
    @Test
    fun shouldReject_whenNullOrBlank() {
        assertThatThrownBy { converter.convertToEntityAttribute(null) }
            .isInstanceOf(CoreException::class.java)

        assertThatThrownBy { converter.convertToEntityAttribute("") }
            .isInstanceOf(CoreException::class.java)
    }
}
