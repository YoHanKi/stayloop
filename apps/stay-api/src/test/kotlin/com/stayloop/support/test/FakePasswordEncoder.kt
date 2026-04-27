package com.stayloop.support.test

import com.stayloop.domain.user.PasswordEncoder

class FakePasswordEncoder : PasswordEncoder {
    override fun encode(raw: String): String = "$PREFIX$raw"

    override fun matches(raw: String, encoded: String): Boolean = encoded == "$PREFIX$raw"

    companion object {
        const val PREFIX = "encoded:"
    }
}
