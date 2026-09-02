package com.hermes.agent.data.llm

/**
 * Per-model timeout floor for known reasoning models.
 *
 * Ported (trimmed) from `agent/reasoning_timeouts.py` in the upstream Python
 * hermes-agent. Reasoning models emit an extended thinking phase before their
 * first content token and routinely exceed a chat-model timeout — o1/o3,
 * DeepSeek R1, Nemotron reasoning, QwQ / Qwen3-thinking, Claude 4.x/5 thinking,
 * Grok reasoning. [RoutedProviderChain]'s default 30 s per-attempt cap kills
 * them mid-think and fails over to a worse model.
 *
 * This is a FLOOR: callers apply `max(default, floor)`, it never lowers a
 * threshold, and it returns null for every non-reasoning model.
 *
 * ponytail: the upstream table has ~20 entries with a start-of-slug regex for
 * CLI stale-stream detectors this app doesn't have. Kept the ~10 model
 * families that actually reason and a plain prefix match. Add an entry if a
 * new reasoning model keeps getting failed-over.
 */
internal object ReasoningStaleTimeout {

    /** slug → floor in milliseconds. Longest slug wins on overlap. */
    private val FLOORS: List<Pair<String, Long>> = listOf(
        "o1-mini" to 600_000L,
        "o1-pro" to 600_000L,
        "o1-preview" to 600_000L,
        "o1" to 600_000L,
        "o3-mini" to 300_000L,
        "o3-pro" to 600_000L,
        "o3" to 600_000L,
        "o4-mini" to 300_000L,
        "deepseek-r1" to 600_000L,
        "deepseek-reasoner" to 600_000L,
        "deepseek-v4-pro" to 600_000L,
        "deepseek-v4-flash" to 600_000L,
        "nemotron-3-ultra" to 600_000L,
        "nemotron-3-super" to 600_000L,
        "nemotron-3-nano" to 300_000L,
        "nemotron" to 300_000L,
        "qwq" to 300_000L,
        "qwen3" to 180_000L,
        "claude-opus-4" to 240_000L,
        "claude-opus-5" to 240_000L,
        "claude-sonnet-4.5" to 180_000L,
        "claude-sonnet-5" to 180_000L,
        "claude-fable" to 600_000L,
        "grok-4-fast-reasoning" to 300_000L,
        "grok-4.5" to 300_000L,
        "grok-4.6" to 300_000L,
        "gemini-2.5-pro" to 240_000L,
    ).sortedByDescending { it.first.length }

    /**
     * Timeout floor (ms) for [model], or null when it is not a known reasoning
     * model. The aggregator prefix (`openai/`, `x-ai/`, `anthropic/`) is
     * stripped first, then each slug is matched at the start of the remaining
     * name with a `-` / `.` / `_` / `:` / end-of-string boundary on the right.
     */
    fun floorMillis(model: String?): Long? {
        val name = model?.trim()?.lowercase()?.substringAfterLast('/') ?: return null
        if (name.isEmpty()) return null
        return FLOORS.firstOrNull { (slug, _) ->
            name == slug ||
                name.startsWith("$slug-") ||
                name.startsWith("$slug.") ||
                name.startsWith("${slug}_") ||
                name.startsWith("$slug:")
        }?.second
    }
}
