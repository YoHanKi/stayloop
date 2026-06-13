package com.stayloop.domain.property.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType

/**
 * 객실 침대 구성. DB 에는 JSON 오브젝트(`{"DOUBLE":1}`)로 직렬화한다
 * (`infrastructure/property/converter/BedConfigConverter`, autoApply).
 */
data class BedConfig(
    val beds: Map<BedType, Int> = emptyMap(),
) {
    init {
        if (beds.any { it.value <= 0 }) {
            throw CoreException(ErrorType.BAD_REQUEST, "침대 수는 1 이상이어야 합니다.")
        }
    }

    fun totalBeds(): Int = beds.values.sum()

    companion object {
        val EMPTY = BedConfig(emptyMap())
    }
}
