package com.stayloop.infrastructure.property.converter

import com.stayloop.domain.property.value.Amenities
import com.stayloop.domain.property.value.AmenityTag
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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

    @DisplayName("attribute 가 null 이면 INTERNAL_ERROR 로 거절된다 — 다른 컨버터와 정책 일관.")
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

    @DisplayName("역직렬화 실패 시 cause 가 보존된다.")
    @Test
    fun shouldPreserveCauseOnDeserializationFailure() {
        assertThatThrownBy { converter.convertToEntityAttribute("not-a-json") }
            .isInstanceOf(CoreException::class.java)
            .hasCauseInstanceOf(Exception::class.java)
    }
}
