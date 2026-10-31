package com.hermes.agent.data.llm

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.ConcurrentHashMap
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * How much of a provider's rate limit is left, read from the headers it sends on every response
 * (upstream hermes-agent: the model picker's "17% left · resets 3:00 PM"). Kept per host for the
 * life of the process; nothing is stored.
 */
data class QuotaSnapshot(
    /** Lowest remaining share across the request and token limits the provider reports, 0..100. */
    val percentLeft: Int,
    /** When that limit resets, or null if the provider did not say. */
    val resetsAtMillis: Long?,
    val observedAtMillis: Long,
) {
    /** "17% left · resets 3:05 PM", or just "17% left". */
    fun label(zone: ZoneId = ZoneId.systemDefault()): String {
        val reset = resetsAtMillis?.let {
            " · resets " + DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(Instant.ofEpochMilli(it).atZone(zone))
        }.orEmpty()
        return "$percentLeft% left$reset"
    }
}

object ProviderQuota {
    private val byHost = ConcurrentHashMap<String, QuotaSnapshot>()

    /** The latest snapshot for the provider at [baseUrl], or null when it never sent limit headers. */
    fun forBaseUrl(baseUrl: String): QuotaSnapshot? =
        baseUrl.trim().toHttpUrlOrNull()?.host?.let { byHost[it] }

    internal fun record(host: String, snapshot: QuotaSnapshot) { byHost[host] = snapshot }

    internal fun clear() = byHost.clear()

    /**
     * The snapshot carried by [headerValue] lookups (case-insensitive header names), or null when
     * the response has no remaining/limit pair. Pairs: `x-ratelimit-remaining-requests` with
     * `x-ratelimit-limit-requests` (and the `-tokens` pair), Anthropic's
     * `anthropic-ratelimit-requests-remaining/-limit`, and the generic `x-ratelimit-remaining`.
     */
    fun parse(headerValue: (String) -> String?, now: Long = System.currentTimeMillis()): QuotaSnapshot? {
        val pairs = listOf(
            Triple("x-ratelimit-remaining-requests", "x-ratelimit-limit-requests", "x-ratelimit-reset-requests"),
            Triple("x-ratelimit-remaining-tokens", "x-ratelimit-limit-tokens", "x-ratelimit-reset-tokens"),
            Triple("anthropic-ratelimit-requests-remaining", "anthropic-ratelimit-requests-limit", "anthropic-ratelimit-requests-reset"),
            Triple("anthropic-ratelimit-tokens-remaining", "anthropic-ratelimit-tokens-limit", "anthropic-ratelimit-tokens-reset"),
            Triple("x-ratelimit-remaining", "x-ratelimit-limit", "x-ratelimit-reset"),
        )
        var lowest: Pair<Int, Long?>? = null
        for ((remainingName, limitName, resetName) in pairs) {
            val remaining = headerValue(remainingName)?.trim()?.toDoubleOrNull() ?: continue
            val limit = headerValue(limitName)?.trim()?.toDoubleOrNull()?.takeIf { it > 0 } ?: continue
            val percent = ((remaining / limit) * 100).toInt().coerceIn(0, 100)
            val reset = headerValue(resetName)?.let { RateLimitReset.resetHeaderMillis(it, now) }
            if (lowest == null || percent < lowest.first) lowest = percent to reset
        }
        return lowest?.let { (percent, reset) -> QuotaSnapshot(percent, reset, now) }
    }
}

/** Records [ProviderQuota] from every response that carries rate-limit headers; passes the response through untouched. */
class ProviderQuotaInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        ProviderQuota.parse({ response.header(it) })?.let { ProviderQuota.record(chain.request().url.host, it) }
        return response
    }
}
