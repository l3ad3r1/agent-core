package com.hermes.agent.domain.llm

/**
 * Is this model reply real content, or a refusal, an error body or a blank?
 *
 * Several self-maintaining paths store a model's reply as data: facts about the user, the rolling
 * user model, conversation briefs, skill rewrites. A reply that is not content was being stored
 * all the same. The Hermes compute relay returns its failures as ordinary assistant text marked
 * "[relay error]" with a 200 response, so a relay outage reads, to code that only checks "non-blank",
 * exactly like a fact about the user or a skill body.
 *
 * Deliberately narrow: it matches how such replies *open* (or the relay's marker anywhere), so a
 * real summary that merely mentions an apology ("the assistant said sorry and opened a ticket") is kept.
 */
object GeneratedTextGuard {

    private val REFUSAL_OPENERS = listOf(
        "i can't", "i cannot", "i can not", "i'm sorry", "i am sorry", "sorry,", "sorry but",
        "i'm unable", "i am unable", "i won't", "i will not", "as an ai", "unable to", "i apologize",
    )

    /** True when [text] looks like content the caller can store; false for blanks, refusals and error bodies. */
    fun isUsable(text: String): Boolean {
        if (text.isBlank()) return false
        val head = text.trimStart('"', '\'', '*', '-', ' ', '\n', '\r', '\t').lowercase()
        if (REFUSAL_OPENERS.any(head::startsWith)) return false
        if ("[relay error]" in head) return false
        if (head.startsWith("<!doctype") || head.startsWith("<html")) return false
        if (head.startsWith("{\"error") || head.startsWith("error:")) return false
        return true
    }

    /** [isUsable] for one line of a multi-line reply, such as a list of extracted facts. */
    fun isUsableLine(line: String): Boolean = isUsable(line)
}
