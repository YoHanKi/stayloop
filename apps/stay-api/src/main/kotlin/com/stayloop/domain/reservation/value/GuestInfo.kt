package com.stayloop.domain.reservation.value

import com.stayloop.domain.user.value.PhoneNumber
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.AttributeOverride
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Embeddable

/**
 * 예약자 정보 박제. 회원 정보가 바뀌어도 예약 당시의 연락처가 보존되도록 값을 복제해 든다.
 * [PhoneNumber] 는 회원 도메인 VO 를 재사용하되 컬럼명만 `@AttributeOverride` 로 맞춘다(03 §5).
 */
@Embeddable
data class GuestInfo(
    @Column(name = "guest_name", nullable = false, length = 50)
    val name: String,
    @Embedded
    @AttributeOverride(name = "value", column = Column(name = "guest_phone_number", nullable = false, length = 20))
    val phoneNumber: PhoneNumber,
) {
    init {
        if (name.isBlank()) {
            throw CoreException(ErrorType.BAD_REQUEST, "예약자 이름은 비어 있을 수 없습니다.")
        }
    }
}
