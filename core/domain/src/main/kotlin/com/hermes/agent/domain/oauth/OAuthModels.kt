package com.hermes.agent.domain.oauth

import kotlinx.serialization.Serializable

@Serializable
data class OAuthSession(
    val providerId: String,
    val state: String,
    val codeVerifier: String,
    val redirectUri: String,
    val timestamp: Long = System.currentTimeMillis(),
)

@Serializable
data class OAuthExchangeResult(
    val providerId: String,
    val apiKey: String,
    val rawResponse: String = "",
)
