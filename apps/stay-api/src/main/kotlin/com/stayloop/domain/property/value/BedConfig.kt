package com.stayloop.domain.property.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType

/**
 * 침대 구성. JSON 컬럼으로 영속화. (`docs/design/04-erd.md §1`)
 * 매핑 컨버터는 `infrastructure/property/converter/BedConfigConverter`.
 */
data class BedConfig(
    val beds: Map<BedType, Int>,
) {
    init {
        if (beds.isEmpty()) {
            throw CoreException(ErrorType.BAD_REQUEST, "침대 구성은 비어 있을 수 없습니다.")
        }
        beds.values.forEach { count ->
            if (count <= 0) {
                throw CoreException(ErrorType.BAD_REQUEST, "각 침대 종류 별 수량은 1 이상이어야 합니다.")
            }
        }
    }

    fun totalBeds(): Int = beds.values.sum()

    companion object {
        fun of(vararg pairs: Pair<BedType, Int>): BedConfig = BedConfig(pairs.toMap())
    }
}

enum class BedType {
    SINGLE,
    DOUBLE,
    QUEEN,
    KING,
    SOFA_BED,
    BUNK,
}
