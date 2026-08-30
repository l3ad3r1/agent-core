package com.hermes.agent.domain.oauth

import kotlinx.serialization.Serializable

@Serializable
data class OAuthSession(
    val providerId: String,
    val state: String,
    val codeVerifier: String,
    val redirectUri: String,
    val timestamp: Long = System.currentTimeMillis(),
    /**
     * Whether [state] was actually put on the authorization URL.
     *
     * Not every provider takes one — OpenRouter's `/auth` endpoint has no
     * `state` parameter — and the callback check needs to tell "this provider
     * never echoes a state" apart from "a state went missing", so that an
     * attacker cannot defeat the check simply by omitting the parameter.
     */
    val stateSent: Boolean = true,
)

@Serializable
data class OAuthExchangeResult(
    val providerId: String,
    val apiKey: String,
    val rawResponse: String = "",
)
