package com.hermes.agent.data.llm

import com.hermes.agent.domain.credentials.KeyStatus
import com.hermes.agent.domain.credentials.PoolRotationStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CredentialPoolManagerTest {

    private lateinit var poolManager: CredentialPoolManager

    @Before
    fun setUp() {
        poolManager = CredentialPoolManager()
    }

    @Test
    fun `getActiveKey returns fallback when pool is empty`() {
        val key = poolManager.getActiveKey("openai", fallbackKey = "sk-fallback")
        assertEquals("sk-fallback", key)
    }

    @Test
    fun `getActiveKey rotates in round robin`() {
        poolManager.addKey("openai", "sk-key-1", "Key 1")
        poolManager.addKey("openai", "sk-key-2", "Key 2")

        val k1 = poolManager.getActiveKey("openai", strategy = PoolRotationStrategy.ROUND_ROBIN)
        val k2 = poolManager.getActiveKey("openai", strategy = PoolRotationStrategy.ROUND_ROBIN)
        val k3 = poolManager.getActiveKey("openai", strategy = PoolRotationStrategy.ROUND_ROBIN)

        assertEquals("sk-key-1", k1)
        assertEquals("sk-key-2", k2)
        assertEquals("sk-key-1", k3)
    }

    @Test
    fun `reportKeyExhausted marks key as cooldown and rotates to next key`() {
        poolManager.addKey("anthropic", "sk-ant-1")
        poolManager.addKey("anthropic", "sk-ant-2")

        assertEquals("sk-ant-1", poolManager.getActiveKey("anthropic"))

        // Report 429 rate limit on sk-ant-1
        poolManager.reportKeyExhausted("anthropic", "sk-ant-1", cooldownSeconds = 120L)

        val keys = poolManager.getKeysForProvider("anthropic")
        assertEquals(KeyStatus.COOLDOWN, keys.find { it.apiKey == "sk-ant-1" }?.keyStatus)

        // Active key should now be sk-ant-2
        val active = poolManager.getActiveKey("anthropic")
        assertEquals("sk-ant-2", active)
        assertTrue(poolManager.hasAlternativeKey("anthropic", "sk-ant-1"))
        assertFalse(poolManager.hasAlternativeKey("anthropic", "sk-ant-2"))
    }

    @Test
    fun `permanent failure marks key DEAD`() {
        poolManager.addKey("deepseek", "sk-ds-dead")
        poolManager.reportKeyExhausted("deepseek", "sk-ds-dead", isPermanentFailure = true)

        val keys = poolManager.getKeysForProvider("deepseek")
        assertEquals(KeyStatus.DEAD, keys.first().keyStatus)
        assertEquals("sk-fallback", poolManager.getActiveKey("deepseek", fallbackKey = "sk-fallback"))
    }
}
