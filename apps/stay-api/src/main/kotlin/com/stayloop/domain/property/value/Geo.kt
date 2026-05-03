package com.stayloop.domain.property.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Embeddable

@Embeddable
data class Geo(
    @Column(name = "latitude", precision = 9, scale = 6)
    val latitude: Double,
    @Column(name = "longitude", precision = 9, scale = 6)
    val longitude: Double,
) {
    init {
        if (latitude !in -90.0..90.0) {
            throw CoreException(ErrorType.BAD_REQUEST, "위도는 -90 ~ 90 범위여야 합니다.")
        }
        if (longitude !in -180.0..180.0) {
            throw CoreException(ErrorType.BAD_REQUEST, "경도는 -180 ~ 180 범위여야 합니다.")
        }
    }
}
