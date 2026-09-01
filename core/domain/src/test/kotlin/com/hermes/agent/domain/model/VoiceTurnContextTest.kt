package com.hermes.agent.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTurnContextTest {

    @Test
    fun formatContextBlock_returnsNullWhenInterruptedAtIsNull() {
        val context = VoiceTurnContext(interruptedAt = null, mode = "talk")
        assertNull(VoiceTurnContext.formatContextBlock(context))
        assertNull(VoiceTurnContext.formatContextBlock(null))
    }

    @Test
    fun formatContextBlock_returnsFormattedBlockWhenInterruptedAtIsSet() {
        val isoTimestamp = "2026-09-02T10:15:30.000Z"
        val context = VoiceTurnContext(interruptedAt = isoTimestamp, mode = "talk")
        val block = VoiceTurnContext.formatContextBlock(context)
        assertEquals("[VOICE CONTEXT: { interrupted_at: \"$isoTimestamp\", mode: \"talk\" }]", block)
    }

    @Test
    fun defaults_areCorrect() {
        val context = VoiceTurnContext()
        assertEquals(8000L, context.silenceTimeoutMs)
        assertTrue(context.preferBluetooth)
        assertEquals("talk", context.mode)
        assertNull(context.interruptedAt)
    }
}
