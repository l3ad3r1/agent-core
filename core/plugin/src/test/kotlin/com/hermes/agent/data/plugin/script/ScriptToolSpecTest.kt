package com.hermes.agent.data.plugin.script

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptToolSpecTest {

    private val spec = ScriptToolSpec(name = "t", description = "d", requiresConfirmation = false)

    @Test
    fun `a module cannot waive confirmation for what its grants make risky`() {
        val p = ScriptPluginPermissions
        assertTrue(spec.toDescriptor(setOf(p.DATA_WRITE)).requiresConfirmation)
        assertTrue(spec.toDescriptor(setOf(p.DATA_READ, p.NETWORK)).requiresConfirmation)
        // Read-only or network-only modules keep the choice they declared.
        assertFalse(spec.toDescriptor(setOf(p.DATA_READ)).requiresConfirmation)
        assertFalse(spec.toDescriptor(setOf(p.NETWORK)).requiresConfirmation)
    }
}
