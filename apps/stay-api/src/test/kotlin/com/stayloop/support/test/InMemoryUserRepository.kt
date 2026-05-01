package com.stayloop.support.test

import com.stayloop.domain.BaseEntity
import com.stayloop.domain.user.User
import com.stayloop.domain.user.UserRepository
import com.stayloop.domain.user.value.LoginId

class InMemoryUserRepository : UserRepository {
    private val store = mutableMapOf<Long, User>()
    private var sequence = 0L

    override fun save(user: User): User {
        if (user.id == 0L) {
            assignId(user, ++sequence)
        }
        store[user.id] = user
        return user
    }

    override fun findByLoginId(loginId: LoginId): User? =
        store.values.firstOrNull { it.loginId == loginId }

    override fun existsByLoginId(loginId: LoginId): Boolean =
        store.values.any { it.loginId == loginId }

    private fun assignId(user: User, id: Long) {
        val field = BaseEntity::class.java.getDeclaredField("id")
        field.isAccessible = true
        field.setLong(user, id)
    }
}
