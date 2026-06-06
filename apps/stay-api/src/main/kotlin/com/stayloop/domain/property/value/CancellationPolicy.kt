package com.stayloop.domain.property.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType

/**
 * 취소 정책. [PropertyPolicy] 안에 중첩되어 JSON 으로 함께 직렬화된다.
 *
 * @property freeUntilDaysBefore [CancellationType.FREE_UNTIL] 일 때만 의미가 있고,
 *   체크인 며칠 전까지 무료 취소인지를 나타낸다.
 */
data class CancellationPolicy(
    val type: CancellationType,
    val freeUntilDaysBefore: Int? = null,
) {
    init {
        when (type) {
            CancellationType.FREE_UNTIL ->
                if (freeUntilDaysBefore == null || freeUntilDaysBefore < 0) {
                    throw CoreException(ErrorType.BAD_REQUEST, "무료 취소 정책은 0 이상의 기준 일수가 필요합니다.")
                }
            CancellationType.NON_REFUNDABLE ->
                if (freeUntilDaysBefore != null) {
                    throw CoreException(ErrorType.BAD_REQUEST, "환불 불가 정책에는 무료 취소 기준 일수를 둘 수 없습니다.")
                }
        }
    }

    companion object {
        fun freeUntil(daysBefore: Int): CancellationPolicy =
            CancellationPolicy(CancellationType.FREE_UNTIL, daysBefore)

        val NON_REFUNDABLE = CancellationPolicy(CancellationType.NON_REFUNDABLE)
    }
}
