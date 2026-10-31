package com.hermes.agent.data.llm

import java.time.ZoneOffset
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderQuotaTest {

    private val now = 1_800_000_000_000L

    @After
    fun tearDown() = ProviderQuota.clear()

    @Test
    fun `groq style headers give the lowest share and its reset`() {
        val h = mapOf(
            "x-ratelimit-remaining-requests" to "13", "x-ratelimit-limit-requests" to "14400",
            "x-ratelimit-remaining-tokens" to "1020", "x-ratelimit-limit-tokens" to "6000",
            "x-ratelimit-reset-tokens" to "4m0s",
        )
        val q = ProviderQuota.parse({ h[it] }, now)!!
        assertEquals(0, q.percentLeft) // 13 of 14,400 requests is the tightest
        val tokensOnly = ProviderQuota.parse({ mapOf("x-ratelimit-remaining-tokens" to "1020", "x-ratelimit-limit-tokens" to "6000", "x-ratelimit-reset-tokens" to "4m0s")[it] }, now)!!
        assertEquals(17, tokensOnly.percentLeft)
        assertEquals(now + 240_000, tokensOnly.resetsAtMillis)
    }

    @Test
    fun `no limit headers means no snapshot`() {
        assertNull(ProviderQuota.parse({ null }, now))
        assertNull(ProviderQuota.parse({ mapOf("x-ratelimit-remaining-requests" to "5")[it] }, now))
    }

    @Test
    fun `label reads like the upstream chip`() {
        val label = QuotaSnapshot(17, now + 240_000, now).label(ZoneOffset.UTC)
        assertTrue(label, label.startsWith("17% left · resets "))
        assertEquals("40% left", QuotaSnapshot(40, null, now).label())
    }

    @Test
    fun `the interceptor records by host and leaves the response alone`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody("{\"ok\":true}")
                .addHeader("x-ratelimit-remaining-requests", "50")
                .addHeader("x-ratelimit-limit-requests", "100"),
        )
        server.start()
        try {
            val client = OkHttpClient.Builder().addNetworkInterceptor(ProviderQuotaInterceptor()).build()
            val body = client.newCall(Request.Builder().url(server.url("/v1/chat/completions")).build()).execute().use { it.body!!.string() }
            assertEquals("{\"ok\":true}", body)
            val q = ProviderQuota.forBaseUrl(server.url("/v1").toString())
            assertNotNull(q)
            assertEquals(50, q!!.percentLeft)
        } finally {
            server.shutdown()
        }
    }
}
