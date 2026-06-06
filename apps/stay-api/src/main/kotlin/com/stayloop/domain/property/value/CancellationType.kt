package com.stayloop.domain.property.value

enum class CancellationType {
    /** 체크인 N 일 전까지 무료 취소. */
    FREE_UNTIL,

    /** 환불 불가. */
    NON_REFUNDABLE,
}
