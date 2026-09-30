package com.hermes.agent.data.plugin.evolution

import com.hermes.agent.domain.skill.SkillGuard
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Parses what the builder and reviewer bots send back. Bot output is untrusted
 * data: it is size-capped, never executed or interpreted here, and anything
 * ambiguous resolves to the safe outcome (no artifact, or REQUEST_CHANGES).
 */
object BotOutputParser {

    sealed interface Extraction {
        data class Found(val text: String) : Extraction
        data class Invalid(val reason: String) : Extraction
    }

    const val MAX_OUTPUT_CHARS = 256 * 1024
    const val MAX_SPEC_CHARS = 12_000
    const val MIN_SPEC_CHARS = 80
    const val MAX_FINDINGS = 10
    const val MAX_FINDING_CHARS = 300

    private val FENCE = Regex("```([A-Za-z0-9_+-]*)[ \\t]*\\r?\\n(.*?)\\r?\\n?```", RegexOption.DOT_MATCHES_ALL)
    private val JSON = Json { isLenient = false }
    /** "VERDICT: APPROVE" anywhere — deliberately unanchored, so an echoed one still counts as a conflict. */
    private val VERDICT_LINE = Regex("(?i)\\bverdict\\**\\s*[:=]\\s*\\**\\s*(approve|request[_ ]changes)\\b")
    private val CONTROL = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")

    data class Block(val lang: String, val body: String)

    fun fencedBlocks(text: String): List<Block> =
        FENCE.findAll(text).map { Block(it.groupValues[1].lowercase(), it.groupValues[2]) }.toList()

    private fun parseObject(text: String): JsonObject? =
        runCatching { JSON.parseToJsonElement(text.trim()) as? JsonObject }.getOrNull()

    /**
     * The module manifest from a builder reply. The contract is exactly one fenced
     * JSON object with `id` and `main`; zero or several is a contract violation
     * rather than a guess about which one was meant.
     */
    fun extractModuleManifest(output: String): Extraction {
        if (output.length > MAX_OUTPUT_CHARS) return Extraction.Invalid("reply is larger than $MAX_OUTPUT_CHARS characters")
        val candidates = fencedBlocks(output)
            .map { it.body.trim() }
            .filter { body -> parseObject(body)?.let { "id" in it && "main" in it } == true }
            .distinct()
        return when {
            candidates.size == 1 -> Extraction.Found(candidates.single())
            candidates.size > 1 -> Extraction.Invalid("reply contains ${candidates.size} manifests; send exactly one")
            // Tolerate a bare JSON reply with no fence, but only when that is the whole reply.
            parseObject(output)?.let { "id" in it && "main" in it } == true -> Extraction.Found(output.trim())
            else -> Extraction.Invalid("reply contains no fenced JSON module manifest with \"id\" and \"main\"")
        }
    }

    /** The change spec from a builder reply for an app change. */
    fun extractChangeSpec(output: String): Extraction {
        if (output.length > MAX_OUTPUT_CHARS) return Extraction.Invalid("reply is larger than $MAX_OUTPUT_CHARS characters")
        val blocks = fencedBlocks(output)
        val specBlocks = blocks.filter { it.lang == "spec" }
        val spec = when {
            specBlocks.size > 1 -> return Extraction.Invalid("reply contains ${specBlocks.size} spec blocks; send exactly one")
            specBlocks.size == 1 -> specBlocks.single().body
            else -> blocks.singleOrNull { it.lang == "markdown" || it.lang == "md" }?.body ?: output
        }.replace(CONTROL, "").trim()
        if (spec.length < MIN_SPEC_CHARS) return Extraction.Invalid("the change spec is too short to act on")
        if (spec.length > MAX_SPEC_CHARS) return Extraction.Invalid("the change spec is longer than $MAX_SPEC_CHARS characters")
        val verdict = SkillGuard.vet(spec)
        if (!verdict.ok) return Extraction.Invalid("the change spec was flagged by Skills Guard: ${verdict.flags.joinToString()}")
        return Extraction.Found(spec)
    }

    /**
     * The reviewer's verdict. Never null: a reply with no verdict, or with more
     * than one different verdict anywhere in it, is REQUEST_CHANGES.
     */
    fun parseVerdict(output: String): ReviewVerdict {
        if (output.length > MAX_OUTPUT_CHARS) {
            return ReviewVerdict(Verdict.REQUEST_CHANGES, listOf("Reviewer reply was too large to trust"))
        }
        val objects = fencedBlocks(output).mapNotNull { parseObject(it.body) } +
            listOfNotNull(parseObject(output))
        val jsonVerdicts = objects.filter { "verdict" in it }
        val verdicts = mutableSetOf<Verdict?>()
        jsonVerdicts.forEach { verdicts += verdictOf((it["verdict"] as? JsonPrimitive)?.contentOrNull) }
        VERDICT_LINE.findAll(output).forEach { verdicts += verdictOf(it.groupValues[1]) }

        val findings = jsonVerdicts.flatMap { findingsOf(it["findings"]) }.ifEmpty { bulletFindings(output) }
            .map { it.replace(CONTROL, "").replace(Regex("\\s+"), " ").trim().take(MAX_FINDING_CHARS) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_FINDINGS)

        return when {
            verdicts.isEmpty() -> ReviewVerdict(Verdict.REQUEST_CHANGES, listOf("Reviewer reply had no parseable verdict") + findings)
            null in verdicts -> ReviewVerdict(Verdict.REQUEST_CHANGES, listOf("Reviewer sent an unknown verdict") + findings)
            verdicts.size > 1 -> ReviewVerdict(Verdict.REQUEST_CHANGES, listOf("Reviewer sent conflicting verdicts") + findings)
            else -> ReviewVerdict(verdicts.single()!!, findings)
        }.let { it.copy(findings = it.findings.take(MAX_FINDINGS)) }
    }

    private fun verdictOf(raw: String?): Verdict? = when (raw?.trim()?.uppercase()?.replace(' ', '_')) {
        "APPROVE", "APPROVED" -> Verdict.APPROVE
        "REQUEST_CHANGES", "CHANGES_REQUESTED" -> Verdict.REQUEST_CHANGES
        else -> null
    }

    private fun findingsOf(element: JsonElement?): List<String> = when (element) {
        is JsonArray -> element.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> item.contentOrNull
                is JsonObject -> listOf("message", "issue", "finding", "description")
                    .firstNotNullOfOrNull { (item[it] as? JsonPrimitive)?.contentOrNull }
                else -> null
            }
        }
        is JsonPrimitive -> listOfNotNull(element.contentOrNull)
        else -> emptyList()
    }

    private fun bulletFindings(output: String): List<String> {
        val after = output.substringAfter("FINDINGS", missingDelimiterValue = "")
        if (after.isEmpty()) return emptyList()
        return after.lines().map { it.trim() }.filter { it.startsWith("- ") || it.startsWith("* ") }.map { it.drop(2) }
    }
}
