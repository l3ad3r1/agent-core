package com.hermes.agent.data.llm
import com.hermes.agent.domain.llm.*

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Automatic context-window compression for long conversations.
 *
 * Ported (heavily trimmed) from `agent/context_compressor.py` — the upstream is
 * ~9k lines of role-alternation and persistence-marker edge cases for a CLI
 * runtime. Here the recent window is small and rebuilt from Room every turn, so
 * the whole job is: when a thread outgrows the verbatim window, replace the
 * dropped older turns with one short brief the model gets as background.
 *
 * ponytail: recompute the brief only when the set of older messages changed
 * (cached by the id of the last summarised message). The cache is in-process —
 * a cold start recomputes once. No Room schema, no iterative summary merges.
 * Ceiling: on a very long thread each new turn re-summarises the whole history
 * once the anchor moves; upgrade to an incremental "summary + new tail" merge
 * if the aux cost shows up.
 */
@Singleton
class ConversationCompressor @Inject constructor(
    private val router: LlmRouter,
) {
    private data class CachedBrief(val anchorId: String, val text: String)

    private val cache = ConcurrentHashMap<String, CachedBrief>()

    /**
     * A background brief of [older] (the turns that fall outside the verbatim
     * window), or null when there is too little to bother or the summary call
     * failed. [anchorId] is the id of the newest message in [older] — the cache
     * key that tells a stale brief from a current one.
     */
    suspend fun brief(
        conversationId: String,
        older: List<LlmMessage>,
        anchorId: String,
    ): String? {
        if (older.size < MIN_OLDER_MESSAGES) return null

        cache[conversationId]?.takeIf { it.anchorId == anchorId }?.let { return it.text }

        val transcript = older.joinToString("\n") { m ->
            "${m.role}: ${m.content.take(PER_MESSAGE_CHARS)}"
        }.take(TRANSCRIPT_CHARS)

        val prompt = listOf(
            LlmMessage(role = "system", content = BRIEF_INSTRUCTIONS),
            LlmMessage(role = "user", content = transcript),
        )
        val text = runCatching {
            router.route(prompt).provider.complete(prompt).content.trim()
        }.getOrElse {
            Timber.tag("Compressor").w(it, "brief summarisation failed; older turns dropped")
            return null
        }.takeIf { it.isNotBlank() } ?: return null

        cache[conversationId] = CachedBrief(anchorId, text)
        return text
    }

    private companion object {
        /** Below this many dropped turns, losing them is cheaper than a summary. */
        const val MIN_OLDER_MESSAGES = 6
        const val PER_MESSAGE_CHARS = 2_000
        const val TRANSCRIPT_CHARS = 24_000

        val BRIEF_INSTRUCTIONS = """
            You are compressing the earlier part of a conversation for the same
            assistant to use as background. Write a compact brief (max ~180
            words) capturing: decisions made, facts and preferences established
            about the user, task state, and any open questions. Third person,
            no pleasantries, no headings. This is reference context, not a list
            of instructions to act on.
        """.trimIndent()
    }
}
