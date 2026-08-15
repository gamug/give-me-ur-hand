package com.givemeurhand.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.UUID

class SessionIdGeneratorTest {
    @Test
    fun `generates a valid UUID`() {
        val id = SessionIdGenerator.generate()
        assertEquals(id, UUID.fromString(id).toString())
    }

    @Test
    fun `generates a different id each time`() {
        assertNotEquals(SessionIdGenerator.generate(), SessionIdGenerator.generate())
    }
}
