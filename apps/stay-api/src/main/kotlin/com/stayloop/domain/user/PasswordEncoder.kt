package com.stayloop.domain.user

interface PasswordEncoder {
    fun encode(raw: String): String

    fun matches(raw: String, encoded: String): Boolean
}
