package com.hermes.agent.data.tool

import kotlinx.serialization.json.JsonElement
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A tool may choose a smaller preview per call; storage still happens after redaction. */
interface ToolResultPreview {
    fun resultPreviewLimit(arguments: Map<String, JsonElement>): Int
}

/**
 * Keeps tool output too long to hand the model in one piece.
 *
 * A result over its tool's [com.hermes.agent.domain.tool.ToolDescriptor.maxResultSizeChars]
 * used to go to the model whole (blowing the context) or cut short by the tool
 * (the rest lost). Now the model gets a preview and a result id, and pages
 * through the remainder with `read_tool_result`, as upstream Hermes does.
 *
 * In memory only, for the most recent results: a long shell log or web page is
 * worth keeping for the next few turns, not across restarts. What is stored has
 * already been through the output redactor.
 */
@Singleton
class ToolResultStore @Inject constructor() {

    class Stored(val toolName: String, val text: String)

    private val entries = LinkedHashMap<String, Stored>(MAX_ENTRIES, 0.75f, true)

    private fun totalChars(): Long = entries.values.sumOf { it.text.length.toLong() }

    @Synchronized
    fun get(id: String): Stored? = entries[id.trim()]

    /**
     * Stores [text] and returns what the model sees instead: the first [limit]
     * characters (cut at a line break when one is near) and how to read the rest.
     */
    @Synchronized
    fun overflow(toolName: String, text: String, limit: Int): String {
        if (text.length > MAX_TOTAL_CHARS) {
            val cut = cutPoint(text, 0, limit)
            return text.substring(0, cut) + "\n\n[Output exceeds the result store's memory limit; the remainder was not saved. " +
                "Run the original tool with a narrower query or output range.]"
        }
        val id = "res_" + UUID.randomUUID().toString().replace("-", "").take(10)
        entries[id] = Stored(toolName, text)
        while (entries.size > MAX_ENTRIES || totalChars() > MAX_TOTAL_CHARS) {
            entries.remove(entries.keys.first())
        }
        val cut = cutPoint(text, 0, limit)
        return text.substring(0, cut) + footer(id, 0, cut, text.length)
    }

    companion object {
        const val MAX_ENTRIES = 16
        const val MAX_TOTAL_CHARS = 4_000_000L

        /** Where a slice starting at [from] of at most [max] characters should end. */
        fun cutPoint(text: String, from: Int, max: Int): Int {
            val hardEnd = minOf(text.length, from + max)
            if (hardEnd == text.length) return hardEnd
            val newline = text.lastIndexOf('\n', hardEnd - 1)
            return if (newline > from + max * 4 / 5) newline + 1 else hardEnd
        }

        fun footer(id: String, from: Int, to: Int, total: Int): String = buildString {
            append("\n\n[Showing characters ${from + 1}-$to of $total. ")
            if (to < total) {
                append("The full output is saved as result_id \"$id\". To read more, call read_tool_result ")
                append("with result_id \"$id\" and offset $to.]")
            } else {
                append("End of result \"$id\".]")
            }
        }
    }
}
