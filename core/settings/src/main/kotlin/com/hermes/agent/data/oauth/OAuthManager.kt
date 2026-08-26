package com.hermes.agent.data.oauth

import com.hermes.agent.domain.oauth.OAuthExchangeResult
import com.hermes.agent.domain.oauth.OAuthSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OAuthManager @Inject constructor(
    private val httpClient: OkHttpClient,
) {

    constructor() : this(
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build(),
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val secureRandom = SecureRandom()

    companion object {
        const val PROVIDER_OPENROUTER = "openrouter"
        const val PROVIDER_NOUS = "nous"

        const val DEFAULT_CALLBACK_SCHEME = "hermes://oauth/callback"
        const val HTTPS_CALLBACK_URL = "https://agent.hermes/oauth/callback"
    }

    /**
     * Generate cryptographically secure PKCE code verifier (RFC 7636).
     */
    fun generateCodeVerifier(): String {
        val bytes = ByteArray(48)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * Generate SHA-256 code challenge from code verifier (RFC 7636).
     */
    fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
    }

    /**
     * Generate secure random state token to protect against CSRF.
     */
    fun generateState(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Build the authorization URL and state session for the given provider.
     */
    fun buildAuthorizationUrl(
        providerId: String,
        redirectUri: String = DEFAULT_CALLBACK_SCHEME,
    ): Pair<String, OAuthSession> {
        val verifier = generateCodeVerifier()
        val challenge = generateCodeChallenge(verifier)
        val state = generateState()

        val session = OAuthSession(
            providerId = providerId,
            state = state,
            codeVerifier = verifier,
            redirectUri = redirectUri,
        )

        val encodedRedirect = URLEncoder.encode(redirectUri, "UTF-8")

        val authUrl = when (providerId) {
            PROVIDER_OPENROUTER -> {
                "https://openrouter.ai/auth?callback_url=$encodedRedirect&code_challenge=$challenge&code_challenge_method=S256"
            }
            PROVIDER_NOUS -> {
                "https://portal.nousresearch.com/oauth/authorize?client_id=hermes-agent&response_type=code&redirect_uri=$encodedRedirect&code_challenge=$challenge&code_challenge_method=S256&state=$state"
            }
            else -> throw IllegalArgumentException("Unsupported OAuth provider: $providerId")
        }

        return authUrl to session
    }

    /**
     * Exchange an authorization code for an API key or access token.
     */
    suspend fun exchangeCodeForApiKey(
        session: OAuthSession,
        code: String,
    ): Result<OAuthExchangeResult> = withContext(Dispatchers.IO) {
        runCatching {
            when (session.providerId) {
                PROVIDER_OPENROUTER -> exchangeOpenRouter(session, code)
                PROVIDER_NOUS -> exchangeNous(session, code)
                else -> throw IllegalArgumentException("Unsupported OAuth provider: ${session.providerId}")
            }
        }
    }

    private fun exchangeOpenRouter(session: OAuthSession, code: String): OAuthExchangeResult {
        val url = "https://openrouter.ai/api/v1/auth/keys"
        val payload = """
            {
                "code": "$code",
                "code_verifier": "${session.codeVerifier}",
                "code_challenge_method": "S256"
            }
        """.trimIndent()

        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string().orEmpty()

        if (!response.isSuccessful) {
            throw IllegalStateException("OpenRouter token exchange failed (HTTP ${response.code}): $responseBody")
        }

        val jsonElement = json.parseToJsonElement(responseBody)
        val apiKey = jsonElement.jsonObject["key"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("OpenRouter response did not contain 'key': $responseBody")

        return OAuthExchangeResult(
            providerId = PROVIDER_OPENROUTER,
            apiKey = apiKey,
            rawResponse = responseBody,
        )
    }

    private fun exchangeNous(session: OAuthSession, code: String): OAuthExchangeResult {
        val url = "https://portal.nousresearch.com/oauth/token"
        val payload = """
            {
                "client_id": "hermes-agent",
                "grant_type": "authorization_code",
                "code": "$code",
                "code_verifier": "${session.codeVerifier}",
                "redirect_uri": "${session.redirectUri}"
            }
        """.trimIndent()

        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string().orEmpty()

        if (!response.isSuccessful) {
            throw IllegalStateException("Nous token exchange failed (HTTP ${response.code}): $responseBody")
        }

        val jsonElement = json.parseToJsonElement(responseBody)
        val apiKey = jsonElement.jsonObject["access_token"]?.jsonPrimitive?.content
            ?: jsonElement.jsonObject["api_key"]?.jsonPrimitive?.content
            ?: jsonElement.jsonObject["key"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("Nous response did not contain access token or key: $responseBody")

        return OAuthExchangeResult(
            providerId = PROVIDER_NOUS,
            apiKey = apiKey,
            rawResponse = responseBody,
        )
    }
}
