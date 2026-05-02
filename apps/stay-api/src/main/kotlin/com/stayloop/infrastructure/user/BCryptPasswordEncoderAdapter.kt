package com.stayloop.infrastructure.user

import com.stayloop.domain.user.PasswordEncoder
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Component

@Component
class BCryptPasswordEncoderAdapter(
    private val delegate: BCryptPasswordEncoder = BCryptPasswordEncoder(),
) : PasswordEncoder {
    override fun encode(raw: String): String = delegate.encode(raw)

    override fun matches(raw: String, encoded: String): Boolean = delegate.matches(raw, encoded)
}
