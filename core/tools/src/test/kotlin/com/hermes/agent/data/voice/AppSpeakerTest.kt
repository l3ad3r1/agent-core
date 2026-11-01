package com.hermes.agent.data.voice

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSpeakerTest {

    private class FakeSpeaker(val ok: Boolean) : VoiceOutputManager.Speaker {
        val said = mutableListOf<String>()
        var stopped = false
        override suspend fun speak(text: String): Boolean { said += text; return ok }
        override fun stop() { stopped = true }
    }

    private fun manager(speaker: FakeSpeaker) =
        VoiceOutputManager(mockk<Context>(relaxed = true)).apply { this.speaker = speaker }

    @Test
    fun `the app's own voice speaks the reply`() = runTest {
        val own = FakeSpeaker(ok = true)
        val events = manager(own).speak("Good evening.").toList()
        assertEquals(listOf("Good evening."), own.said)
        assertEquals(listOf(VoiceOutputEvent.Start, VoiceOutputEvent.Done), events)
    }

    @Test
    fun `when the app's voice cannot speak, the platform voice is tried`() = runTest {
        val own = FakeSpeaker(ok = false)
        val events = manager(own).speak("Hello").toList()
        // No platform engine in a unit test: the fallback reports it is not ready.
        assertTrue(events.last() is VoiceOutputEvent.Error)
    }

    @Test
    fun `stop reaches the app's voice and a speaker makes the manager available`() {
        val own = FakeSpeaker(ok = true)
        val m = manager(own)
        assertTrue(m.isAvailable())
        m.stop()
        assertTrue(own.stopped)
    }
}
