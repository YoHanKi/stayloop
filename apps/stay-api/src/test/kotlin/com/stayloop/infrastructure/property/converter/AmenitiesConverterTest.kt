package com.stayloop.infrastructure.property.converter

import com.stayloop.domain.property.value.AmenityTag
import com.stayloop.domain.property.value.Amenities
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class AmenitiesConverterTest {
    private val converter = AmenitiesConverter()

    @DisplayName("Amenities 를 JSON 배열로 직렬화하고 동일하게 역직렬화한다.")
    @Test
    fun shouldRoundTrip() {
        val amenities = Amenities(setOf(AmenityTag.WIFI, AmenityTag.PARKING))

        val json = converter.convertToDatabaseColumn(amenities)
        val restored = converter.convertToEntityAttribute(json)

        assertThat(json).contains("WIFI", "PARKING")
        assertThat(restored).isEqualTo(amenities)
    }

    @DisplayName("null / 빈 문자열은 빈 Amenities 로 역직렬화한다.")
    @Test
    fun shouldReturnEmpty_whenNullOrBlank() {
        assertThat(converter.convertToEntityAttribute(null)).isEqualTo(Amenities.EMPTY)
        assertThat(converter.convertToEntityAttribute("")).isEqualTo(Amenities.EMPTY)
    }
}
