package com.hermes.agent.domain.usage

import java.math.BigDecimal
import java.math.RoundingMode

data class PricingTier(
    val promptCostPerMillion: Double,
    val completionCostPerMillion: Double,
    val cacheReadCostPerMillion: Double = 0.0,
)

data class CostEstimate(
    val amountUsd: Double,
    val isZeroCost: Boolean,
    val matchedModel: String,
    val promptRatePerMillion: Double,
    val completionRatePerMillion: Double,
)

/**
 * Computes estimated costs in USD based on model family rates.
 * Ported from upstream `usage_pricing.py`.
 */
object UsagePricingEngine {

    private val MODEL_RATES = mapOf(
        // Anthropic
        "claude-3-7-sonnet" to PricingTier(3.00, 15.00, 0.30),
        "claude-3-5-sonnet" to PricingTier(3.00, 15.00, 0.30),
        "claude-3-5-haiku" to PricingTier(0.80, 4.00, 0.08),
        "claude-3-opus" to PricingTier(15.00, 75.00, 1.50),
        "claude-3-sonnet" to PricingTier(3.00, 15.00, 0.30),
        "claude-3-haiku" to PricingTier(0.25, 1.25, 0.025),

        // OpenAI
        "gpt-4o" to PricingTier(2.50, 10.00, 1.25),
        "gpt-4o-mini" to PricingTier(0.15, 0.60, 0.075),
        "gpt-4-turbo" to PricingTier(10.00, 30.00),
        "gpt-4" to PricingTier(30.00, 60.00),
        "gpt-3.5-turbo" to PricingTier(0.50, 1.50),
        "o1" to PricingTier(15.00, 60.00, 7.50),
        "o1-mini" to PricingTier(3.00, 12.00, 1.50),
        "o3-mini" to PricingTier(1.10, 4.40, 0.55),

        // Google Gemini
        "gemini-2.0-flash" to PricingTier(0.10, 0.40, 0.025),
        "gemini-1.5-pro" to PricingTier(1.25, 5.00, 0.3125),
        "gemini-1.5-flash" to PricingTier(0.075, 0.30, 0.01875),
        "gemini-1.5-flash-8b" to PricingTier(0.0375, 0.15, 0.01),

        // DeepSeek
        "deepseek-chat" to PricingTier(0.14, 0.28, 0.014),
        "deepseek-v3" to PricingTier(0.14, 0.28, 0.014),
        "deepseek-reasoner" to PricingTier(0.55, 2.19, 0.14),
        "deepseek-r1" to PricingTier(0.55, 2.19, 0.14),

        // Groq / OpenRouter open models
        "llama-3.3-70b-versatile" to PricingTier(0.59, 0.79),
        "llama-3.1-8b-instant" to PricingTier(0.05, 0.08),
        "mixtral-8x7b-32768" to PricingTier(0.24, 0.24),
    )

    private val ZERO_TIER = PricingTier(0.0, 0.0, 0.0)
    private val DEFAULT_FALLBACK_TIER = PricingTier(1.00, 3.00)

    fun resolveTier(modelName: String, isOnDevice: Boolean = false): Pair<String, PricingTier> {
        if (isOnDevice) return "on-device-llama" to ZERO_TIER
        val normalized = modelName.trim().lowercase()

        if (normalized.contains("ollama") || normalized.contains("local") || normalized.contains("llama.cpp")) {
            return "local-zero-cost" to ZERO_TIER
        }

        for ((key, tier) in MODEL_RATES) {
            if (normalized.contains(key)) {
                return key to tier
            }
        }

        // Fuzzy match common prefix families
        return when {
            normalized.startsWith("claude") -> "claude-fallback" to PricingTier(3.00, 15.00)
            normalized.startsWith("gpt-4") -> "gpt4-fallback" to PricingTier(2.50, 10.00)
            normalized.startsWith("gpt-3") -> "gpt3-fallback" to PricingTier(0.50, 1.50)
            normalized.startsWith("gemini") -> "gemini-fallback" to PricingTier(0.15, 0.60)
            normalized.startsWith("deepseek") -> "deepseek-fallback" to PricingTier(0.25, 0.80)
            normalized.startsWith("llama") || normalized.startsWith("qwen") || normalized.startsWith("mistral") ->
                "open-weights-cloud" to PricingTier(0.30, 0.60)
            else -> "unknown-cloud" to DEFAULT_FALLBACK_TIER
        }
    }

    fun calculateCost(
        modelName: String,
        promptTokens: Long,
        completionTokens: Long,
        isOnDevice: Boolean = false,
    ): CostEstimate {
        val (matched, tier) = resolveTier(modelName, isOnDevice)
        if (tier.promptCostPerMillion == 0.0 && tier.completionCostPerMillion == 0.0) {
            return CostEstimate(
                amountUsd = 0.0,
                isZeroCost = true,
                matchedModel = matched,
                promptRatePerMillion = 0.0,
                completionRatePerMillion = 0.0,
            )
        }

        val promptCost = (promptTokens.toDouble() / 1_000_000.0) * tier.promptCostPerMillion
        val completionCost = (completionTokens.toDouble() / 1_000_000.0) * tier.completionCostPerMillion
        val total = BigDecimal(promptCost + completionCost)
            .setScale(6, RoundingMode.HALF_UP)
            .toDouble()

        return CostEstimate(
            amountUsd = total,
            isZeroCost = false,
            matchedModel = matched,
            promptRatePerMillion = tier.promptCostPerMillion,
            completionRatePerMillion = tier.completionCostPerMillion,
        )
    }
}
