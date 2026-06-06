package com.stayloop.domain.property.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import java.time.LocalTime

/**
 * 숙소 운영 정책. DB 에는 JSON 으로 직렬화한다
 * (`infrastructure/property/converter/PropertyPolicyConverter`, autoApply).
 *
 * 체크인은 당일 늦은 시각, 체크아웃은 (다음 날) 이른 시각이라 시각만 비교하면
 * `checkInTime > checkOutTime` 이어야 한다(호텔 표준: 체크아웃 11:00, 체크인 15:00).
 * "다음 날" 이라는 의미는 [com.stayloop.domain.reservation.value.StayPeriod] 가 날짜로 표현하므로
 * 여기서는 시각 비교만 한다.
 */
data class PropertyPolicy(
    val checkInTime: LocalTime,
    val checkOutTime: LocalTime,
    val cancellation: CancellationPolicy,
    val smokingAllowed: Boolean = false,
    val petAllowed: Boolean = false,
) {
    init {
        if (!checkInTime.isAfter(checkOutTime)) {
            throw CoreException(ErrorType.BAD_REQUEST, "체크인 시각은 체크아웃 시각보다 늦어야 합니다.")
        }
    }

    companion object {
        /** 호텔 표준(체크인 15:00 / 체크아웃 11:00 / 7일 전 무료 취소). */
        fun standard(): PropertyPolicy =
            PropertyPolicy(
                checkInTime = LocalTime.of(15, 0),
                checkOutTime = LocalTime.of(11, 0),
                cancellation = CancellationPolicy.freeUntil(7),
            )
    }
}
