package com.hermes.agent.data.llm

/**
 * Splits a conversation into the turns sent verbatim and the older ones folded into the
 * [ConversationCompressor] brief, by size rather than by count.
 *
 * A fixed 20-message window treated twenty one-liners and twenty pasted documents the same; the
 * second overflowed small on-device models, which then cut the prompt blindly (upstream
 * hermes-agent: "compaction triggers on real token counts"). The newest turns are kept while they
 * fit [budgetTokens] and number at most [maxMessages]; the newest [minRecent] are always kept, so
 * the turn being answered is never summarised away.
 */
object ContextWindowPlanner {
    const val DEFAULT_BUDGET_TOKENS = 6_000
    const val DEFAULT_MAX_MESSAGES = 20

    data class Split<T>(val older: List<T>, val recent: List<T>)

    fun <T> split(
        messages: List<T>,
        tokensOf: (T) -> Int,
        budgetTokens: Int = DEFAULT_BUDGET_TOKENS,
        maxMessages: Int = DEFAULT_MAX_MESSAGES,
        minRecent: Int = 1,
    ): Split<T> {
        var used = 0
        var keep = 0
        for (i in messages.indices.reversed()) {
            if (keep >= maxMessages) break
            val cost = tokensOf(messages[i]).coerceAtLeast(1)
            if (keep >= minRecent && used + cost > budgetTokens) break
            used += cost
            keep++
        }
        return Split(messages.dropLast(keep), messages.takeLast(keep))
    }

    /** A stored token count when it is believable, otherwise characters / 4. */
    fun estimateTokens(storedTokens: Int, text: String): Int = maxOf(storedTokens, text.length / 4, 1)
}
