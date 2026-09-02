package com.hermes.agent.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordSettingsTest {

    @Test
    fun normalizeTrigger_trimsAndCapsAt64Units() {
        val longString = "a".repeat(100)
        val normalized = WakeWordConfig.normalizeTrigger("   $longString   ")
        assertEquals(64, normalized.length)
        assertEquals("a".repeat(64), normalized)
    }

    @Test
    fun normalizeTriggers_deduplicatesCaseInsensitivelyAndCapsAt32() {
        val input = mutableListOf("  Hey Hermes  ", "hey hermes", "HEY HERMES", "   ", "Okay Hermes")
        for (i in 1..40) {
            input.add("Trigger $i")
        }

        val result = WakeWordConfig.normalizeTriggers(input)
        // Deduplicated "Hey Hermes" -> 1 entry + "Okay Hermes" -> 1 entry + triggers 1..30 -> 32 total
        assertEquals(32, result.size)
        assertEquals("Hey Hermes", result[0])
        assertEquals("Okay Hermes", result[1])
    }

    @Test
    fun normalizeTriggers_emptyFallsBackToDefault() {
        val result = WakeWordConfig.normalizeTriggers(listOf("   ", ""))
        assertEquals(listOf(WakeWordConfig.DEFAULT_TRIGGER), result)
    }

    @Test
    fun normalizeRoutingKey_lowercasesStripsPunctuationAndCollapsesSpaces() {
        val key = WakeWordConfig.normalizeRoutingKey("  Hey, Hermes!  What's   up?  ")
        assertEquals("hey hermes what s up", key)
    }

    @Test
    fun normalizeRoutingRules_normalizesKeysAndCapsAt32() {
        val input = mapOf(
            "Hey, Hermes!" to "conversational",
            "Take a note..." to "productivity",
            "Turn on lights!" to "device_control",
            "   " to "invalid",
        )
        val result = WakeWordConfig.normalizeRoutingRules(input)
        assertEquals("conversational", result["hey hermes"])
        assertEquals("productivity", result["take a note"])
        assertEquals("device_control", result["turn on lights"])
        assertEquals(3, result.size)
    }

    @Test
    fun matchTrigger_findsConfiguredTriggerInHeardSpeech() {
        val triggers = listOf("Hey Hermes", "Okay Hermes")
        val match1 = WakeWordConfig.matchTrigger("hey hermes what time is it", triggers)
        assertEquals("Hey Hermes", match1)

        val match2 = WakeWordConfig.matchTrigger("start listening now", triggers)
        assertNull(match2)
    }

    @Test
    fun matchWakeTrigger_requiresTheTriggerAtTheStartAndAShortUtterance() {
        val triggers = listOf("Hey Hermes", "Computer")

        assertEquals("Hey Hermes", WakeWordConfig.matchWakeTrigger("hey hermes", triggers))
        assertEquals("Hey Hermes", WakeWordConfig.matchWakeTrigger("hey hermes what's the time", triggers))
        assertEquals("Computer", WakeWordConfig.matchWakeTrigger("computer lights on", triggers))

        // Not at the start.
        assertNull(WakeWordConfig.matchWakeTrigger("so I said hey hermes to my friend", triggers))
        assertNull(WakeWordConfig.matchWakeTrigger("my new computer is fast", triggers))
        // At the start but a whole sentence, not a wake phrase.
        assertNull(
            WakeWordConfig.matchWakeTrigger(
                "hey hermes could you please add milk and eggs to my shopping list for tomorrow",
                triggers,
            ),
        )
        // Nothing heard.
        assertNull(WakeWordConfig.matchWakeTrigger("", triggers))
        assertNull(WakeWordConfig.matchWakeTrigger("what time is it", triggers))
    }

    @Test
    fun resolveTargetAgent_mapsTriggerToTargetOrDefault() {
        val rules = mapOf("turn on lights" to "device_control")
        val agent1 = WakeWordConfig.resolveTargetAgent("Turn on lights!", rules, "conversational")
        assertEquals("device_control", agent1)

        val agent2 = WakeWordConfig.resolveTargetAgent("Hey Hermes", rules, "conversational")
        assertEquals("conversational", agent2)
    }

    @Test
    fun serialization_roundTripsCorrectly() {
        val triggers = listOf("Hey Hermes", "Computer")
        val encoded = WakeWordConfig.encodeTriggers(triggers)
        val decoded = WakeWordConfig.decodeTriggers(encoded)
        assertEquals(triggers, decoded)

        val rules = mapOf("computer" to "research")
        val encodedRules = WakeWordConfig.encodeRoutingRules(rules)
        val decodedRules = WakeWordConfig.decodeRoutingRules(encodedRules)
        assertEquals(rules, decodedRules)
    }
}
