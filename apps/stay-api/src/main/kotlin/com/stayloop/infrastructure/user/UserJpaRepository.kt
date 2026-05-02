package com.stayloop.infrastructure.user

import com.stayloop.domain.user.UserModel
import com.stayloop.domain.user.value.LoginId
import org.springframework.data.jpa.repository.JpaRepository

interface UserJpaRepository : JpaRepository<UserModel, Long> {
    fun findByLoginId(loginId: LoginId): UserModel?

    fun existsByLoginId(loginId: LoginId): Boolean
}
