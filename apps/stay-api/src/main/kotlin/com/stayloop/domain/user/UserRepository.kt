package com.stayloop.domain.user

import com.stayloop.domain.user.value.LoginId

interface UserRepository {
    fun save(user: UserModel): UserModel

    fun findByLoginId(loginId: LoginId): UserModel?

    fun existsByLoginId(loginId: LoginId): Boolean
}
