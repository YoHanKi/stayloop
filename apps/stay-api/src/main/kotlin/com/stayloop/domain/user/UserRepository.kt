package com.stayloop.domain.user

import com.stayloop.domain.user.value.LoginId

interface UserRepository {
    fun save(user: User): User

    fun findByLoginId(loginId: LoginId): User?

    fun existsByLoginId(loginId: LoginId): Boolean
}
