package com.hermes.agent.data.appagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppInteractionSessionTest {

    private var now = 1_000L
    private val session = AppInteractionSession { now }

    @Test
    fun `authorising publishes the app being driven`() {
        session.authorize("com.example.mail")
        assertEquals("com.example.mail", session.driving.value)
        assertTrue(session.isActive())
        assertNull(session.rejectionReason("com.example.mail"))
    }

    @Test
    fun `stop ends the session at once and the next action is refused`() {
        session.authorize("com.example.mail")
        session.clear()
        assertNull(session.driving.value)
        assertFalse(session.isActive())
        assertNotNull(session.rejectionReason("com.example.mail"))
    }

    @Test
    fun `an expired session stops publishing`() {
        session.authorize("com.example.mail")
        now += AppInteractionSession.SESSION_TTL_MS + 1
        assertFalse(session.isActive())
        assertNull(session.driving.value)
    }

    @Test
    fun `another app on screen is still refused`() {
        session.authorize("com.example.mail")
        assertNotNull(session.rejectionReason("com.example.bank"))
    }
}
