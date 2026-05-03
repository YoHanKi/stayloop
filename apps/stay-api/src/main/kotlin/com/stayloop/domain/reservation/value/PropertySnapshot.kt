package com.stayloop.domain.reservation.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Embeddable

/**
 * 예약 시점의 Property 박제. (`docs/design/03-class-diagram.md §4`, `docs/plan/week2-3.md §⑦`)
 *
 * **박제 의도** — 예약 후 Property 가 변경(이름/주소/정책 수정 등) 되어도, *예약 당시의 정보* 가 영수증·
 * 안내·취소·재발행 흐름에서 그대로 보존되어야 한다. Property entity 의 lazy reference 가 아니라 **불변 VO 박제**
 * 형태로 reservation row 에 함께 저장한다.
 *
 * 박제 항목 (단순 primitives) — 중첩 `@Embedded`(`Address`/`PropertyPolicy`) 를 그대로 임베드하면 호스트 측에서
 * `@AttributeOverride` 누적이 폭발하므로, 원본 VO 가 아닌 *이미 직렬화된 의미값* 만 박제:
 * - `propertyId` (FK 참조용 / 본인 자원 인가 검증)
 * - `propertyName` — Property.name.value 값 박제
 * - `propertyAddress` — Address.fullAddress 박제 (도시 코드는 검색용이므로 박제 제외)
 * - `propertyPolicy` — 정책 텍스트 요약 (nullable — 정책 미설정 가능)
 *
 * 도메인 가드:
 * - `propertyId > 0` (영속화된 Property 참조)
 * - `propertyName` 비공백, 1~`MAX_NAME_LENGTH(100)` 자
 * - `propertyAddress` 비공백, 1~`MAX_ADDRESS_LENGTH(255)` 자
 * - `propertyPolicy` nullable, non-null 일 때 1~`MAX_POLICY_LENGTH(1000)` 자
 *
 * **컬럼 length ↔ init 가드** 는 동일 const (`Name.kt` / `Address.kt` 패턴, verify-code §6).
 */
@Embeddable
data class PropertySnapshot(
    @Column(name = "property_id", nullable = false)
    val propertyId: Long,
    @Column(name = "property_name", nullable = false, length = MAX_NAME_LENGTH)
    val propertyName: String,
    @Column(name = "property_address", nullable = false, length = MAX_ADDRESS_LENGTH)
    val propertyAddress: String,
    @Column(name = "property_policy", nullable = true, length = MAX_POLICY_LENGTH)
    val propertyPolicy: String? = null,
) {
    init {
        if (propertyId <= 0L) {
            throw CoreException(ErrorType.BAD_REQUEST, "propertyId 는 양수여야 합니다 (영속화된 Property 의 id).")
        }
        if (propertyName.isBlank() || propertyName.length > MAX_NAME_LENGTH) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "박제 propertyName 은 1~${MAX_NAME_LENGTH}자의 비공백 문자열이어야 합니다.",
            )
        }
        if (propertyAddress.isBlank() || propertyAddress.length > MAX_ADDRESS_LENGTH) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "박제 propertyAddress 는 1~${MAX_ADDRESS_LENGTH}자의 비공백 문자열이어야 합니다.",
            )
        }
        propertyPolicy?.let {
            if (it.isBlank() || it.length > MAX_POLICY_LENGTH) {
                throw CoreException(
                    ErrorType.BAD_REQUEST,
                    "박제 propertyPolicy 는 null 이거나 1~${MAX_POLICY_LENGTH}자의 비공백 문자열이어야 합니다.",
                )
            }
        }
    }

    companion object {
        const val MAX_NAME_LENGTH: Int = 100
        const val MAX_ADDRESS_LENGTH: Int = 255
        const val MAX_POLICY_LENGTH: Int = 1000
    }
}
