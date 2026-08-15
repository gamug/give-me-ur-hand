package com.givemeurhand.backend.professional

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PasswordHasherTest {
    @Test
    fun `hashes and verifies a matching password`() {
        val hash = PasswordHasher.hash("clave-segura-123")
        assertTrue(PasswordHasher.verify("clave-segura-123", hash))
    }

    @Test
    fun `rejects a non-matching password`() {
        val hash = PasswordHasher.hash("clave-segura-123")
        assertFalse(PasswordHasher.verify("otra-clave", hash))
    }

    @Test
    fun `produces a different hash each time due to random salt`() {
        assertNotEquals(PasswordHasher.hash("misma-clave"), PasswordHasher.hash("misma-clave"))
    }
}
