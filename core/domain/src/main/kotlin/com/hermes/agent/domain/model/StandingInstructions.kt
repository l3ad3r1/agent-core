package com.hermes.agent.domain.model

/**
 * Free-text guidance the user writes once and that is prepended to **every**
 * agent's system prompt — OpenClaw's "Standing Orders" (`docs/automation/index.md`:
 * *persistent agent instructions injected into every session*).
 *
 * This is deliberately **context only**. It cannot grant a tool, change routing,
 * or start a session, and it is screened before it reaches a prompt: the text is
 * user-authored but it lands in the same context window as tool output, so a
 * paste of tool-call syntax or a role tag must not be able to forge a turn.
 *
 * Not to be confused with [StandingOrder], which is a *scheduled* proactive
 * instruction executed by the heartbeat worker.
 */
object StandingInstructions {

    /** Hard ceiling on what is injected, in characters. */
    const val MAX_LENGTH = 4096

    /**
     * Fragments that must never survive into a system prompt. These are the same
     * shapes the notification screen drops; here the text is the user's own, so
     * the offending fragment is stripped and the rest is kept rather than the
     * whole block being discarded.
     */
    private val FORBIDDEN = listOf(
        Regex("""(?i)</?\s*tool_call\s*>"""),
        Regex("""(?i)</?\s*(system|assistant|user)\s*>"""),
        Regex("""(?i)^\s*(system|assistant|user)\s*:""", RegexOption.MULTILINE),
        Regex("""<\|[^|>]{0,64}\|>"""),
        Regex("""(?i)\bfunction_call\b"""),
        Regex("""(?i)\bignore\s+(?:all\s+)?previous\b"""),
    )

    /** Outcome of screening: what will be injected, and whether anything was removed. */
    data class Screened(val content: String, val removedCount: Int) {
        val isEmpty: Boolean get() = content.isBlank()
    }

    /**
     * Trims, truncates to [MAX_LENGTH], and strips forbidden fragments.
     * Returns a blank [Screened.content] when there is nothing left to inject.
     */
    fun screen(raw: String): Screened {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Screened("", 0)

        var removed = 0
        var text = trimmed.take(MAX_LENGTH)
        for (pattern in FORBIDDEN) {
            val hits = pattern.findAll(text).count()
            if (hits > 0) {
                removed += hits
                text = pattern.replace(text, " ")
            }
        }
        return Screened(text.replace(Regex("[ \\t]{2,}"), " ").trim(), removed)
    }

    /**
     * The block appended to a system prompt, or `""` when there is nothing to say.
     * Kept as one clearly-labelled section so the model can tell user standing
     * guidance apart from the agent's own wiring.
     */
    fun promptBlock(raw: String): String {
        val screened = screen(raw)
        if (screened.isEmpty) return ""
        return "\n\n## Standing instructions from the user\n" +
            "These apply to every turn. Follow them unless the user overrides them in this conversation.\n\n" +
            screened.content + "\n"
    }
}
