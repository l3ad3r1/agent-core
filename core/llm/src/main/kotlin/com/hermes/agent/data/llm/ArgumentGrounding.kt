package com.hermes.agent.data.llm

import com.hermes.agent.domain.llm.ToolCall
import kotlinx.serialization.json.JsonPrimitive

/**
 * Catches a tool call whose time-like number the user never said.
 *
 * [scoreToolCalls] judges the *shape* of a call, so a well-formed `set_alarm(hour=1, minute=30)`
 * scored 1.00 for "wake me up at 6:30 tomorrow": the model had picked the right tool and
 * invented the hour. An alarm at the wrong time is worse than a call that goes to the cloud, so
 * a numeric argument that measures time must be traceable to the request.
 *
 * Traceable means the number appears in the request (digits or a number word), or follows from
 * one: +12 for an hour the user places in the afternoon ("6:30pm", "tonight" -> 18; a bare "6:30"
 * stays 6, so a model that picks 18 on its own is refused), and minutes/hours expressed in seconds
 * for a duration ("ten minutes" -> 600). Noon and midnight are 12 and 0/24. Anything else is
 * refused, including phrasings this does not understand; the turn then goes to a bigger model,
 * which is the safe direction to be wrong in. Not caught: a right number in the wrong unit
 * (10 seconds for "ten minutes").
 */

/** Words in an argument name that mark a time quantity the user states. Limits and ids are not checked. */
private val TIME_WORDS = setOf("hour", "minute", "min", "second", "sec", "duration", "delay", "timeout", "day", "month", "year")

private fun holdsTime(argumentName: String): Boolean =
    argumentName.lowercase().split('_', '-').any { it.removeSuffix("s") in TIME_WORDS }

private val NUMBER_WORDS = mapOf(
    "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
    "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
    "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
    "eighteen" to 18, "nineteen" to 19, "twenty" to 20, "thirty" to 30, "forty" to 40,
    "fifty" to 50, "sixty" to 60, "noon" to 12, "midnight" to 0, "half" to 30, "quarter" to 15,
)

/** Every number [turn] states or directly implies. */
internal fun numbersIn(turn: String): Set<Long> {
    val said = mutableSetOf<Long>()
    Regex("""\d+""").findAll(turn).forEach { m -> m.value.toLongOrNull()?.let(said::add) }
    Regex("""[a-z]+""").findAll(turn.lowercase()).forEach { m -> NUMBER_WORDS[m.value]?.let { said.add(it.toLong()) } }
    if (Regex("""(?i)\bmidnight\b""").containsMatchIn(turn)) said.add(24)
    if (Regex("""(?i)\bhalf\b""").containsMatchIn(turn)) said.add(30)
    if (Regex("""(?i)\b(an|one)\s+hour\b""").containsMatchIn(turn)) said.add(1)

    val afternoon = Regex("""(?i)(\d\s*p\.?m|afternoon|evening|tonight|night)""").containsMatchIn(turn)
    val derived = mutableSetOf<Long>()
    for (n in said) {
        derived += n
        if (n in 1..11 && afternoon) derived += n + 12
        derived += n * 60 // minutes -> seconds, hours -> minutes
        derived += n * 3600 // hours -> seconds
        derived += n * 86_400 // days -> seconds
    }
    return derived
}

/**
 * The first time-like numeric argument in [calls] that [turn] does not account for, described for
 * the abstain log, or null when every one is grounded.
 */
internal fun ungroundedTimeArgument(calls: List<ToolCall>, turn: String): String? {
    val grounded = numbersIn(turn)
    for (call in calls) {
        for ((name, value) in call.arguments) {
            if (!holdsTime(name)) continue
            val number = (value as? JsonPrimitive)?.content?.trim()?.toLongOrNull() ?: continue
            // "at 7" / "for ten minutes" leave the minute (or second) unsaid; zero is the default.
            if (number == 0L && Regex("""(?i)(minute|second|sec|min)""").containsMatchIn(name)) continue
            if (number !in grounded) {
                return "'$name' is $number in ${call.name}, but the request never says or implies that number"
            }
        }
    }
    return null
}
