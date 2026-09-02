package com.hermes.agent.data.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Wake-word configuration and normalisation helpers matching OpenClaw `docs/nodes/voicewake.md`.
 */
@Serializable
data class WakeWordConfig(
    val enabled: Boolean = false,
    val triggers: List<String> = listOf(DEFAULT_TRIGGER),
    val routingRules: Map<String, String> = emptyMap(),
    val sensitivity: Float = DEFAULT_SENSITIVITY,
    val restartOnBoot: Boolean = false,
) {
    companion object {
        const val MAX_TRIGGERS = 32
        const val MAX_TRIGGER_LENGTH = 64
        const val MAX_ROUTING_RULES = 32
        const val DEFAULT_TRIGGER = "Hey Hermes"
        const val DEFAULT_SENSITIVITY = 0.5f

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /**
         * Normalises a single trigger phrase:
         * - Trims whitespace
         * - Caps at 64 UTF-16 code units
         */
        fun normalizeTrigger(raw: String): String {
            val trimmed = raw.trim()
            return if (trimmed.length > MAX_TRIGGER_LENGTH) {
                trimmed.substring(0, MAX_TRIGGER_LENGTH).trim()
            } else {
                trimmed
            }
        }

        /**
         * Normalises the global trigger list:
         * - Normalises each phrase (trim, 64-char cap)
         * - Removes blank entries
         * - Deduplicates (case-insensitive deduplication preserving original case of first entry)
         * - Caps at <= 32 entries
         * - Falls back to [DEFAULT_TRIGGER] if empty
         */
        fun normalizeTriggers(rawList: List<String>): List<String> {
            val seen = mutableSetOf<String>()
            val result = mutableListOf<String>()

            for (raw in rawList) {
                val normalized = normalizeTrigger(raw)
                if (normalized.isNotBlank()) {
                    val lower = normalized.lowercase()
                    if (seen.add(lower)) {
                        result.add(normalized)
                        if (result.size >= MAX_TRIGGERS) break
                    }
                }
            }

            return if (result.isEmpty()) listOf(DEFAULT_TRIGGER) else result
        }

        /**
         * Normalises a routing rule trigger key:
         * - Lowercase
         * - Strips punctuation (retaining only ASCII letters, digits, and spaces)
         * - Collapses multiple spaces into a single space and trims
         * - Caps at 64 UTF-16 code units
         */
        fun normalizeRoutingKey(raw: String): String {
            val lower = raw.lowercase()
            val stripped = lower.replace(Regex("[^a-z0-9\\s]"), " ")
            val collapsed = stripped.replace(Regex("\\s+"), " ").trim()
            return if (collapsed.length > MAX_TRIGGER_LENGTH) {
                collapsed.substring(0, MAX_TRIGGER_LENGTH).trim()
            } else {
                collapsed
            }
        }

        /**
         * Normalises routing rules:
         * - Normalises trigger keys (lowercase, punctuation stripped, whitespace collapsed, <=64 chars)
         * - Cleans agent/session target values (trimmed, max 64 chars)
         * - Caps at <= 32 entries
         */
        fun normalizeRoutingRules(rawRules: Map<String, String>): Map<String, String> {
            val result = mutableMapOf<String, String>()
            for ((rawKey, rawValue) in rawRules) {
                val key = normalizeRoutingKey(rawKey)
                val value = rawValue.trim().take(MAX_TRIGGER_LENGTH)
                if (key.isNotBlank() && value.isNotBlank()) {
                    result[key] = value
                    if (result.size >= MAX_ROUTING_RULES) break
                }
            }
            return result
        }

        /**
         * Matches spoken/detected text against configured triggers.
         * Returns the matched trigger phrase, or null if no trigger is present.
         */
        fun matchTrigger(heardText: String, triggers: List<String>): String? {
            if (heardText.isBlank()) return null
            val normalizedText = normalizeRoutingKey(heardText)
            for (trigger in triggers) {
                val normalizedTrigger = normalizeRoutingKey(trigger)
                if (normalizedTrigger.isNotBlank() && (normalizedText == normalizedTrigger || normalizedText.contains(normalizedTrigger))) {
                    return trigger
                }
            }
            return null
        }

        /** How many words past the trigger phrase a wake utterance may run and still count. */
        private const val WAKE_TAIL_WORD_BUDGET = 4

        /**
         * Stricter matcher for the always-on wake service: the trigger must sit at
         * the **start** of the utterance and the utterance must be short (the phrase
         * plus at most [WAKE_TAIL_WORD_BUDGET] trailing words). This is what stops
         * "hey hermes" buried in ordinary conversation, or a long dictated sentence
         * that merely contains the words, from opening the assistant.
         *
         * "hey hermes"                         -> match
         * "hey hermes what's the time"         -> match (3 tail words)
         * "so I told hey hermes about it"      -> no match (not at the start)
         * "hey hermes remind me to call the plumber tomorrow afternoon" -> no match (too long)
         */
        fun matchWakeTrigger(heardText: String, triggers: List<String>): String? {
            if (heardText.isBlank()) return null
            val text = normalizeRoutingKey(heardText)
            val textWords = text.split(' ').filter { it.isNotBlank() }
            for (trigger in triggers) {
                val phrase = normalizeRoutingKey(trigger)
                if (phrase.isBlank()) continue
                val phraseWords = phrase.split(' ').filter { it.isNotBlank() }
                if (phraseWords.isEmpty() || textWords.size < phraseWords.size) continue
                val startsWithPhrase = textWords.subList(0, phraseWords.size) == phraseWords
                val shortEnough = textWords.size <= phraseWords.size + WAKE_TAIL_WORD_BUDGET
                if (startsWithPhrase && shortEnough) return trigger
            }
            return null
        }

        /**
         * Resolves the target agent role for a matched trigger based on routing rules.
         */
        fun resolveTargetAgent(
            matchedTrigger: String,
            routingRules: Map<String, String>,
            defaultAgent: String = "conversational",
        ): String {
            val key = normalizeRoutingKey(matchedTrigger)
            return routingRules[key] ?: defaultAgent
        }

        fun encodeTriggers(triggers: List<String>): String =
            json.encodeToString(triggers)

        fun decodeTriggers(raw: String?): List<String> {
            if (raw.isNullOrBlank()) return listOf(DEFAULT_TRIGGER)
            return runCatching {
                val parsed: List<String> = json.decodeFromString(raw)
                normalizeTriggers(parsed)
            }.getOrDefault(listOf(DEFAULT_TRIGGER))
        }

        fun encodeRoutingRules(rules: Map<String, String>): String =
            json.encodeToString(rules)

        fun decodeRoutingRules(raw: String?): Map<String, String> {
            if (raw.isNullOrBlank()) return emptyMap()
            return runCatching {
                val parsed: Map<String, String> = json.decodeFromString(raw)
                normalizeRoutingRules(parsed)
            }.getOrDefault(emptyMap())
        }
    }
}
