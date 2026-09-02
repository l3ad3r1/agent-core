package com.hermes.agent.domain.settings

import kotlinx.serialization.Serializable

/** One OpenAI-compatible provider available to the model router. */
@Serializable
data class CloudProviderProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val modelAutoSelected: Boolean = true,
    val apiKey: String,
    val enabled: Boolean = true,
    val quality: Double,
    val cost: Double,
    val latency: Double,
    val toolReliability: Double,
    val supportsVision: Boolean = false,
    /**
     * Per-provider reasoning / thinking effort for o-series and extended-thinking
     * models: `minimal` | `low` | `medium` | `high`. Blank means "inherit the
     * global [UserSettings.reasoningEffort]". `medium` is the API default and is
     * never sent on the wire.
     */
    val reasoningEffort: String = "",
)
