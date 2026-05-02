package com.stayloop.infrastructure.user

import com.stayloop.domain.user.User
import com.stayloop.domain.user.UserRepository
import org.springframework.data.jpa.repository.JpaRepository

interface UserJpaRepository :
    UserRepository,
    JpaRepository<User, Long>
