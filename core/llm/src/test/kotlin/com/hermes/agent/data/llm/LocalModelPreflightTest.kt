package com.hermes.agent.data.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelPreflightTest {

    private val mb = 1024L * 1024L
    private val gb = 1024L * 1024L * 1024L

    @Test
    fun `low memory flag immediately blocks load`() {
        val decision = LocalModelPreflight.evaluate(
            modelBytes = 800 * mb,
            totalRamBytes = 12 * gb,
            availableRamBytes = 6 * gb,
            lowMemory = true,
        )

        assertFalse(decision.allowed)
        assertEquals(PreflightLevel.BLOCKED, decision.level)
        assertEquals(0, decision.effectiveContextTokens)
        assertTrue(decision.detail.contains("lowMemory", ignoreCase = true))
    }

    @Test
    fun `total RAM below requirement blocks load`() {
        // 3B model (2 GB file) requires at least 1.0x total RAM (2 GB), but device only has 1.5 GB
        val decision = LocalModelPreflight.evaluate(
            modelBytes = 2 * gb,
            totalRamBytes = (1.5 * gb).toLong(),
            availableRamBytes = 1 * gb,
            lowMemory = false,
        )

        assertFalse(decision.allowed)
        assertEquals(PreflightLevel.BLOCKED, decision.level)
        assertTrue(decision.detail.contains("below required RAM", ignoreCase = true))
    }

    @Test
    fun `severely low available RAM blocks load`() {
        // 1B model (800 MB file) -> working set ~ (0.65 * 800MB + 384MB) = 904 MB
        // If available RAM is only 300 MB (< 0.65 * 904 MB = 587 MB), it is severe
        val decision = LocalModelPreflight.evaluate(
            modelBytes = 800 * mb,
            totalRamBytes = 6 * gb,
            availableRamBytes = 300 * mb,
            lowMemory = false,
        )

        assertFalse(decision.allowed)
        assertEquals(PreflightLevel.BLOCKED, decision.level)
        assertTrue(decision.detail.contains("Insufficient available RAM", ignoreCase = true))
    }

    @Test
    fun `tight available RAM returns warning and clamps context`() {
        // 1B model (800 MB file) -> working set ~ 904 MB
        // Available RAM 800 MB -> allowed with warning and context clamped to 1024
        val decision = LocalModelPreflight.evaluate(
            modelBytes = 800 * mb,
            totalRamBytes = 6 * gb,
            availableRamBytes = 800 * mb,
            lowMemory = false,
            requestedContextTokens = 2048,
        )

        assertTrue(decision.allowed)
        assertEquals(PreflightLevel.WARNING, decision.level)
        assertEquals(1024, decision.effectiveContextTokens)
    }

    @Test
    fun `ample available RAM returns optimal with full context`() {
        // 1B model (800 MB file) with 4 GB free RAM on 12 GB device
        val decision = LocalModelPreflight.evaluate(
            modelBytes = 800 * mb,
            totalRamBytes = 12 * gb,
            availableRamBytes = 4 * gb,
            lowMemory = false,
            requestedContextTokens = 2048,
        )

        assertTrue(decision.allowed)
        assertEquals(PreflightLevel.OPTIMAL, decision.level)
        assertEquals(2048, decision.effectiveContextTokens)
    }

    @Test
    fun `large 3B model available RAM under working set blocks load`() {
        // 3.5 GB model file -> working set ~ (0.75 * 3.5GB + 600MB) = 3.225 GB
        // Available RAM 2 GB -> blocks because model >= 3 GB and available < working set
        val decision = LocalModelPreflight.evaluate(
            modelBytes = (3.5 * gb).toLong(),
            totalRamBytes = 8 * gb,
            availableRamBytes = 2 * gb,
            lowMemory = false,
        )

        assertFalse(decision.allowed)
        assertEquals(PreflightLevel.BLOCKED, decision.level)
    }
}
