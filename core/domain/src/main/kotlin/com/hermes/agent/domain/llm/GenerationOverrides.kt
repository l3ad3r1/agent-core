package com.hermes.agent.domain.llm

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Sampling settings for one run, such as a conversation's own temperature and reply length.
 *
 * Carried in the coroutine context rather than through [LlmProvider]'s signatures, so a run sets
 * them once (`withContext(GenerationOverrides(...))`) and every provider call inside it, including
 * the tool loop and any fallback, sees them. A provider that cannot apply a setting ignores it;
 * the on-device engine samples with its own fixed settings. Null means "the provider's default".
 */
class GenerationOverrides(
    val temperature: Double? = null,
    val maxTokens: Int? = null,
) : AbstractCoroutineContextElement(Key) {

    companion object Key : CoroutineContext.Key<GenerationOverrides> {
        const val MIN_TEMPERATURE = 0.0
        const val MAX_TEMPERATURE = 2.0
        const val MIN_MAX_TOKENS = 16

        /** Null when nothing is set, so callers can skip adding an empty element. */
        fun of(temperature: Double?, maxTokens: Int?): GenerationOverrides? {
            val t = temperature?.coerceIn(MIN_TEMPERATURE, MAX_TEMPERATURE)
            val m = maxTokens?.coerceAtLeast(MIN_MAX_TOKENS)
            return if (t == null && m == null) null else GenerationOverrides(t, m)
        }
    }

    val isEmpty: Boolean get() = temperature == null && maxTokens == null

    /**
     * The OpenAI-compatible request fields for these settings, each followed by a comma, for a
     * request body built by hand: `"temperature":0.3,"max_tokens":800,`. Empty when nothing is set.
     */
    fun jsonFields(): String = buildString {
        temperature?.let { append("\"temperature\":").append(it).append(',') }
        maxTokens?.let { append("\"max_tokens\":").append(it).append(',') }
    }
}
