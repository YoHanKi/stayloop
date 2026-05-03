package com.stayloop.domain.property.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.Embedded

/**
 * 숙소 주소. (`docs/design/03-class-diagram.md §1`)
 * `coord` 는 후순위 (지오 검색 합류 시 활용 — `05 §5`).
 */
@Embeddable
data class Address(
    @Column(name = "city", nullable = false, length = 20)
    val city: String,
    @Column(name = "address", nullable = false, length = 255)
    val fullAddress: String,
    @Embedded
    val coord: Geo? = null,
) {
    init {
        if (city.isBlank()) {
            throw CoreException(ErrorType.BAD_REQUEST, "도시 코드는 비어 있을 수 없습니다.")
        }
        if (fullAddress.isBlank()) {
            throw CoreException(ErrorType.BAD_REQUEST, "주소는 비어 있을 수 없습니다.")
        }
    }
}
