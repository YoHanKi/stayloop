package com.stayloop.infrastructure.property.converter

import com.stayloop.domain.property.value.Amenities
import com.stayloop.domain.property.value.AmenityTag
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class AmenitiesConverterTest {
    private val converter = AmenitiesConverter()

    @DisplayName("Amenities 는 JSON 배열로 round-trip 직렬화/역직렬화된다.")
    @Test
    fun shouldRoundTripAmenities() {
        val original = Amenities.of(AmenityTag.WIFI, AmenityTag.PARKING, AmenityTag.POOL)

        val json = converter.convertToDatabaseColumn(original)
        val restored = converter.convertToEntityAttribute(json)

        assertThat(restored.tags).containsExactlyInAnyOrderElementsOf(original.tags)
    }

    @DisplayName("null / 빈 문자열은 EMPTY 로 역직렬화된다.")
    @Test
    fun shouldReturnEmptyForNullOrBlank() {
        assertThat(converter.convertToEntityAttribute(null)).isEqualTo(Amenities.EMPTY)
        assertThat(converter.convertToEntityAttribute("")).isEqualTo(Amenities.EMPTY)
    }
}
