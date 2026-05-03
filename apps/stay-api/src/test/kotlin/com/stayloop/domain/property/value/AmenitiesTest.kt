package com.stayloop.domain.property.value

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class AmenitiesTest {
    @DisplayName("EMPTY 는 빈 태그 집합을 의미한다.")
    @Test
    fun shouldExposeEmpty() {
        assertThat(Amenities.EMPTY.tags).isEmpty()
    }

    @DisplayName("of() 정적 팩토리는 가변 인자를 Set 으로 만든다.")
    @Test
    fun shouldCreateViaOf() {
        val amenities = Amenities.of(AmenityTag.WIFI, AmenityTag.PARKING, AmenityTag.WIFI)

        assertThat(amenities.tags).hasSize(2)
        assertThat(amenities.has(AmenityTag.WIFI)).isTrue()
        assertThat(amenities.has(AmenityTag.PARKING)).isTrue()
        assertThat(amenities.has(AmenityTag.POOL)).isFalse()
    }
}
