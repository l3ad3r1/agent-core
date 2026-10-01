package com.hermes.agent.data.llm

import com.hermes.agent.domain.credentials.KeyStatus
import com.hermes.agent.domain.credentials.PoolRotationStrategy
import com.hermes.agent.domain.credentials.ProviderKeyEntry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

    @Test
    fun `concurrent provider updates publish state without nested list locks`() {
        poolManager.addKey("a", "key-a")
        poolManager.addKey("b", "key-b")
        val field = CredentialPoolManager::class.java.getDeclaredField("providerPools")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val pools = field.get(poolManager) as ConcurrentHashMap<String, MutableList<ProviderKeyEntry>>
        val barrier = CyclicBarrier(2)
        for (provider in listOf("a", "b")) {
            pools[provider] = BarrierList(pools.getValue(provider).toMutableList(), barrier)
        }
        val completed = CountDownLatch(2)
        val failures = CopyOnWriteArrayList<Throwable>()
        for (provider in listOf("a", "b")) {
            Thread {
                try {
                    poolManager.reportKeySuccess(provider, "key-$provider")
                } catch (failure: Throwable) {
                    failures.add(failure)
                } finally {
                    completed.countDown()
                }
            }.apply { isDaemon = true; start() }
        }
        assertTrue("updates deadlocked while publishing provider state", completed.await(5, TimeUnit.SECONDS))
        assertTrue(failures.toString(), failures.isEmpty())
        assertEquals(1L, poolManager.getKeysForProvider("a").single().totalRequests)
        assertEquals(1L, poolManager.getKeysForProvider("b").single().totalRequests)
    }

    /** Both mutations reach their list traversal while still holding their own monitor. */
    private class BarrierList(
        private val entries: MutableList<ProviderKeyEntry>,
        private val barrier: CyclicBarrier,
    ) : AbstractMutableList<ProviderKeyEntry>() {
        private val firstRead = AtomicBoolean(true)
        override val size: Int get() = entries.size
        override fun get(index: Int): ProviderKeyEntry {
            if (firstRead.compareAndSet(true, false)) barrier.await(2, TimeUnit.SECONDS)
            return entries[index]
        }
        override fun set(index: Int, element: ProviderKeyEntry): ProviderKeyEntry = entries.set(index, element)
        override fun add(index: Int, element: ProviderKeyEntry) = entries.add(index, element)
        override fun removeAt(index: Int): ProviderKeyEntry = entries.removeAt(index)
    }
}
