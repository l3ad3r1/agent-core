package com.hermes.agent.data.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReasoningStaleTimeoutTest {

    @Test fun `known reasoning models get a floor above the 30s default`() {
        assertEquals(600_000L, ReasoningStaleTimeout.floorMillis("openai/o3"))
        assertEquals(300_000L, ReasoningStaleTimeout.floorMillis("openai/o3-mini-2025-01-31"))
        assertEquals(600_000L, ReasoningStaleTimeout.floorMillis("deepseek/deepseek-r1"))
        assertEquals(300_000L, ReasoningStaleTimeout.floorMillis("nvidia/nemotron-3-nano-8b"))
        assertEquals(180_000L, ReasoningStaleTimeout.floorMillis("qwen/qwen3-235b-a22b-thinking"))
        assertEquals(240_000L, ReasoningStaleTimeout.floorMillis("anthropic/claude-opus-4-6"))
    }

    @Test fun `longest slug wins so o3-mini is not swallowed by o3`() {
        assertEquals(300_000L, ReasoningStaleTimeout.floorMillis("o3-mini"))
        assertEquals(600_000L, ReasoningStaleTimeout.floorMillis("o3"))
    }

    @Test fun `non-reasoning models and junk return null`() {
        assertNull(ReasoningStaleTimeout.floorMillis("gpt-4o"))
        assertNull(ReasoningStaleTimeout.floorMillis("olmo-1"))          // not "o1"
        assertNull(ReasoningStaleTimeout.floorMillis("llama-3.1-70b"))
        assertNull(ReasoningStaleTimeout.floorMillis(""))
        assertNull(ReasoningStaleTimeout.floorMillis(null))
    }
}
