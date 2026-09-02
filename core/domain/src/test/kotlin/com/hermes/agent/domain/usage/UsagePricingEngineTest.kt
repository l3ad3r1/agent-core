package com.hermes.agent.domain.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsagePricingEngineTest {

    @Test
    fun `pricing for gpt-4o calculates correctly`() {
        val cost = UsagePricingEngine.calculateCost(
            modelName = "gpt-4o",
            promptTokens = 1_000_000,
            completionTokens = 1_000_000,
        )
        assertFalse(cost.isZeroCost)
        // 2.50 prompt + 10.00 completion = 12.50
        assertEquals(12.50, cost.amountUsd, 0.001)
    }

    @Test
    fun `pricing for on-device and local models is zero`() {
        val localCost = UsagePricingEngine.calculateCost(
            modelName = "llama-3-8b-q4",
            promptTokens = 500_000,
            completionTokens = 500_000,
            isOnDevice = true,
        )
        assertTrue(localCost.isZeroCost)
        assertEquals(0.0, localCost.amountUsd, 0.0)

        val ollamaCost = UsagePricingEngine.calculateCost(
            modelName = "ollama/mistral:latest",
            promptTokens = 500_000,
            completionTokens = 500_000,
        )
        assertTrue(ollamaCost.isZeroCost)
        assertEquals(0.0, ollamaCost.amountUsd, 0.0)
    }

    @Test
    fun `gpt-4_1 and gpt-5 families resolve to their own tiers, not the gpt-4 fallback`() {
        // Substring matching: "gpt-4.1-mini" must not fall through to the "gpt-4"
        // ($30/$60) key. 1M prompt + 1M completion.
        assertEquals(2.00, UsagePricingEngine.calculateCost("gpt-4.1-mini", 1_000_000, 1_000_000).amountUsd, 0.001)
        assertEquals(0.50, UsagePricingEngine.calculateCost("gpt-4.1-nano", 1_000_000, 1_000_000).amountUsd, 0.001)
        assertEquals(10.00, UsagePricingEngine.calculateCost("gpt-4.1-2025-04-14", 1_000_000, 1_000_000).amountUsd, 0.001)
        assertEquals(11.25, UsagePricingEngine.calculateCost("gpt-5", 1_000_000, 1_000_000).amountUsd, 0.001)
        assertEquals(2.25, UsagePricingEngine.calculateCost("gpt-5-mini", 1_000_000, 1_000_000).amountUsd, 0.001)
        // o-series minis must not resolve to their non-mini parent.
        assertEquals(15.00, UsagePricingEngine.calculateCost("o1-mini", 1_000_000, 1_000_000).amountUsd, 0.001)
        assertEquals(5.50, UsagePricingEngine.calculateCost("o3-mini", 1_000_000, 1_000_000).amountUsd, 0.001)
    }

    @Test
    fun `pricing for claude sonnet calculates correctly`() {
        val cost = UsagePricingEngine.calculateCost(
            modelName = "claude-3-5-sonnet-20241022",
            promptTokens = 100_000, // 0.1M * $3 = $0.30
            completionTokens = 10_000, // 0.01M * $15 = $0.15
        )
        assertEquals(0.45, cost.amountUsd, 0.001)
    }
}
