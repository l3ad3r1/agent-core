package com.hermes.agent.data.plugin.evolution

/**
 * Makes evidence safe to put in a prompt, a bot run, or an issue — or refuses it.
 *
 * Implemented by the host app with its own redaction rules, so there is one set
 * of secret patterns per app rather than a drifting copy here. Returning null
 * drops the example entirely; the miner never falls back to raw text.
 */
fun interface EvidenceSanitizer {
    fun sanitize(text: String): String?
}

/** One tool execution from the activity ledger. */
data class ToolCallRecord(
    val tool: String,
    val success: Boolean,
    val detail: String,
    val timestamp: Long,
)

/** One user or assistant chat message. */
data class ChatRecord(
    val role: String,
    val content: String,
    val conversationId: String,
    val timestamp: Long,
)

/** Everything the miner reads, loaded by the host from its own database. */
data class UsageSnapshot(
    val toolCalls: List<ToolCallRecord>,
    val messages: List<ChatRecord>,
    /** Skill name → how many times it was rewritten in the window. */
    val skillRefinements: Map<String, Int>,
    /** Built-in tools currently registered (module tools excluded by the host). */
    val builtInTools: Set<String>,
)

/** Loads a [UsageSnapshot] covering the last [windowMillis]. */
fun interface UsageSnapshotSource {
    suspend fun load(windowMillis: Long): UsageSnapshot
}

/**
 * Deterministic usage mining — no model call, no network. Turns raw history
 * into a short ranked list of [EvolutionSignal]s:
 *
 *  - tools that failed repeatedly, grouped by tool and normalized error;
 *  - turns where the assistant said it could not do what was asked;
 *  - request shapes the user keeps repeating;
 *  - skills that needed rewriting again and again;
 *  - built-in tools never used, once there is enough activity to say so.
 *
 * Every example passes through the [EvidenceSanitizer]; an example it rejects is
 * dropped, not included raw.
 */
class UsageSignalMiner(private val sanitizer: EvidenceSanitizer) {

    fun mine(snapshot: UsageSnapshot, limit: Int = MAX_SIGNALS): List<EvolutionSignal> {
        val signals = buildList {
            addAll(toolFailures(snapshot.toolCalls))
            addAll(capabilityGaps(snapshot.messages))
            addAll(repeatedRequests(snapshot.messages))
            addAll(skillChurn(snapshot.skillRefinements))
            unusedTools(snapshot)?.let { add(it) }
        }
        return signals
            .sortedWith(compareByDescending<EvolutionSignal> { it.score }.thenBy { it.key })
            .take(limit)
    }

    internal fun toolFailures(calls: List<ToolCallRecord>): List<EvolutionSignal> {
        val byTool = calls.groupBy { it.tool }
        return calls.filter { !it.success && it.tool.isNotBlank() }
            .groupBy { it.tool to normalizeError(it.detail) }
            .filter { (_, rows) -> rows.size >= MIN_REPEATS }
            .map { (key, rows) ->
                val (tool, error) = key
                val total = byTool[tool]?.size ?: rows.size
                val failureRate = rows.size.toDouble() / total
                EvolutionSignal(
                    kind = SignalKind.TOOL_FAILURE,
                    key = "tool_failure:$tool:${fingerprint(error)}",
                    count = rows.size,
                    score = rows.size * 3.0 + failureRate * 5.0,
                    summary = "'$tool' failed ${rows.size} of $total calls" +
                        (if (error.isNotBlank()) " with: ${safe(error)}" else ""),
                    examples = examples(rows.sortedByDescending { it.timestamp }.map { it.detail }),
                    tool = tool,
                )
            }
    }

    internal fun capabilityGaps(messages: List<ChatRecord>): List<EvolutionSignal> {
        val gaps = mutableListOf<String>()
        messages.groupBy { it.conversationId }.values.forEach { convo ->
            val ordered = convo.sortedBy { it.timestamp }
            ordered.forEachIndexed { i, msg ->
                if (!msg.role.equals("assistant", true) || !GAP.containsMatchIn(msg.content.take(600))) return@forEachIndexed
                val ask = ordered.subList(0, i).lastOrNull { it.role.equals("user", true) } ?: return@forEachIndexed
                gaps += ask.content
            }
        }
        // Refusals group by a coarse verb-object shape: "book a table at X" and "book a table
        // tomorrow" are the same missing feature.
        return gaps.groupBy { requestShape(it, words = GAP_SHAPE_WORDS) }
            .filter { (shape, _) -> shape.isNotBlank() }
            .map { (shape, asks) ->
                EvolutionSignal(
                    kind = SignalKind.CAPABILITY_GAP,
                    key = "gap:${fingerprint(shape)}",
                    count = asks.size,
                    // A refusal is the strongest hint of a missing feature, so even one counts.
                    score = asks.size * 4.0 + 1.0,
                    summary = "The assistant could not help ${asks.size} time(s) with requests like \"${safe(shape)}\"",
                    examples = examples(asks),
                )
            }
    }

    internal fun repeatedRequests(messages: List<ChatRecord>): List<EvolutionSignal> =
        messages.filter { it.role.equals("user", true) && it.content.length >= 8 }
            .groupBy { requestShape(it.content) }
            .filter { (shape, rows) -> shape.split(' ').size >= 2 && rows.size >= MIN_REPEATED_REQUESTS }
            .map { (shape, rows) ->
                val conversations = rows.map { it.conversationId }.distinct().size
                EvolutionSignal(
                    kind = SignalKind.REPEATED_REQUEST,
                    key = "repeat:${fingerprint(shape)}",
                    count = rows.size,
                    score = rows.size * 1.0 + conversations * 0.5,
                    summary = "Asked ${rows.size} times across $conversations conversation(s): \"${safe(shape)}\"",
                    examples = examples(rows.map { it.content }),
                )
            }

    internal fun skillChurn(refinements: Map<String, Int>): List<EvolutionSignal> =
        refinements.filter { (name, count) -> name.isNotBlank() && count >= MIN_REPEATS }
            .map { (name, count) ->
                EvolutionSignal(
                    kind = SignalKind.SKILL_CHURN,
                    key = "skill:${fingerprint(name)}",
                    count = count,
                    score = count * 1.5,
                    summary = "Skill '${safe(name)}' was rewritten $count times — it may need a real tool",
                )
            }

    internal fun unusedTools(snapshot: UsageSnapshot): EvolutionSignal? {
        if (snapshot.toolCalls.size < MIN_CALLS_FOR_UNUSED) return null
        val used = snapshot.toolCalls.map { it.tool }.toSet()
        val unused = (snapshot.builtInTools - used).sorted()
        if (unused.isEmpty()) return null
        return EvolutionSignal(
            kind = SignalKind.UNUSED_TOOL,
            key = "unused:built-in",
            count = unused.size,
            score = 0.5 + unused.size * 0.05,
            summary = "${unused.size} built-in tool(s) unused in ${snapshot.toolCalls.size} calls: " +
                unused.take(15).joinToString(", ") + if (unused.size > 15) ", …" else "",
        )
    }

    /** Text for a summary: sanitized, or a placeholder — never the raw input. */
    private fun safe(text: String): String = sanitizer.sanitize(text)?.take(MAX_EXAMPLE_CHARS) ?: "(withheld)"

    private fun examples(raw: List<String>): List<String> =
        raw.asSequence()
            .map { it.replace(WHITESPACE, " ").trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { sanitizer.sanitize(it)?.take(MAX_EXAMPLE_CHARS) }
            .distinct()
            .take(MAX_EXAMPLES)
            .toList()

    companion object {
        const val MAX_SIGNALS = 12
        const val MIN_REPEATS = 2
        const val MIN_REPEATED_REQUESTS = 3
        const val MIN_CALLS_FOR_UNUSED = 30
        const val MAX_EXAMPLES = 3
        const val MAX_EXAMPLE_CHARS = 160
        const val GAP_SHAPE_WORDS = 2

        private val WHITESPACE = Regex("\\s+")
        private val URL = Regex("https?://\\S+")
        private val NUMBER = Regex("\\d+")
        private val QUOTED = Regex("\"[^\"]*\"|'[^']*'|`[^`]*`")
        private val NON_WORD = Regex("[^a-z ]")

        /** "I can't…", "I'm unable to…", "I don't have a tool for…", "no tool is available…". */
        private val GAP = Regex(
            listOf(
                "\\bi (?:can(?:no|')t|am unable to|'m unable to|am not able to|'m not able to)\\b",
                "\\bi (?:do not|don't) have (?:a |an |the )?(?:tool|ability|way|access|capability|integration)",
                "\\bno (?:suitable |available )?tool\\b",
                "\\bnot (?:currently )?(?:supported|possible for me)\\b",
                "\\bbeyond my (?:current )?capabilities\\b",
            ).joinToString("|"),
            RegexOption.IGNORE_CASE,
        )

        private val STOPWORDS = setOf(
            "a", "an", "the", "to", "of", "for", "and", "or", "in", "on", "at", "is", "are", "be",
            "me", "my", "i", "you", "your", "it", "this", "that", "please", "can", "could", "would",
            "will", "with", "what", "whats", "how", "do", "does", "hey", "hi", "hermes", "jeeves",
            "just", "some", "any", "now", "up", "about", "from", "by", "as", "so", "if", "all",
        )

        /**
         * Short stable digest for signal keys. Keys are persisted to deduplicate
         * proposals; hashing keeps user text and error strings out of them.
         */
        fun fingerprint(text: String): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .take(8)
                .joinToString("") { "%02x".format(it) }

        /** The first few content words, lowercased, with numbers, links and quotes removed. */
        fun requestShape(text: String, words: Int = 4): String =
            text.lowercase()
                .replace(URL, " ")
                .replace(QUOTED, " ")
                .replace(NUMBER, " ")
                .replace(NON_WORD, " ")
                .split(' ')
                .filter { it.length > 1 && it !in STOPWORDS }
                .take(words)
                .joinToString(" ")

        /** First line of an error, lowercased, digits collapsed, so one bug groups as one signal. */
        fun normalizeError(detail: String): String =
            detail.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
                .lowercase()
                .replace(URL, "<url>")
                .replace(NUMBER, "#")
                .replace(WHITESPACE, " ")
                .trim()
                .take(120)
    }
}
