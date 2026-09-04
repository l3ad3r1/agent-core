package com.hermes.agent.data.llm
import com.hermes.agent.domain.llm.*
import com.hermes.agent.domain.settings.*

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicLong

/** Parses the textual tool envelope emitted by local and Hermes/Nous-style models. */
internal fun extractTextToolCalls(
    content: String,
    json: Json,
    knownToolNames: Set<String> = emptySet(),
): Pair<String, List<ToolCall>> {
    if (content.isBlank()) return content to emptyList()
    if (!content.contains("<tool", ignoreCase = true)) {
        return recoverLooseToolCall(content, json, knownToolNames)
    }
    val calls = mutableListOf<ToolCall>()
    var sawStructuredEnvelope = false
    TOOL_CALL_TAG.findAll(content).forEach { match ->
        val element = parseRelaxed(match.groupValues[1].trim(), json) ?: return@forEach
        val objects = when (element) {
            is JsonArray -> element.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(element)
            else -> emptyList()
        }
        if (objects.isNotEmpty()) sawStructuredEnvelope = true
        objects.forEach { obj ->
            calls += toolCallFromObject(obj, json) ?: return@forEach
        }
    }
    if (calls.isNotEmpty()) return TOOL_CALL_TAG.replace(content, "").trim() to calls
    // A parseable JSON envelope that named no runnable tool is still a failed
    // tool call, not prose — strip it so raw <tool_call> markup never reaches
    // the user (a small model asked to "reply on Telegram" invents an envelope
    // like {"message": "...", "platform": "Telegram"} that matches no tool).
    // Genuinely malformed junk falls through to the loose recovery, which
    // leaves ordinary text untouched.
    if (sawStructuredEnvelope) return TOOL_CALL_TAG.replace(content, "").trim() to emptyList()
    return recoverLooseToolCall(content, json, knownToolNames)
}

/**
 * Reads one `{…}` tool envelope. Accepts the shapes small models actually emit:
 * `{"name": …, "arguments": {…}}`, `{"name": …, "parameters": {…}}`, and the
 * OpenAI-in-text `{"function": {"name": …, "arguments": {…}}}`. `arguments` may
 * itself be a JSON string. Returns null when no tool name is present.
 */
private fun toolCallFromObject(obj: JsonObject, json: Json): ToolCall? {
    val fn = (obj["function"] as? JsonObject) ?: obj
    val name = fn["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: return null
    val arguments: Map<String, JsonElement> = when (val value = fn["arguments"] ?: fn["parameters"]) {
        is JsonObject -> value
        is JsonPrimitive -> runCatching {
            json.parseToJsonElement(value.content).jsonObject
        }.getOrNull() ?: emptyMap()
        else -> emptyMap()
    }
    return ToolCall(
        id = "text_call_${TOOL_CALL_ID.incrementAndGet()}",
        name = name,
        arguments = arguments,
    )
}

/**
 * Last resort for a model that meant to call a tool but could not produce the
 * envelope.
 *
 * Llama 3.2 1B on device answers "use the todo tool" with a markdown heading
 * and a bare argument object:
 *
 *     ## todo
 *     {"title": "Buy milk", "priority": "low"}
 *
 * That is a tool call in everything but syntax, and dropping it leaves the user
 * with a reply that reads like a confirmation while nothing was written.
 *
 * Guarded by [knownToolNames]: a call is recovered only when the name resolves
 * to a tool advertised for this turn. Without that guard any reply containing
 * JSON — which a capable model produces whenever it is asked about JSON — could
 * be executed. When the caller passes no names nothing is recovered, so the
 * cloud path is unchanged unless it opts in.
 */
private fun recoverLooseToolCall(
    content: String,
    json: Json,
    knownToolNames: Set<String>,
): Pair<String, List<ToolCall>> {
    if (knownToolNames.isEmpty() || !content.contains('{')) return content to emptyList()

    for (span in jsonObjectSpans(content)) {
        val obj = parseRelaxed(content.substring(span.first, span.last + 1), json) as? JsonObject
            ?: continue

        // Shape 1: the envelope, without its tags.
        val declared = obj["name"]?.jsonPrimitive?.contentOrNull?.trim()
        val declaredArguments = obj["arguments"] as? JsonObject
        if (declared != null && declared in knownToolNames && declaredArguments != null) {
            return content.removeRange(span).trim() to listOf(
                ToolCall(
                    id = "recovered_call_${TOOL_CALL_ID.incrementAndGet()}",
                    name = declared,
                    arguments = declaredArguments,
                ),
            )
        }

        // Shape 2: a heading naming the tool, then its arguments. The object's
        // own "name" is ignored here — in the device capture it held the task
        // title rather than the tool.
        val prefix = content.substring(0, span.first)
        val named = knownToolNames.firstOrNull { headingFor(it).containsMatchIn(prefix) } ?: continue
        val cleaned = (
            prefix.replace(headingFor(named), "") + content.substring(span.last + 1)
            ).trim()
        return cleaned to listOf(
            ToolCall(
                id = "recovered_call_${TOOL_CALL_ID.incrementAndGet()}",
                name = named,
                arguments = obj,
            ),
        )
    }
    return content to emptyList()
}

/** A line that is only the tool's name, with or without markdown decoration. */
private fun headingFor(name: String) =
    Regex("""(?m)^[#*\s]*""" + Regex.escape(name) + """[:*\s]*$""")

/**
 * Index ranges of the top-level JSON objects in [text].
 *
 * Brace counting rather than a regex: the argument objects nest, and braces
 * appear inside their string values.
 */
private fun jsonObjectSpans(text: String): List<IntRange> {
    val spans = mutableListOf<IntRange>()
    var depth = 0
    var start = -1
    var inString = false
    var escaped = false
    text.forEachIndexed { index, ch ->
        when {
            escaped -> escaped = false
            inString && ch == '\\' -> escaped = true
            ch == '"' -> inString = !inString
            inString -> Unit
            ch == '{' -> {
                if (depth == 0) start = index
                depth++
            }
            ch == '}' -> {
                if (depth > 0) depth--
                if (depth == 0 && start >= 0) {
                    spans += start..index
                    start = -1
                }
            }
        }
    }
    return spans
}

/**
 * Parses [text] as JSON, retrying once with quotes added around bare keys and
 * values.
 *
 * Llama 3.2 1B on device re-states a call it has already made as
 *
 *     {name:word_count, arguments:{"action":"count","text":"..."}}
 *
 * which is a tool call in every respect except that the key and the tool name
 * are unquoted, so a strict parse rejects it. The reply then flowed through as
 * ordinary text and the raw brace soup was shown to the user as the assistant's
 * answer.
 *
 * The strict parse is always tried first, so well-formed output takes the same
 * path it always did and only malformed output pays for the repair.
 */
private fun parseRelaxed(text: String, json: Json): JsonElement? {
    runCatching { json.parseToJsonElement(text) }.getOrNull()?.let { return it }
    val normalized = normalizeRelaxedJson(text)
    if (normalized == text) return null
    return runCatching { json.parseToJsonElement(normalized) }.getOrNull()
}

/**
 * Quotes unquoted object keys and bare identifier values.
 *
 * Character-by-character rather than a regex because the repair must not reach
 * inside string values, where a colon or a brace is ordinary text.
 */
private fun normalizeRelaxedJson(text: String): String {
    val out = StringBuilder(text.length + 16)
    var index = 0
    var inString = false
    var escaped = false
    while (index < text.length) {
        val ch = text[index]
        when {
            escaped -> {
                out.append(ch); escaped = false; index++
            }
            inString -> {
                out.append(ch)
                if (ch == '\\') escaped = true else if (ch == '"') inString = false
                index++
            }
            ch == '"' -> {
                out.append(ch); inString = true; index++
            }
            ch == '{' || ch == ',' -> {
                out.append(ch); index = appendBareToken(text, index + 1, out, asKey = true)
            }
            ch == ':' -> {
                out.append(ch); index = appendBareToken(text, index + 1, out, asKey = false)
            }
            else -> {
                out.append(ch); index++
            }
        }
    }
    return out.toString()
}

/**
 * Copies whitespace from [start], then quotes the identifier that follows when
 * it sits where JSON requires a quoted string. Returns the index to resume at.
 */
private fun appendBareToken(
    text: String,
    start: Int,
    out: StringBuilder,
    asKey: Boolean,
): Int {
    var cursor = start
    while (cursor < text.length && text[cursor].isWhitespace()) cursor++
    val tokenStart = cursor
    while (cursor < text.length && (text[cursor].isLetterOrDigit() || text[cursor] == '_')) cursor++
    val tokenEnd = cursor

    val token = text.substring(tokenStart, tokenEnd)
    val startsIdentifier = token.isNotEmpty() && (token[0].isLetter() || token[0] == '_')
    // true/false/null are real JSON values; quoting them would change the type.
    val quotable = startsIdentifier && token !in JSON_LITERALS

    val followed = if (asKey) {
        var probe = cursor
        while (probe < text.length && text[probe].isWhitespace()) probe++
        probe < text.length && text[probe] == ':'
    } else {
        true
    }

    if (!quotable || !followed) return start

    out.append(text, start, tokenStart)
    out.append('"').append(token).append('"')
    return tokenEnd
}

private val JSON_LITERALS = setOf("true", "false", "null")

private val TOOL_CALL_TAG = Regex(
    "<(?:tool_call|toolcall)>(.*?)</(?:tool_call|toolcall)>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

private val TOOL_CALL_ID = AtomicLong(0)
