package com.hermes.agent.data.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Dates as tools receive and report them.
 *
 * Tools used to take only epoch milliseconds from the model. Models are poor at that arithmetic:
 * asked for "Friday 2 October 2026" they sent a 2025 timestamp, the event was stored a year early,
 * and the reply still said 2026 because the result echoed the raw number back. So a date may
 * also be an ISO local date-time in the user's zone, and every result names the date in words.
 */
object ToolTime {

    /** Ages beyond this, a create is refused as a likely date mistake unless allow_past is set. */
    const val PAST_TOLERANCE_MS = 24 * 60 * 60 * 1000L

    /** How a date parameter is described to the model. */
    const val INPUT_HINT = "Local date-time like 2026-10-02T16:00 (preferred), a date like 2026-10-02, or epoch milliseconds."

    private val ISO_OFFSET = Regex(""".*(Z|[+-]\d{2}:?\d{2})$""")
    private val WORDS = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm", Locale.ENGLISH)

    sealed interface Parsed {
        data class Ok(val epochMs: Long) : Parsed
        data class Invalid(val raw: String) : Parsed
    }

    /** Null when the parameter is absent. */
    fun parse(element: JsonElement?, zone: ZoneId = ZoneId.systemDefault()): Parsed? {
        val primitive = element as? JsonPrimitive ?: return null
        val raw = primitive.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        raw.toLongOrNull()?.let { return Parsed.Ok(it) }
        raw.toDoubleOrNull()?.let { return Parsed.Ok(it.toLong()) }
        val text = raw.replace(' ', 'T')
        val parsed = runCatching {
            when {
                text.length == 10 -> LocalDate.parse(text).atStartOfDay(zone).toInstant()
                ISO_OFFSET.matches(text) -> OffsetDateTime.parse(text).toInstant()
                else -> LocalDateTime.parse(text).atZone(zone).toInstant()
            }
        }.getOrNull()
        return parsed?.let { Parsed.Ok(it.toEpochMilli()) } ?: Parsed.Invalid(raw)
    }

    /** "Fri 2 Oct 2026, 16:00" in the user's zone. */
    fun words(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        WORDS.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    fun invalidMessage(param: String, raw: String): String =
        "'$param' could not be read as a date: '$raw'. Use a local date-time like 2026-10-02T16:00."

    /** The refusal for a create that lands well in the past, naming both dates so the model can see its mistake. */
    fun pastMessage(param: String, epochMs: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "'$param' resolves to ${words(epochMs, zone)}, which is in the past (it is now ${words(now, zone)}). " +
            "If you meant a future date, send it as a local date-time like 2026-10-02T16:00. " +
            "To record a past date on purpose, repeat the call with allow_past=true."
}
