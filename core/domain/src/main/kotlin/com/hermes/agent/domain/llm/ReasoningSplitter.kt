package com.hermes.agent.domain.llm

/**
 * Separates a model's working-out from its answer.
 *
 * Reasoning models hand back their thinking in one of two ways: in a separate field of the reply
 * (`reasoning_content`, or `reasoning`), or inline in the text between `<think>` and `</think>`
 * tags. Left in, the inline form shows up in the chat as if it were the answer. This takes either
 * and returns the answer with the thinking removed, and the thinking on its own so it can be shown
 * behind a "Thought for 6s" chip instead.
 *
 * Handles the awkward cases seen in practice: a template that writes only the closing tag, a
 * reply cut off in the middle of a thought (no closing tag: everything after the opening tag is
 * thinking and there is no answer yet), and several thinking blocks in one reply.
 */
object ReasoningSplitter {

    data class Split(val answer: String, val reasoning: String) {
        val hasReasoning: Boolean get() = reasoning.isNotBlank()
    }

    private val closed = Regex("<(think|thinking)>(.*?)</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val open = Regex("<(think|thinking)>", RegexOption.IGNORE_CASE)
    private val strayClose = Regex("</(think|thinking)>", RegexOption.IGNORE_CASE)

    fun split(content: String, structuredReasoning: String = ""): Split {
        val thoughts = mutableListOf<String>()
        if (structuredReasoning.isNotBlank()) thoughts += structuredReasoning.trim()

        var text = closed.replace(content) { m ->
            m.groupValues[2].trim().takeIf { it.isNotEmpty() }?.let(thoughts::add)
            ""
        }
        // A cut-off thought: the opening tag never closed, so the rest is all thinking.
        open.find(text)?.let { m ->
            text.substring(m.range.last + 1).trim().takeIf { it.isNotEmpty() }?.let(thoughts::add)
            text = text.substring(0, m.range.first)
        }
        // A template that supplies the opening tag itself leaves only a closing one in the reply.
        strayClose.find(text)?.let { m ->
            text.substring(0, m.range.first).trim().takeIf { it.isNotEmpty() }?.let(thoughts::add)
            text = text.substring(m.range.last + 1)
        }
        return Split(answer = text.trim(), reasoning = thoughts.joinToString("\n\n"))
    }
}
