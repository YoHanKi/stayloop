package com.stayloop.domain.property.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.Embedded

/**
 * 숙소 주소. (`docs/design/03-class-diagram.md §1`)
 * `coord` 는 후순위 (지오 검색 합류 시 활용 — `05 §5`).
 *
 * **컬럼 length ↔ `init` length 가드** 는 동일 상수(`CITY_MAX_LENGTH`, `FULL_ADDRESS_MAX_LENGTH`) 로 묶여
 * DB 제약 위반(500) 대신 BAD_REQUEST 로 거절된다 (verify-code §6, `Name.kt` 와 동일 패턴).
 */
@Embeddable
data class Address(
    @Column(name = "city", nullable = false, length = CITY_MAX_LENGTH)
    val city: String,
    @Column(name = "address", nullable = false, length = FULL_ADDRESS_MAX_LENGTH)
    val fullAddress: String,
    @Embedded
    val coord: Geo? = null,
) {
    init {
        if (city.isBlank()) {
            throw CoreException(ErrorType.BAD_REQUEST, "도시 코드는 비어 있을 수 없습니다.")
        }
        if (city.length > CITY_MAX_LENGTH) {
            throw CoreException(ErrorType.BAD_REQUEST, "도시 코드는 ${CITY_MAX_LENGTH}자 이하여야 합니다.")
        }
        if (fullAddress.isBlank()) {
            throw CoreException(ErrorType.BAD_REQUEST, "주소는 비어 있을 수 없습니다.")
        }
        if (fullAddress.length > FULL_ADDRESS_MAX_LENGTH) {
            throw CoreException(ErrorType.BAD_REQUEST, "주소는 ${FULL_ADDRESS_MAX_LENGTH}자 이하여야 합니다.")
        }
    }

    companion object {
        const val CITY_MAX_LENGTH: Int = 20
        const val FULL_ADDRESS_MAX_LENGTH: Int = 255
    }
}
