package com.stayloop.domain.property.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Embeddable

@Embeddable
data class Geo(
    // Hibernate 6.x 는 DOUBLE 컬럼에 `scale` 속성을 거절 (`scale has no meaning for SQL floating point types`).
    // DOUBLE 자체로는 소수 자릿수가 부정확하므로 DB 차원에서 DECIMAL(9,6) 으로 고정해 둔다 — Hibernate 의
    // 자동 dialect 매핑이 거절하지 않도록 columnDefinition 으로 명시.
    @Column(name = "latitude", columnDefinition = "DECIMAL(9,6)")
    val latitude: Double,
    @Column(name = "longitude", columnDefinition = "DECIMAL(9,6)")
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
