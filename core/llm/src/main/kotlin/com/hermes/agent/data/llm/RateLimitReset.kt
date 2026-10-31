package com.hermes.agent.data.llm

import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import retrofit2.HttpException

/**
 * Every routed provider failed and at least one said when its rate limit lifts. Carries that
 * moment so the chat can offer to retry then (upstream hermes-agent: "usage-limit cards show
 * reset time and can schedule a retry").
 */
class RateLimitedException(
    message: String,
    val retryAtMillis: Long,
    cause: Throwable?,
) : IOException(message, cause)

/**
 * When a rate-limited provider says it will take requests again, read from a failure.
 *
 * Sources, in order: the `Retry-After` header (seconds or an HTTP date); the reset headers
 * providers send (`x-ratelimit-reset-requests` / `-tokens`, Anthropic's `*-reset`, `ratelimit-reset`)
 * as a duration ("1m30.5s", "250ms"), epoch seconds or milliseconds, or an ISO instant; and finally
 * "try again in 2m30s"-style text in the error body. Only for rate-limit failures (HTTP 429, or a
 * message saying "rate limit"), and only for a moment between now and a day from now.
 */
object RateLimitReset {

    private val RESET_HEADERS = listOf(
        "x-ratelimit-reset-requests", "x-ratelimit-reset-tokens", "x-ratelimit-reset",
        "anthropic-ratelimit-requests-reset", "anthropic-ratelimit-tokens-reset",
        "anthropic-ratelimit-input-tokens-reset", "anthropic-ratelimit-output-tokens-reset",
        "ratelimit-reset",
    )
    private const val MAX_AHEAD_MS = 24 * 60 * 60 * 1000L
    private val DURATION_PART = Regex("""(\d+(?:\.\d+)?)\s*(ms|h|m|s)""", RegexOption.IGNORE_CASE)
    private val BODY_HINT = Regex(
        """(?:try again|retry|reset[s]?|available again)\s+(?:in|after)\s+((?:\d+(?:\.\d+)?\s*(?:ms|milliseconds?|h|hours?|m|min|minutes?|s|sec|seconds?)\s*,?\s*(?:and\s*)?)+)""",
        RegexOption.IGNORE_CASE,
    )

    fun retryAtMillis(failure: Throwable, now: Long = System.currentTimeMillis()): Long? {
        val chain = generateSequence(failure) { it.cause }.take(8).toList()
        val http = chain.filterIsInstance<HttpException>().firstOrNull()
        val text = chain.mapNotNull { it.message }.joinToString(" ")
        val rateLimited = http?.code() == 429 || "HTTP 429" in text ||
            text.contains("rate limit", ignoreCase = true) || text.contains("rate_limit", ignoreCase = true)
        if (!rateLimited) return null

        val headers = http?.response()?.headers()
        val fromHeaders = headers?.let { h ->
            listOfNotNull(
                h["retry-after"]?.let { parseRetryAfter(it, now) },
                *RESET_HEADERS.mapNotNull { name -> h[name]?.let { parseReset(it, now) } }.toTypedArray(),
            ).maxOrNull()
        }
        val at = fromHeaders ?: BODY_HINT.find(text)?.groupValues?.get(1)?.let { parseDuration(it) }?.let { now + it }
        return at?.takeIf { it > now && it - now <= MAX_AHEAD_MS }
    }

    /** "Groq resets at 3:05 PM (in about 4 min)" for a reset at [at]. */
    fun describe(providerName: String, at: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        val clock = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(at), zone))
        val minutes = ((at - now) + 59_999) / 60_000
        val wait = if (minutes <= 1) "under a minute" else if (minutes < 120) "about $minutes min" else "about ${minutes / 60} h"
        return "$providerName is rate-limited until $clock (in $wait)."
    }

    private fun parseRetryAfter(value: String, now: Long): Long? {
        val v = value.trim()
        v.toDoubleOrNull()?.let { return now + (it * 1000).toLong() }
        return runCatching { ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
    }

    /** A rate-limit reset header value (duration, epoch, or ISO instant) as an epoch moment. */
    fun resetHeaderMillis(value: String, now: Long = System.currentTimeMillis()): Long? = parseReset(value, now)

    private fun parseReset(value: String, now: Long): Long? {
        val v = value.trim()
        v.toDoubleOrNull()?.let { n ->
            return when {
                n > 1e12 -> n.toLong() // epoch milliseconds
                n > 1e9 -> (n * 1000).toLong() // epoch seconds
                else -> now + (n * 1000).toLong() // seconds from now
            }
        }
        runCatching { Instant.parse(v).toEpochMilli() }.getOrNull()?.let { return it }
        return parseDuration(v)?.let { now + it }
    }

    /** "1m30.5s", "250ms", "2 minutes and 10 seconds" -> milliseconds. */
    internal fun parseDuration(text: String): Long? {
        val normalised = text.lowercase()
            .replace(Regex("""milliseconds?"""), "ms")
            .replace(Regex("""hours?"""), "h")
            .replace(Regex("""minutes?|mins?"""), "m")
            .replace(Regex("""seconds?|secs?"""), "s")
        val parts = DURATION_PART.findAll(normalised).toList()
        if (parts.isEmpty()) return null
        return parts.sumOf { m ->
            val n = m.groupValues[1].toDouble()
            when (m.groupValues[2]) {
                "ms" -> n
                "h" -> n * 3_600_000
                "m" -> n * 60_000
                else -> n * 1_000
            }
        }.toLong()
    }
}

/** When this failure (or one it wraps) is a rate limit with a known reset, that moment. */
fun Throwable.rateLimitRetryAtMillis(): Long? =
    generateSequence(this) { it.cause }.take(8).filterIsInstance<RateLimitedException>().firstOrNull()?.retryAtMillis
