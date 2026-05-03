package com.stayloop.domain.reservation.value

import com.stayloop.domain.user.value.PhoneNumber
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.AttributeOverride
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.Embedded

/**
 * 예약자 정보 박제. (`docs/design/03-class-diagram.md §4`, `docs/plan/week2-3.md §⑦`)
 *
 * **`PhoneNumber` 는 `domain.user.value` 의 VO 를 재사용** — 형식 검증(010-XXXX-XXXX) 이 동일하고,
 * `Money` / `LoginId` 와 같이 도메인 간 공용 VO 패턴. `@AttributeOverride` 로 reservation 의 컬럼명
 * (`guest_phone_number`) 을 명시 — `PhoneNumber.@Column(name = "phone_number")` 의 기본 이름은
 * users 테이블에 결합되어 있으므로 호스트 측에서 override.
 *
 * **`name` 은 별도 String 필드** — `domain.user.value.Name` 을 재사용하지 않는다. 예약자 이름은
 * *예약 시점의 박제* 로, 회원 이름과 다를 수 있다 (대리 예약 등). 동일 검증 규칙을 갖되 의미가 다르므로
 * 자체 가드를 둔다.
 *
 * 도메인 가드:
 * - `name` 비공백, 1~`MAX_NAME_LENGTH` 자
 * - `phoneNumber` 는 `PhoneNumber` VO 가 자체 검증
 */
@Embeddable
data class GuestInfo(
    @Column(name = "guest_name", nullable = false, length = MAX_NAME_LENGTH)
    val name: String,
    @Embedded
    @AttributeOverride(name = "value", column = Column(name = "guest_phone_number", nullable = false, length = 20))
    val phoneNumber: PhoneNumber,
) {
    init {
        if (name.isBlank() || name.length > MAX_NAME_LENGTH) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "예약자 이름은 1~${MAX_NAME_LENGTH}자의 비공백 문자열이어야 합니다.",
            )
        }
    }

    companion object {
        const val MAX_NAME_LENGTH: Int = 50
    }
}
