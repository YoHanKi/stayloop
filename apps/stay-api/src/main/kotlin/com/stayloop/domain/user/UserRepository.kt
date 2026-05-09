package com.stayloop.domain.user

import com.stayloop.domain.user.value.LoginId

interface UserRepository {
    fun save(user: UserModel): UserModel

    fun findByLoginId(loginId: LoginId): UserModel?

    fun existsByLoginId(loginId: LoginId): Boolean

    /**
     * 다수 id 배치 조회. 입력 순서 보존하지 않으며 (호출자가 `associateBy { id }`),
     * 누락된 id 는 결과에서 빠진다.
     *
     * 어드민 *발급 이력 조회* 에서 BIGINT user_id → LoginId 매핑 N+1 회피용.
     */
    fun findAllByIds(ids: Collection<Long>): List<UserModel>
}
