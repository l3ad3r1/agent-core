package com.hermes.agent.data.plugin.evolution

import com.hermes.agent.domain.skill.SkillGuard
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Turns the proposal model's reply into validated drafts. Lenient about framing
 * (fences, prose around the JSON), strict about content: an item missing a
 * required field, with an unknown kind, or flagged by Skills Guard is dropped.
 */
object EvolutionProposalParser {

    data class Draft(
        val title: String,
        val problem: String,
        val evidence: String,
        val kind: ProposalKind,
        val targetTool: String?,
        val acceptanceCriteria: List<String>,
        /** 1-based index into the signals the prompt listed, when the model named one. */
        val signalIndex: Int?,
    )

    const val MAX_PROPOSALS = 5
    private val JSON = Json { isLenient = false }
    private val CONTROL = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")
    private val SIGNAL_REF = Regex("^[Ss]?(\\d{1,2})$")

    fun parse(raw: String): List<Draft> {
        val text = raw.trim()
        if (text.isEmpty() || text.length > BotOutputParser.MAX_OUTPUT_CHARS || text.uppercase().startsWith("NO_PROPOSALS")) {
            return emptyList()
        }
        val root = locate(text) ?: return emptyList()
        val items = when (root) {
            is JsonArray -> root
            is JsonObject -> root["proposals"] as? JsonArray ?: return emptyList()
            else -> return emptyList()
        }
        return items.mapNotNull { (it as? JsonObject)?.let(::draftOf) }
            .distinctBy { it.title.lowercase() }
            .take(MAX_PROPOSALS)
    }

    private fun locate(text: String): JsonElement? {
        val candidates = BotOutputParser.fencedBlocks(text).map { it.body } + text +
            listOfNotNull(
                text.indexOf('{').takeIf { it >= 0 }?.let { start ->
                    text.lastIndexOf('}').takeIf { it > start }?.let { end -> text.substring(start, end + 1) }
                },
            )
        return candidates.firstNotNullOfOrNull { c ->
            runCatching { JSON.parseToJsonElement(c.trim()) }.getOrNull()?.takeIf { it is JsonObject || it is JsonArray }
        }
    }

    private fun str(obj: JsonObject, key: String, max: Int): String? =
        (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?.replace(CONTROL, "")?.replace(Regex("\\s+"), " ")?.trim()?.take(max)?.takeIf { it.isNotBlank() }

    private fun draftOf(obj: JsonObject): Draft? {
        val title = str(obj, "title", 80)?.takeIf { it.length >= 4 } ?: return null
        val problem = str(obj, "problem", 600) ?: return null
        val evidence = str(obj, "evidence", 600).orEmpty()
        var kind = ProposalKind.parse((obj["kind"] as? JsonPrimitive)?.contentOrNull) ?: return null
        val criteria = (obj["acceptance_criteria"] as? JsonArray ?: obj["acceptanceCriteria"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
            ?.map { it.replace(CONTROL, "").replace(Regex("\\s+"), " ").trim().take(200) }
            ?.filter { it.isNotBlank() }
            ?.take(6)
            .orEmpty()
        if (criteria.isEmpty()) return null
        var target = (str(obj, "target_tool", 48) ?: str(obj, "targetTool", 48))
            ?.takeIf { EvolutionModuleVetter.TOOL_NAME.matches(it) }
        // A fix with no valid target cannot override anything; it can only be a new module.
        if (kind == ProposalKind.MODULE_FIX && target == null) kind = ProposalKind.MODULE_FEATURE
        if (kind != ProposalKind.MODULE_FIX) target = null
        val signal = (obj["signal"] as? JsonPrimitive)?.let { p ->
            p.intOrNull ?: p.contentOrNull?.let { SIGNAL_REF.matchEntire(it.trim())?.groupValues?.get(1)?.toIntOrNull() }
        }
        val verdict = SkillGuard.vet(listOf(title, problem, evidence, target.orEmpty()).plus(criteria).joinToString("\n"))
        if (!verdict.ok) return null
        return Draft(title, problem, evidence, kind, target, criteria, signal)
    }
}
