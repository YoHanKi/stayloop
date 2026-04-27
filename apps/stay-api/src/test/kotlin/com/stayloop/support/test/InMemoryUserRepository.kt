package com.stayloop.support.test

import com.stayloop.domain.user.User
import com.stayloop.domain.user.UserRepository
import com.stayloop.domain.user.value.LoginId

class InMemoryUserRepository : UserRepository {
    private val storeById = mutableMapOf<Long, User>()
    private val idByLoginId = mutableMapOf<String, Long>()
    private var sequence = 0L

    override fun save(user: User): User {
        val id = if (user.id == 0L) ++sequence else user.id
        val saved = User.reconstruct(
            id = id,
            loginId = user.loginId,
            password = user.password,
            name = user.name,
            birthDate = user.birthDate,
            email = user.email,
            phoneNumber = user.phoneNumber,
        )
        storeById[id] = saved
        idByLoginId[user.loginId.value] = id
        return saved
    }

    override fun findByLoginId(loginId: LoginId): User? =
        idByLoginId[loginId.value]?.let { storeById[it] }

    override fun existsByLoginId(loginId: LoginId): Boolean =
        idByLoginId.containsKey(loginId.value)
}
