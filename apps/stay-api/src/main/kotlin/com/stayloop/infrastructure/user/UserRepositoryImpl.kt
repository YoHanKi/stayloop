package com.stayloop.infrastructure.user

import com.stayloop.domain.user.UserModel
import com.stayloop.domain.user.UserRepository
import com.stayloop.domain.user.value.LoginId
import org.springframework.stereotype.Component

@Component
class UserRepositoryImpl(
    private val userJpaRepository: UserJpaRepository,
) : UserRepository {
    override fun save(user: UserModel): UserModel = userJpaRepository.save(user)

    override fun findByLoginId(loginId: LoginId): UserModel? = userJpaRepository.findByLoginId(loginId)

    override fun existsByLoginId(loginId: LoginId): Boolean = userJpaRepository.existsByLoginId(loginId)
}
