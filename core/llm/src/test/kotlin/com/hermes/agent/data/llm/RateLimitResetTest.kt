package com.hermes.agent.data.llm

import com.hermes.agent.domain.llm.LlmMessage
import com.hermes.agent.domain.llm.LlmProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import okhttp3.Headers.Companion.headersOf
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class RateLimitResetTest {

    private val now = 1_800_000_000_000L

    private fun http(code: Int, vararg headers: String, body: String = "{}"): HttpException =
        HttpException(
            Response.error<Any>(
                body.toResponseBody("application/json".toMediaType()),
                okhttp3.Response.Builder()
                    .code(code).message("x").protocol(okhttp3.Protocol.HTTP_1_1)
                    .request(okhttp3.Request.Builder().url("https://api.example/v1/chat/completions").build())
                    .headers(headersOf(*headers))
                    .build(),
            ),
        )

    @Test
    fun `retry-after in seconds`() {
        assertEquals(now + 30_000, RateLimitReset.retryAtMillis(http(429, "retry-after", "30"), now))
    }

    @Test
    fun `retry-after as an http date`() {
        val at = RateLimitReset.retryAtMillis(http(429, "Retry-After", "Fri, 15 Jan 2027 08:01:40 GMT"), now)
        assertEquals(java.time.ZonedDateTime.of(2027, 1, 15, 8, 1, 40, 0, ZoneOffset.UTC).toInstant().toEpochMilli(), at)
    }

    @Test
    fun `groq and openai style duration headers, latest wins`() {
        val at = RateLimitReset.retryAtMillis(
            http(429, "x-ratelimit-reset-requests", "2m59.56s", "x-ratelimit-reset-tokens", "6m0s"), now,
        )
        assertEquals(now + 360_000, at)
    }

    @Test
    fun `epoch seconds and iso instants`() {
        assertEquals((now / 1000 + 90) * 1000, RateLimitReset.retryAtMillis(http(429, "ratelimit-reset", "${now / 1000 + 90}"), now))
        val iso = java.time.Instant.ofEpochMilli(now + 120_000).toString()
        assertEquals(now + 120_000, RateLimitReset.retryAtMillis(http(429, "anthropic-ratelimit-requests-reset", iso), now))
    }

    @Test
    fun `try again text in the body when there are no headers`() {
        val failure = RuntimeException(
            "HTTP 429: {\"error\":{\"message\":\"Rate limit reached. Please try again in 2m30s.\"}}",
            http(429),
        )
        assertEquals(now + 150_000, RateLimitReset.retryAtMillis(failure, now))
        assertEquals(now + 20_000, RateLimitReset.retryAtMillis(IllegalStateException("rate limit hit, retry after 20 seconds"), now))
    }

    @Test
    fun `nothing for errors that are not rate limits, or for past and far-future times`() {
        assertNull(RateLimitReset.retryAtMillis(http(500, "retry-after", "30"), now))
        assertNull(RateLimitReset.retryAtMillis(http(429), now))
        assertNull(RateLimitReset.retryAtMillis(http(429, "retry-after", "0"), now))
        assertNull(RateLimitReset.retryAtMillis(http(429, "retry-after", "${3 * 24 * 3600}"), now))
    }

    @Test
    fun `describe names the provider and the wait`() {
        val text = RateLimitReset.describe("Groq", now + 4 * 60_000, now, ZoneOffset.UTC)
        assertTrue(text, text.startsWith("Groq is rate-limited until "))
        assertTrue(text, text.endsWith("(in about 4 min)."))
    }

    private fun provider(providerName: String): LlmProvider = mockk(relaxed = true) {
        every { name } returns providerName
        every { model } returns "m"
        every { isOnDevice } returns false
        coEvery { isAvailable() } returns true
    }

    @Test
    fun `when every provider is rate-limited the chain says which frees up first`() = runTest {
        val groq = provider("Groq")
        val gemini = provider("Gemini")
        coEvery { groq.complete(any()) } throws RuntimeException("HTTP 429", http(429, "retry-after", "600"))
        coEvery { gemini.complete(any()) } throws RuntimeException("HTTP 429", http(429, "retry-after", "120"))
        try {
            RoutedProviderChain(listOf(groq, gemini)).complete(listOf(LlmMessage("user", "hi")))
            fail("expected a rate-limit failure")
        } catch (e: RateLimitedException) {
            assertTrue(e.message!!, e.message!!.startsWith("Gemini is rate-limited until"))
            val wait = e.retryAtMillis - System.currentTimeMillis()
            assertTrue("soonest reset should be about 2 min, was ${wait}ms", wait in 100_000..125_000)
        }
    }

    @Test
    fun `a chain failure with no reset time is unchanged`() = runTest {
        val down = provider("Down")
        coEvery { down.complete(any()) } throws java.io.IOException("connection reset")
        try {
            RoutedProviderChain(listOf(down)).complete(listOf(LlmMessage("user", "hi")))
            fail("expected a failure")
        } catch (e: java.io.IOException) {
            assertTrue("must not be dressed up as a rate limit", e !is RateLimitedException)
        }
    }
}
