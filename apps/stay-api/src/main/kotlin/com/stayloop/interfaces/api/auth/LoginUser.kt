package com.stayloop.interfaces.api.auth

import com.stayloop.domain.user.value.LoginId

/**
 * 본인 자원 인가용 — `X-Loopers-LoginId` 헤더 한 개만으로 식별. (`docs/plan/week2-3.md §⑧ Phase B/C`)
 *
 * 비밀번호 재인증이 필요한 흐름(`UserFacade.getMyInfo` / `changePassword`) 은 `LoginCredentials` 를 사용하고,
 * 단순 식별만 필요한 흐름(찜 토글 / 예약 생성·취소·조회 등) 은 본 VO 를 사용한다. `LoginCredentials` 의
 * 비밀번호 헤더를 모든 요청마다 강제하는 것은 세션 부재 환경의 임시 워크어라운드이며, 인가만 필요한 라우트
 * 에서는 의도적으로 password 검증을 우회한다 (보안 레이어가 들어오는 시점에 토큰 기반으로 교체될 자리).
 */
data class LoginUser(
    val loginId: LoginId,
) {
    companion object {
        const val LOGIN_ID_HEADER = "X-Loopers-LoginId"
    }
}
