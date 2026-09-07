package com.hermes.agent.data.llm
import com.hermes.agent.domain.llm.*

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.withContext
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
 * The brief is maintained incrementally. A cached brief covers the first N of
 * [older]; turns that have dropped out of the verbatim window since are carried
 * **verbatim** as a tail alongside it, and only folded in — by summarising the
 * brief plus that tail, never the whole thread again — once the tail is big
 * enough to be worth an inference.
 *
 * That shape is what makes this affordable on-device. Re-summarising every turn
 * cost a whole extra local inference, and because the brief's prompt and the
 * chat prompt share almost no prefix it also evicted the chat model's KV cache,
 * leaving every chat prefill cold. Carrying the tail verbatim keeps the context
 * complete between merges without an inference, so nothing is forgotten and the
 * summary cost is paid once per [MERGE_AFTER_MESSAGES] turns rather than every
 * turn — and each merge is bounded by the tail, not by thread length.
 *
 * The cache is in-process; a cold start rebuilds once. No Room schema.
 */
@Singleton
class ConversationCompressor @Inject constructor(
    private val router: LlmRouter,
) {
    private data class CachedBrief(
        /** How many of `older`, from the start, this brief text actually covers. */
        val coveredCount: Int,
        val text: String,
    )

    private val cache = ConcurrentHashMap<String, CachedBrief>()

    /**
     * Background context for [older] — the turns that fall outside the verbatim
     * window — or null when there is too little to bother and the first
     * summarisation has not happened yet.
     *
     * The result is the cached brief plus any turns it does not yet cover,
     * quoted verbatim, so it always describes the whole of [older] even on the
     * turns that do no inference at all.
     */
    suspend fun brief(
        conversationId: String,
        older: List<LlmMessage>,
    ): String? {
        if (older.size < MIN_OLDER_MESSAGES) return null

        // A brief claiming to cover more than exists belongs to a thread that has
        // since been trimmed or cleared; it cannot be reconciled, so start over.
        val cached = cache[conversationId]?.takeIf { it.coveredCount <= older.size }
        val tail = older.drop(cached?.coveredCount ?: 0)

        if (tail.isEmpty()) return cached?.text

        val renderedTail = render(tail)
        // Too little new material to be worth an inference. Hand back the brief
        // with the new turns attached verbatim: complete, and free.
        if (cached != null &&
            tail.size < MERGE_AFTER_MESSAGES &&
            renderedTail.length < TAIL_CHARS
        ) {
            return cached.text + "\n\n" + renderedTail
        }

        // Fold the tail in. With a brief in hand the model sees it plus only the
        // new turns, so this stays bounded however long the thread gets; without
        // one this is the first summarisation and the tail is all of `older`.
        val instructions = if (cached != null) MERGE_INSTRUCTIONS else BRIEF_INSTRUCTIONS
        val body = if (cached != null) {
            "## Current brief\n${cached.text}\n\n## Turns since\n$renderedTail"
        } else {
            renderedTail
        }
        val prompt = listOf(
            LlmMessage(role = "system", content = instructions),
            LlmMessage(role = "user", content = body.take(TRANSCRIPT_CHARS)),
        )
        val text = runCatching {
            // Background work: on the local engine this keeps the summary off the
            // conversation's KV lane, so the next chat turn still finds its
            // prefix cached. Providers that do not care simply ignore it.
            withContext(AuxiliaryInference) {
                router.route(prompt).provider.complete(prompt).content.trim()
            }
        }.getOrElse {
            Timber.tag("Compressor").w(it, "brief merge failed; falling back to what is cached")
            // Never drop context on a failed call: the previous brief plus the
            // verbatim tail is still complete, just not compressed.
            return cached?.let { it.text + "\n\n" + renderedTail }
        }.takeIf { it.isNotBlank() }
            ?: return cached?.let { it.text + "\n\n" + renderedTail }

        cache[conversationId] = CachedBrief(older.size, text)
        return text
    }

    private fun render(messages: List<LlmMessage>): String =
        messages.joinToString("\n") { m -> "${m.role}: ${m.content.take(PER_MESSAGE_CHARS)}" }

    private companion object {
        /** Below this many dropped turns, losing them is cheaper than a summary. */
        const val MIN_OLDER_MESSAGES = 6

        /**
         * How many uncovered turns may ride along verbatim before they are
         * folded into the brief.
         *
         * This is a cost knob, not a forgetting knob — the tail is in the prompt
         * either way, so nothing is lost by waiting. Raising it summarises less
         * often but leaves more un-compressed text in every prompt.
         */
        const val MERGE_AFTER_MESSAGES = 6

        /** Merge early when the verbatim tail gets long, whatever its count. */
        const val TAIL_CHARS = 2_000
        const val PER_MESSAGE_CHARS = 2_000
        const val TRANSCRIPT_CHARS = 24_000

        val MERGE_INSTRUCTIONS = """
            You are maintaining a running brief of the earlier part of a
            conversation for the same assistant to use as background. You are
            given the current brief and the turns that have happened since.
            Rewrite it so it covers both, in at most ~180 words: decisions made,
            facts and preferences established about the user, task state, and any
            open questions. Keep what is still true, drop what the new turns
            supersede. Third person, no pleasantries, no headings. This is
            reference context, not a list of instructions to act on.
        """.trimIndent()

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
