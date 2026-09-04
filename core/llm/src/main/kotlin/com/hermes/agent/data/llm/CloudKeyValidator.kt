package com.hermes.agent.data.llm

/**
 * Catches an API key pasted into the wrong provider.
 *
 * Providers reject a foreign key with a bare 401 whose body names the *key*, not
 * the mistake — "Incorrect API key provided: cpk_c8*****GwCW" from SambaNova,
 * when `cpk_` is a Chutes key. Nothing in that tells you the provider selection
 * is what's wrong, and the failure looks identical to an expired or mistyped
 * key, so it costs real time to diagnose.
 *
 * This is deliberately conservative: it only speaks up when a key carries a
 * prefix that unambiguously belongs to a *different* provider. Most gateways
 * issue opaque random strings with no prefix at all, and a plain `sk-` is used
 * across half the OpenAI-compatible ecosystem — guessing from those would fire
 * on correct keys, which is worse than staying quiet.
 */
object CloudKeyValidator {

    /**
     * Vendor-assigned prefixes distinctive enough to identify their issuer.
     *
     * Order matters: `sk-or-` (OpenRouter) must be tested before any shorter
     * `sk-` entry would be. Plain `sk-` is intentionally absent — OpenAI,
     * DeepSeek and many proxies all use it.
     */
    private val prefixToProvider: List<Pair<String, String>> = listOf(
        "sk-or-" to "openrouter",
        "cpk_" to "chutes",
        "gsk_" to "groq",
        "nvapi-" to "nvidia",
        "hf_" to "huggingface",
        "AIza" to "gemini",
    )

    /**
     * The id of the provider [apiKey] actually belongs to, when that is
     * definitely not [providerId]; null when the key looks fine, carries no
     * recognised prefix, or the target is a custom endpoint.
     *
     * Custom providers are skipped: they proxy other vendors by design, so a
     * Chutes key on a custom base URL is a normal setup, not a mistake.
     */
    fun mismatchedProvider(providerId: String, apiKey: String): String? {
        if (providerId.startsWith("custom_")) return null
        val key = apiKey.trim()
        if (key.isEmpty()) return null
        val owner = prefixToProvider.firstOrNull { (prefix, _) -> key.startsWith(prefix) }
            ?.second
            ?: return null
        return owner.takeIf { it != providerId }
    }

    /**
     * Human-readable warning for a mismatch, or null when there is nothing to
     * say. Phrased as an observation rather than a block — the heuristic can be
     * wrong, and the user may know something we don't.
     */
    fun mismatchWarning(providerId: String, providerName: String, apiKey: String): String? {
        val owner = mismatchedProvider(providerId, apiKey) ?: return null
        val ownerName = CloudProviderRegistry.definition(owner)?.name ?: owner
        return "This looks like a $ownerName key. $providerName will reject it with a 401."
    }
}
