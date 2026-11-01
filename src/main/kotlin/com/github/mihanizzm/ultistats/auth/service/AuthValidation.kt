package com.github.mihanizzm.ultistats.auth.service

import java.security.SecureRandom
import java.util.Base64
import java.util.Locale

object AuthValidation {
    private val random = SecureRandom()
    fun password(value: String): String = value.also { unicodeLength(it, 8, 1024, "password") }
    fun login(value: String): String = value.trim().also {
        require(it.matches(Regex("[A-Za-z0-9._-]{1,64}"))) { "Invalid login" }
    }.lowercase(Locale.ROOT)
    fun displayName(value: String): String = value.trim().also { unicodeLength(it, 1, 120, "displayName") }
    fun search(value: String?): String? = value?.trim()?.also { unicodeLength(it, 0, 120, "search") }?.ifEmpty { null }
    fun secret(): String = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private fun unicodeLength(value: String, min: Int, max: Int, field: String) {
        var index = 0
        var length = 0
        while (index < value.length) {
            val char = value[index++]
            if (Character.isHighSurrogate(char)) {
                require(index < value.length && Character.isLowSurrogate(value[index++])) { "Invalid $field" }
            } else require(!Character.isLowSurrogate(char)) { "Invalid $field" }
            length++
        }
        require(length in min..max) { "Invalid $field length" }
    }
}
