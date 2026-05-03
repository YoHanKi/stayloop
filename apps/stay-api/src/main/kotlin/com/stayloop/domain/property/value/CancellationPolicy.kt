package com.stayloop.domain.property.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType

/**
 * 취소 정책. 본 라운드는 데이터 보유까지 — 정책 적용(위약금/환불 산출)은 결제 합류 후 (`05 §2.6 / §8.2`).
 */
data class CancellationPolicy(
    val type: CancellationType,
    val freeUntilDaysBefore: Int = 0,
) {
    init {
        if (freeUntilDaysBefore < 0) {
            throw CoreException(ErrorType.BAD_REQUEST, "freeUntilDaysBefore 는 0 이상이어야 합니다.")
        }
        if (type == CancellationType.NON_REFUNDABLE && freeUntilDaysBefore != 0) {
            throw CoreException(ErrorType.BAD_REQUEST, "환불 불가 정책은 freeUntilDaysBefore 가 0 이어야 합니다.")
        }
    }
}

enum class CancellationType {
    FREE_UNTIL,
    NON_REFUNDABLE,
    PARTIAL_REFUND,
}
