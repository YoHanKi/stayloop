package com.stayloop.interfaces.api.auth

import com.stayloop.domain.user.value.LoginId

/**
 * 본인 자원 인가용 식별자. `X-Loopers-LoginId` 헤더 단독으로 사용자를 식별한다 — 비밀번호 재인증이
 * 본질이 아닌 라우트(찜 토글·예약 생성/취소/조회)에서 [LoginCredentials] 의 password 헤더 강제를 우회한다.
 *
 * 보안 레이어(토큰 기반 인증)가 도입되는 시점에 그 토큰에서 추출한 사용자로 교체될 자리다.
 * 후임이 모든 라우트로 무심코 확장하지 않도록 임시 워크어라운드임을 명시한다.
 */
data class LoginUser(
    val loginId: LoginId,
) {
    companion object {
        const val LOGIN_ID_HEADER = "X-Loopers-LoginId"
    }
}
