package com.stayloop.domain.property.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Embeddable

/**
 * 숙소 주소. 검색이 [city] 단위로 이뤄지므로 city 를 별도 컬럼으로 둔다.
 */
@Embeddable
data class Address(
    @Column(name = "city", nullable = false, length = 50)
    val city: String,
    @Column(name = "address", nullable = false, length = 255)
    val roadAddress: String,
) {
    init {
        if (city.isBlank()) {
            throw CoreException(ErrorType.BAD_REQUEST, "도시는 비어 있을 수 없습니다.")
        }
        if (roadAddress.isBlank()) {
            throw CoreException(ErrorType.BAD_REQUEST, "주소는 비어 있을 수 없습니다.")
        }
    }
}
