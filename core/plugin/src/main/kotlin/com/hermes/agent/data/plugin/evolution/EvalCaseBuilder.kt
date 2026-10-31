package com.hermes.agent.data.plugin.evolution

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One evaluation case for the PC evolution lab, in the shape
 * NousResearch/hermes-agent-self-evolution's `EvalExample` reads (`task_input`,
 * `expected_behavior`, `difficulty`, `category`, `source`); the extra keys are ignored there
 * and used by the lab for de-duplication and grouping.
 */
@Serializable
data class EvalCase(
    val id: String,
    @SerialName("task_input") val taskInput: String,
    @SerialName("expected_behavior") val expectedBehavior: String,
    val difficulty: String,
    val category: String,
    // No default: a defaulted field is left out of the JSON, and the upstream loader expects it.
    val source: String,
    val tool: String? = null,
    val app: String,
    @SerialName("created_at") val createdAt: Long,
) {
    companion object {
        const val SOURCE = "jeeves-usage"
        const val CATEGORY_TOOL_FAILURE = "tool_failure"
        const val CATEGORY_CAPABILITY_GAP = "capability_gap"
        const val CATEGORY_USER_CORRECTION = "user_correction"
    }
}

/**
 * Turns usage history into evaluation cases: moments where Jeeves demonstrably fell short, each
 * paired with what a good reply would have done. Deterministic, no model call.
 *
 *  - a tool call that failed, with the user request it was serving;
 *  - a request the assistant said it could not do;
 *  - a request whose answer the user had to correct.
 *
 * Every text goes through [sanitizer]. A case whose request the sanitizer refuses is dropped
 * whole; quoted evidence it refuses is left out of the rubric. Raw text never gets through.
 */
class EvalCaseBuilder(
    private val sanitizer: EvidenceSanitizer,
    private val app: String,
) {

    fun build(snapshot: UsageSnapshot, limit: Int = MAX_CASES): List<EvalCase> {
        val messages = snapshot.messages.sortedBy { it.timestamp }
        val cases = toolFailures(snapshot.toolCalls, messages) +
            capabilityGaps(messages) +
            corrections(messages)
        return cases.distinctBy { it.id }
            .sortedByDescending { it.createdAt }
            .take(limit)
    }

    internal fun toolFailures(calls: List<ToolCallRecord>, messages: List<ChatRecord>): List<EvalCase> =
        calls.filter { !it.success && it.tool.isNotBlank() && !NOT_A_FAILURE.containsMatchIn(it.detail) }.mapNotNull { call ->
            // The request this call served: the last thing the user said shortly before it.
            val ask = messages.lastOrNull {
                it.role.equals("user", true) && it.timestamp <= call.timestamp &&
                    call.timestamp - it.timestamp <= ASK_WINDOW_MS
            } ?: return@mapNotNull null
            val error = UsageSignalMiner.normalizeError(call.detail)
            val quotedError = quote(error)
            case(
                category = EvalCase.CATEGORY_TOOL_FAILURE,
                task = ask.content,
                tool = call.tool,
                difficulty = "hard",
                createdAt = call.timestamp,
                rubric = buildString {
                    append("Gets the user what they asked for. Last time the `${call.tool}` tool failed on this request")
                    if (quotedError != null) append(" with: $quotedError")
                    append(". A good reply works around the failure (another tool or approach) or says plainly ")
                    append("that it could not and why; it never claims success it did not have.")
                },
            )
        }

    internal fun capabilityGaps(messages: List<ChatRecord>): List<EvalCase> =
        pairs(messages).mapNotNull { (ask, reply, _) ->
            if (!UsageSignalMiner.GAP.containsMatchIn(reply.content.take(600))) return@mapNotNull null
            case(
                category = EvalCase.CATEGORY_CAPABILITY_GAP,
                task = ask.content,
                difficulty = "hard",
                createdAt = reply.timestamp,
                rubric = buildString {
                    append("Does what was asked, using the tools available, instead of saying it cannot.")
                    quote(reply.content)?.let { append(" Last time it replied: $it") }
                    append(" If it truly cannot, it says what it would need and offers the closest thing it can do.")
                },
            )
        }

    internal fun corrections(messages: List<ChatRecord>): List<EvalCase> =
        pairs(messages).mapNotNull { (ask, _, next) ->
            val correction = next ?: return@mapNotNull null
            if (!CORRECTION.containsMatchIn(correction.content.take(200))) return@mapNotNull null
            case(
                category = EvalCase.CATEGORY_USER_CORRECTION,
                task = ask.content,
                difficulty = "medium",
                createdAt = correction.timestamp,
                rubric = buildString {
                    append("Gets it right the first time.")
                    quote(correction.content)?.let { append(" The user had to correct an earlier answer: $it") }
                },
            )
        }

    /** (user request, the assistant's reply, the user's next message if any), per conversation. */
    private fun pairs(messages: List<ChatRecord>): List<Triple<ChatRecord, ChatRecord, ChatRecord?>> =
        messages.groupBy { it.conversationId }.values.flatMap { convo ->
            val turns = convo.filter { it.role.equals("user", true) || it.role.equals("assistant", true) }
            turns.indices.mapNotNull { i ->
                val reply = turns[i]
                if (!reply.role.equals("assistant", true)) return@mapNotNull null
                val ask = turns.subList(0, i).lastOrNull { it.role.equals("user", true) } ?: return@mapNotNull null
                val next = turns.getOrNull(i + 1)?.takeIf { it.role.equals("user", true) }
                Triple(ask, reply, next)
            }
        }

    private fun case(
        category: String,
        task: String,
        rubric: String,
        difficulty: String,
        createdAt: Long,
        tool: String? = null,
    ): EvalCase? {
        val cleanTask = clean(task)?.take(MAX_TASK_CHARS) ?: return null
        if (cleanTask.length < MIN_TASK_CHARS) return null
        return EvalCase(
            id = UsageSignalMiner.fingerprint("$category|${tool.orEmpty()}|$cleanTask"),
            taskInput = cleanTask,
            expectedBehavior = rubric.take(MAX_RUBRIC_CHARS),
            difficulty = difficulty,
            category = category,
            source = EvalCase.SOURCE,
            tool = tool,
            app = app,
            createdAt = createdAt,
        )
    }

    /** Sanitized, single-spaced text, or null when the sanitizer refuses it. */
    private fun clean(text: String): String? =
        sanitizer.sanitize(text.replace(WHITESPACE, " ").trim())?.trim()?.takeIf { it.isNotEmpty() }

    private fun quote(text: String): String? = clean(text)?.let { "\"${it.take(MAX_QUOTE_CHARS)}\"" }

    companion object {
        const val MAX_CASES = 100
        const val MIN_TASK_CHARS = 10
        const val MAX_TASK_CHARS = 1_500
        const val MAX_RUBRIC_CHARS = 1_000
        const val MAX_QUOTE_CHARS = 240

        /** A tool call is matched to a user request at most this long before it. */
        const val ASK_WINDOW_MS = 10 * 60 * 1000L

        private val WHITESPACE = Regex("\\s+")

        /**
         * A call stopped by the user's own decision or by a guard working as intended. Its rubric
         * would say "work around the failure", which would teach evolution to route around the
         * user's refusals and the app's safety checks, so these are never cases.
         */
        internal val NOT_A_FAILURE = Regex(
            "user (?:declined|denied|cancel+ed|rejected)|not approved|security violation|" +
                "private or local network address|not permitted by network security policy",
            RegexOption.IGNORE_CASE,
        )

        /** The user telling the assistant its last answer was wrong. Anchored at the start. */
        internal val CORRECTION = Regex(
            "^\\s*(?:no[,.!]|nope\\b|wrong\\b|that'?s (?:not|wrong|incorrect)|not what i|" +
                "you (?:didn'?t|did not|got it wrong|misunderstood)|i (?:said|meant|asked)\\b|incorrect\\b|actually[, ])",
            RegexOption.IGNORE_CASE,
        )
    }
}
