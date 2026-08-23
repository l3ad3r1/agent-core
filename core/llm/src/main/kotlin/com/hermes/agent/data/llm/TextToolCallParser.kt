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
    TOOL_CALL_TAG.findAll(content).forEach { match ->
        val element = runCatching {
            json.parseToJsonElement(match.groupValues[1].trim())
        }.getOrNull() ?: return@forEach
        val objects = when (element) {
            is JsonArray -> element.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(element)
            else -> emptyList()
        }
        objects.forEach { obj ->
            val name = obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@forEach
            val arguments: Map<String, JsonElement> = when (val value = obj["arguments"]) {
                is JsonObject -> value
                is JsonPrimitive -> runCatching {
                    json.parseToJsonElement(value.content).jsonObject
                }.getOrNull() ?: emptyMap()
                else -> emptyMap()
            }
            calls += ToolCall(
                id = "text_call_${TOOL_CALL_ID.incrementAndGet()}",
                name = name,
                arguments = arguments,
            )
        }
    }
    if (calls.isEmpty()) return recoverLooseToolCall(content, json, knownToolNames)
    return TOOL_CALL_TAG.replace(content, "").trim() to calls
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
        val obj = runCatching {
            json.parseToJsonElement(content.substring(span.first, span.last + 1)) as? JsonObject
        }.getOrNull() ?: continue

        // Shape 1: the envelope, without its tags.
        val declared = obj["name"]?.jsonPrimitive?.contentOrNull?.trim()
        val declaredArguments = obj["arguments"] as? JsonObject
        if (declared != null && declared in knownToolNames && declaredArguments != null) {
            return content.removeRange(span).trim() to listOf(
                ToolCall(
                    id = "text_call_${TOOL_CALL_ID.incrementAndGet()}",
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
                id = "text_call_${TOOL_CALL_ID.incrementAndGet()}",
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

private val TOOL_CALL_TAG = Regex(
    "<(?:tool_call|toolcall)>(.*?)</(?:tool_call|toolcall)>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

private val TOOL_CALL_ID = AtomicLong(0)
