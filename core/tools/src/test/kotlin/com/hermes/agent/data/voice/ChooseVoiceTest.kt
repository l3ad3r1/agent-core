package com.hermes.agent.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChooseVoiceTest {

    private val voices = listOf(
        VoiceOption("en-us-x-iol-local", needsNetwork = false),
        VoiceOption("en-us-x-iom-network", needsNetwork = true),
        VoiceOption("en-us-x-iom-local", needsNetwork = false),
        VoiceOption("en-gb-x-rjs-network", needsNetwork = true),
        VoiceOption("en-gb-x-gbd-local", needsNetwork = false, features = setOf("notInstalled")),
    )

    @Test
    fun `the first preference that exists wins, installed before network`() {
        assertEquals("en-gb-x-rjs-network", chooseVoice(voices, listOf("en-gb-x-rjs", "en-us-x-iom")))
        assertEquals("en-us-x-iom-local", chooseVoice(voices, listOf("en-us-x-iom")))
    }

    @Test
    fun `a voice reported as not installed is skipped`() {
        assertEquals("en-us-x-iom-local", chooseVoice(voices, listOf("en-gb-x-gbd", "en-us-x-iom")))
    }

    @Test
    fun `no match keeps the engine default`() {
        assertNull(chooseVoice(voices, listOf("fr-fr-x-abc")))
        assertNull(chooseVoice(voices, emptyList()))
    }
}
