package com.stayloop.infrastructure.user

import com.stayloop.domain.user.User
import com.stayloop.domain.user.UserRepository
import com.stayloop.domain.user.value.LoginId
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.springframework.stereotype.Repository

@Repository
class UserRepositoryAdapter(
    private val userJpaRepository: UserJpaRepository,
) : UserRepository {
    override fun save(user: User): User {
        val entity = if (user.id == 0L) {
            UserJpaEntity.fromDomain(user)
        } else {
            userJpaRepository.findById(user.id)
                .orElseThrow { CoreException(ErrorType.NOT_FOUND, "사용자를 찾을 수 없습니다.") }
                .apply { password = user.password.encoded }
        }
        return userJpaRepository.save(entity).toDomain()
    }

    override fun findByLoginId(loginId: LoginId): User? =
        userJpaRepository.findByLoginId(loginId.value)?.toDomain()

    override fun existsByLoginId(loginId: LoginId): Boolean =
        userJpaRepository.existsByLoginId(loginId.value)
}
