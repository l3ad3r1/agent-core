package com.hermes.agent.domain.credentials

import kotlinx.serialization.Serializable

/**
 * Lifecycle status of an API key in the credential pool.
 * Ported from upstream `credential_pool.py`.
 */
enum class KeyStatus {
    ACTIVE,
    COOLDOWN, // In temporary 429/rate-limit cooldown
    DEAD,     // Permanently revoked (401/403 invalid credentials)
}

/**
 * Selection strategy when rotating across active keys for a provider.
 */
enum class PoolRotationStrategy {
    FILL_FIRST,  // Use primary key until rate limited, then second, etc.
    ROUND_ROBIN, // Rotate evenly across all available keys
    LEAST_USED,  // Pick the key with the smallest recent request count
}

@Serializable
data class ProviderKeyEntry(
    val id: String,
    val provider: String,            // e.g. "openai", "anthropic", "openrouter", "deepseek"
    val apiKey: String,              // enc:v1:... or plaintext if in memory
    val alias: String? = null,       // e.g. "Personal Key", "Work Key"
    val status: String = KeyStatus.ACTIVE.name,
    val cooldownUntilMs: Long = 0L,
    val consecutiveFailures: Int = 0,
    val totalRequests: Long = 0L,
    val lastUsedAtMs: Long = 0L,
    val addedAtMs: Long = System.currentTimeMillis(),
) {
    val keyStatus: KeyStatus
        get() = runCatching { KeyStatus.valueOf(status) }.getOrDefault(KeyStatus.ACTIVE)

    fun isAvailable(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (keyStatus == KeyStatus.DEAD) return false
        if (keyStatus == KeyStatus.COOLDOWN && nowMs < cooldownUntilMs) return false
        return true
    }
}
