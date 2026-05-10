package com.stayloop.support.test

import com.stayloop.domain.BaseEntity
import com.stayloop.domain.user.UserModel
import com.stayloop.domain.user.UserRepository
import com.stayloop.domain.user.value.LoginId

class InMemoryUserRepository : UserRepository {
    private val store = mutableMapOf<Long, UserModel>()
    private var sequence = 0L

    override fun save(user: UserModel): UserModel {
        if (user.id == 0L) {
            assignId(user, ++sequence)
        }
        store[user.id] = user
        return user
    }

    override fun findByLoginId(loginId: LoginId): UserModel? =
        store.values.firstOrNull { it.loginId == loginId }

    override fun existsByLoginId(loginId: LoginId): Boolean =
        store.values.any { it.loginId == loginId }

    override fun findAllByIds(ids: Collection<Long>): List<UserModel> {
        if (ids.isEmpty()) return emptyList()
        val idSet = ids.toSet()
        return store.values.filter { it.id in idSet }
    }

    private fun assignId(user: UserModel, id: Long) {
        val field = BaseEntity::class.java.getDeclaredField("id")
        field.isAccessible = true
        field.setLong(user, id)
    }
}
