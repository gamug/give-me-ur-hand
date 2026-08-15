package com.givemeurhand.backend.professional

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JwtServiceTest {
    @Test
    fun `issues a token whose subject can be recovered`() {
        val service = JwtService("test-secret")
        val token = service.issue("professional-123")
        assertEquals("professional-123", service.verify(token))
    }

    @Test
    fun `rejects a token signed with a different secret`() {
        val issuer = JwtService("secret-a")
        val verifier = JwtService("secret-b")
        val token = issuer.issue("professional-123")
        assertNull(verifier.verify(token))
    }

    @Test
    fun `rejects a garbage token`() {
        val service = JwtService("test-secret")
        assertNull(service.verify("not-a-real-token"))
    }
}
