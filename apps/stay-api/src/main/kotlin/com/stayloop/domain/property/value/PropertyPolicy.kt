package com.stayloop.domain.property.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import java.time.LocalTime

/**
 * 숙소 정책. (`docs/design/03-class-diagram.md §1`)
 * 본 라운드는 보유·노출·예약 시 박제까지. **정책 적용 메서드는 추가하지 말 것** (`05 §8.2`).
 * JSON 컬럼으로 영속화 — 매핑 컨버터는 `infrastructure/property/converter/PropertyPolicyConverter`.
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
            // 호텔 표준: 체크아웃(오전) < 체크인(오후). 다음날 체크인 시간이 더 늦어야 의미 있음.
            // 예: 체크인 15:00, 체크아웃 11:00 — 시각만 비교하면 in > out.
            throw CoreException(ErrorType.BAD_REQUEST, "체크인 시각은 체크아웃 시각보다 늦어야 합니다.")
        }
    }
}
