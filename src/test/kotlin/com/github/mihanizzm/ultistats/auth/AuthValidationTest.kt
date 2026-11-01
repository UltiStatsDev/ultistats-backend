package com.github.mihanizzm.ultistats.auth

import com.github.mihanizzm.ultistats.auth.service.AuthValidation
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuthValidationTest {
    @Test
    fun `password bounds count Unicode code points and retain spaces`() {
        assertFailsWith<IllegalArgumentException> { AuthValidation.password("a".repeat(7)) }
        assertEquals(" password ", AuthValidation.password(" password "))
        assertEquals("😀".repeat(8), AuthValidation.password("😀".repeat(8)))
        assertEquals("😀".repeat(1024), AuthValidation.password("😀".repeat(1024)))
        assertFailsWith<IllegalArgumentException> { AuthValidation.password("a".repeat(1025)) }
    }

    @Test
    fun `malformed UTF16 passwords are rejected instead of replacement encoding`() {
        listOf("abcdefgh\uD800", "abcdefgh\uDC00", "abc\uD800defgh").forEach {
            assertFailsWith<IllegalArgumentException> { AuthValidation.password(it) }
        }
    }

    @Test
    fun `login normalization is locale independent ASCII and bounded`() {
        assertEquals("user.i-12", AuthValidation.login(" User.I-12 "))
        assertEquals("a".repeat(64), AuthValidation.login("A".repeat(64)))
        listOf("", " ", "Женя", "a b", "a@b", "a".repeat(65)).forEach {
            assertFailsWith<IllegalArgumentException> { AuthValidation.login(it) }
        }
    }

    @Test
    fun `display name accepts trimmed international text and rejects malformed or overlong input`() {
        assertEquals("Организатор 😀", AuthValidation.displayName(" Организатор 😀 "))
        assertEquals("😀".repeat(120), AuthValidation.displayName("😀".repeat(120)))
        listOf(" ", "😀".repeat(121), "abc\uD800").forEach {
            assertFailsWith<IllegalArgumentException> { AuthValidation.displayName(it) }
        }
    }
}
