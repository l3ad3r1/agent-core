package com.hermes.agent.data.llm

import com.hermes.agent.domain.credentials.KeyStatus
import com.hermes.agent.domain.credentials.PoolRotationStrategy
import com.hermes.agent.domain.credentials.ProviderKeyEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages multi-key credential pools with automatic 429 rotation and cooldown tracking.
 * Ported from upstream `credential_pool.py`.
 */
@Singleton
class CredentialPoolManager @Inject constructor() {

    private val providerPools = ConcurrentHashMap<String, MutableList<ProviderKeyEntry>>()
    private val roundRobinIndices = ConcurrentHashMap<String, Int>()

    private val _poolStateFlow = MutableStateFlow<Map<String, List<ProviderKeyEntry>>>(emptyMap())
    val poolStateFlow: StateFlow<Map<String, List<ProviderKeyEntry>>> = _poolStateFlow.asStateFlow()

    fun addKey(
        provider: String,
        apiKey: String,
        alias: String? = null,
    ): ProviderKeyEntry {
        val normalizedProvider = normalizeProvider(provider)
        val list = providerPools.getOrPut(normalizedProvider) { mutableListOf() }
        synchronized(list) {
            val existing = list.find { it.apiKey == apiKey }
            if (existing != null) return existing

            val entry = ProviderKeyEntry(
                id = UUID.randomUUID().toString(),
                provider = normalizedProvider,
                apiKey = apiKey,
                alias = alias,
            )
            list.add(entry)
            syncState()
            return entry
        }
    }

    fun removeKey(provider: String, keyId: String): Boolean {
        val normalizedProvider = normalizeProvider(provider)
        val list = providerPools[normalizedProvider] ?: return false
        val removed = synchronized(list) {
            list.removeIf { it.id == keyId }
        }
        if (removed) syncState()
        return removed
    }

    fun getKeysForProvider(provider: String): List<ProviderKeyEntry> {
        val normalizedProvider = normalizeProvider(provider)
        val list = providerPools[normalizedProvider] ?: return emptyList()
        return synchronized(list) { list.toList() }
    }

    fun getActiveKey(
        provider: String,
        fallbackKey: String = "",
        strategy: PoolRotationStrategy = PoolRotationStrategy.FILL_FIRST,
    ): String {
        val normalizedProvider = normalizeProvider(provider)
        val list = providerPools[normalizedProvider]

        if (list == null || list.isEmpty()) {
            return fallbackKey
        }

        val now = System.currentTimeMillis()
        synchronized(list) {
            val available = list.filter { it.isAvailable(now) }
            if (available.isEmpty()) {
                Timber.tag("CredentialPool").w("All %d pool keys for %s are exhausted or dead", list.size, provider)
                return fallbackKey
            }

            val selected = when (strategy) {
                PoolRotationStrategy.FILL_FIRST -> available.first()
                PoolRotationStrategy.ROUND_ROBIN -> {
                    val idx = roundRobinIndices.getOrPut(normalizedProvider) { 0 }
                    val picked = available[idx % available.size]
                    roundRobinIndices[normalizedProvider] = (idx + 1) % available.size
                    picked
                }
                PoolRotationStrategy.LEAST_USED -> available.minByOrNull { it.totalRequests } ?: available.first()
            }

            return selected.apiKey
        }
    }

    fun hasAlternativeKey(provider: String, currentKey: String): Boolean {
        val normalizedProvider = normalizeProvider(provider)
        val list = providerPools[normalizedProvider] ?: return false
        val now = System.currentTimeMillis()
        return synchronized(list) {
            list.any { it.apiKey != currentKey && it.isAvailable(now) }
        }
    }

    fun reportKeyExhausted(
        provider: String,
        apiKey: String,
        cooldownSeconds: Long = 60L,
        isPermanentFailure: Boolean = false,
    ) {
        val normalizedProvider = normalizeProvider(provider)
        val list = providerPools[normalizedProvider] ?: return
        val now = System.currentTimeMillis()
        synchronized(list) {
            val idx = list.indexOfFirst { it.apiKey == apiKey }
            if (idx >= 0) {
                val entry = list[idx]
                val failures = entry.consecutiveFailures + 1
                // Backoff multiplier on repeated rate limits
                val multiplier = (1L shl (failures - 1).coerceAtMost(5))
                val finalCooldown = (cooldownSeconds * multiplier * 1000L)

                val newStatus = if (isPermanentFailure || failures >= 8) KeyStatus.DEAD else KeyStatus.COOLDOWN
                list[idx] = entry.copy(
                    status = newStatus.name,
                    cooldownUntilMs = now + finalCooldown,
                    consecutiveFailures = failures,
                    lastUsedAtMs = now,
                )
                Timber.tag("CredentialPool").w(
                    "Key %s for %s marked %s (cooldown %ds, failures %d)",
                    entry.alias ?: entry.id.take(8),
                    provider,
                    newStatus,
                    (finalCooldown / 1000),
                    failures,
                )
                syncState()
            }
        }
    }

    fun reportKeySuccess(provider: String, apiKey: String) {
        val normalizedProvider = normalizeProvider(provider)
        val list = providerPools[normalizedProvider] ?: return
        val now = System.currentTimeMillis()
        synchronized(list) {
            val idx = list.indexOfFirst { it.apiKey == apiKey }
            if (idx >= 0) {
                val entry = list[idx]
                list[idx] = entry.copy(
                    status = KeyStatus.ACTIVE.name,
                    consecutiveFailures = 0,
                    totalRequests = entry.totalRequests + 1,
                    lastUsedAtMs = now,
                )
                syncState()
            }
        }
    }

    private fun normalizeProvider(provider: String): String =
        provider.trim().lowercase().removeSuffix("-cloud")

    private fun syncState() {
        val snapshot = providerPools.mapValues { (_, v) -> synchronized(v) { v.toList() } }
        _poolStateFlow.value = snapshot
    }
}
