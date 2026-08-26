package com.hermes.agent.data.oauth

import com.hermes.agent.domain.oauth.OAuthSession
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class OAuthManagerTest {

    @Test
    fun `generateCodeVerifier creates valid base64url string without padding`() {
        val manager = OAuthManager()
        val verifier = manager.generateCodeVerifier()
        assertTrue(verifier.isNotBlank())
        assertFalse(verifier.contains("="))
        assertFalse(verifier.contains("+"))
        assertFalse(verifier.contains("/"))
    }

    @Test
    fun `generateCodeChallenge creates expected S256 challenge`() {
        val manager = OAuthManager()
        // Known verifier test
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val challenge = manager.generateCodeChallenge(verifier)
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", challenge)
    }

    @Test
    fun `buildAuthorizationUrl creates correct OpenRouter url and session`() {
        val manager = OAuthManager()
        val (url, session) = manager.buildAuthorizationUrl("openrouter", "hermes://oauth/callback")

        assertEquals("openrouter", session.providerId)
        assertEquals("hermes://oauth/callback", session.redirectUri)
        assertTrue(url.startsWith("https://openrouter.ai/auth?"))
        assertTrue(url.contains("callback_url=hermes%3A%2F%2Foauth%2Fcallback"))
        assertTrue(url.contains("code_challenge="))
        assertTrue(url.contains("code_challenge_method=S256"))
    }

    @Test
    fun `buildAuthorizationUrl creates correct Nous url and session`() {
        val manager = OAuthManager()
        val (url, session) = manager.buildAuthorizationUrl("nous", "hermes://oauth/callback")

        assertEquals("nous", session.providerId)
        assertEquals("hermes://oauth/callback", session.redirectUri)
        assertTrue(url.startsWith("https://portal.nousresearch.com/oauth/authorize?"))
        assertTrue(url.contains("client_id=hermes-agent"))
        assertTrue(url.contains("state=${session.state}"))
        assertTrue(url.contains("code_challenge="))
    }

    @Test
    fun `exchangeCodeForApiKey succeeds for OpenRouter`() = runTest {
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"key": "sk-or-v1-mock-api-key-12345"}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val manager = OAuthManager(httpClient = mockClient)
        val session = OAuthSession(
            providerId = "openrouter",
            state = "mock-state",
            codeVerifier = "mock-verifier",
            redirectUri = "hermes://oauth/callback",
        )

        val result = manager.exchangeCodeForApiKey(session, "auth-code-123")
        assertTrue(result.isSuccess)
        val exchange = result.getOrThrow()
        assertEquals("openrouter", exchange.providerId)
        assertEquals("sk-or-v1-mock-api-key-12345", exchange.apiKey)
    }

    @Test
    fun `exchangeCodeForApiKey succeeds for Nous`() = runTest {
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"access_token": "nous-access-token-999"}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val manager = OAuthManager(httpClient = mockClient)
        val session = OAuthSession(
            providerId = "nous",
            state = "mock-state",
            codeVerifier = "mock-verifier",
            redirectUri = "hermes://oauth/callback",
        )

        val result = manager.exchangeCodeForApiKey(session, "auth-code-456")
        assertTrue(result.isSuccess)
        val exchange = result.getOrThrow()
        assertEquals("nous", exchange.providerId)
        assertEquals("nous-access-token-999", exchange.apiKey)
    }

    @Test
    fun `exchangeCodeForApiKey returns failure on HTTP error`() = runTest {
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(401)
                    .message("Unauthorized")
                    .body("""{"error": "invalid_grant"}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val manager = OAuthManager(httpClient = mockClient)
        val session = OAuthSession(
            providerId = "openrouter",
            state = "mock-state",
            codeVerifier = "mock-verifier",
            redirectUri = "hermes://oauth/callback",
        )

        val result = manager.exchangeCodeForApiKey(session, "bad-code")
        assertFalse(result.isSuccess)
    }
}
