package com.hermes.agent.data.llm
import com.hermes.agent.domain.llm.*
import com.hermes.agent.domain.settings.*

import com.hermes.agent.domain.tool.ToolDescriptor
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import timber.log.Timber

/**
 * Signals that the on-device tool caller declined this turn.
 *
 * An [IOException] on purpose: [RoutedProviderChain] advances to the next
 * provider on a thrown failure, and already treats a *quality* judgement as a
 * failure worth advancing on — see its empty-response guard, which throws
 * because a blank answer is a dead turn. An abstain is the same kind of
 * judgement, so handing back a low-confidence call would be the bug; throwing
 * moves the turn to the cloud, which is exactly the intent.
 */
internal class ToolCallerAbstained(reason: String) : IOException(reason)

/** Below this, the turn goes to the cloud instead. */
private const val HANDOFF_THRESHOLD = 0.60

/**
 * How many tools the caller is shown.
 *
 * Not the whole catalogue, despite that being the point of a model this small.
 * The arithmetic: ~110 tools at roughly 120 characters of schema each is ~13 KB,
 * near 3.5k tokens of prefill *per turn*, and the system block is re-processed
 * every call (see LocalLlmManager.generateResponse). That is seconds of latency
 * in front of every tool turn — the same shape of problem as the 1B model's
 * 8-tool cap, just cheaper per token.
 *
 * Measured on a TCL tablet, 40 was far too many for a 270M model: shown five or six
 * tools it asked clarifying questions, invented names (`light_off`) or picked a
 * neighbour. The tools are ranked against the request first, so three is the
 * matching tool and its closest alternatives.
 */
private const val MAX_TOOL_CALLER_TOOLS = 3

/**
 * How much of the system block the declarations may take.
 *
 * The count cap alone did not keep the prompt inside the model's 4,092-token lane: forty
 * declarations of the agent's real tools came to 22.5K characters (4,732 tokens), llama.cpp
 * refused the system prompt, and every tool turn fell through to the cloud. The native side also
 * caps system prefill at 1,536 tokens and cuts off whatever is past it, which would truncate a
 * declaration mid-schema. FunctionGemma reads declarations at about 4.8 characters a token, so
 * 6,000 characters stays under that cap with room for the preamble and recent turns.
 */
internal const val MAX_TOOL_CALLER_DECLARATION_CHARS = 6_000

/** The leading tools whose declarations fit in [maxChars], in the order they were offered. */
internal fun toolsWithinBudget(tools: List<ToolDescriptor>, maxChars: Int): List<ToolDescriptor> {
    var used = 0
    return tools.takeWhile { tool ->
        used += renderFunctionDeclarations(listOf(tool)).length
        used <= maxChars
    }
}

/**
 * A small on-device model that attempts device control before any cloud
 * provider, and abstains when it is not confident.
 *
 * This is the attempt-first half of a hybrid: rather than classifying a turn up
 * front to decide where to send it, the cheap model tries, and the structural
 * quality of what it produced decides whether the answer stands or the turn
 * moves on. Classification up front would pay small-model prefill on every turn
 * and still route most of them to the cloud.
 *
 * Deliberately not a chat model. It answers tool turns or it abstains; ordinary
 * conversation is [LocalLlmProvider]'s job, and letting a 270M model near it
 * would be a visible regression.
 */
@Singleton
class ToolCallerLlmProvider @Inject constructor(
    private val localLlmManager: LocalLlmManager,
) : LlmProvider {

    override val name: String = "On-device tool caller"
    override val isOnDevice: Boolean = true
    override val model: String = ToolCallerCatalog.DEFAULT.id

    /**
     * Never reached through the router, which only inserts this provider on
     * turns that carry tools. Guarded anyway: a caller that reaches for
     * `complete` wants prose, and this model has none worth reading.
     */
    override suspend fun complete(messages: List<LlmMessage>): LlmResponse =
        throw ToolCallerAbstained("the tool caller does not answer chat turns")

    override fun stream(messages: List<LlmMessage>): Flow<LlmStreamChunk> = flow {
        throw ToolCallerAbstained("the tool caller does not answer chat turns")
    }

    override suspend fun completeWithTools(
        messages: List<LlmMessage>,
        tools: List<ToolDescriptor>,
    ): LlmToolResponse {
        if (tools.isEmpty()) throw ToolCallerAbstained("no tools were offered")
        // Once its call has run, the round is about the result, and this model has no reply to
        // give. Asked again it repeated the call: "pause the music" toggled playback three times
        // before the repeat guard stopped the turn. A chat model writes the reply instead.
        if (messages.lastOrNull()?.role == "tool") throw ToolCallerAbstained("a tool already ran this turn")

        val turn = messages.lastOrNull { it.role == "user" }?.content.orEmpty()
        val scored = scoreToolsForTurn(tools, turn)
        // Nothing in the request names any tool: the small model would only guess, and on
        // the tablet it guessed wrong or asked a question. Hand the turn on without running it.
        val lead = scored.first().first.takeIf { toolRelevance(it, turn) > 0.0 }
            ?: throw ToolCallerAbstained("the request names none of the offered tools")
        // One stray shared word is not a request for the tool: "Install the canvas-design
        // skill from the Skills Hub" matched media_control's "installed music app" and the
        // model, shown only that tool, toggled playback.
        if (toolCoverage(lead, turn) < MIN_TOOL_COVERAGE) {
            throw ToolCallerAbstained("the request is mostly about something ${lead.name} does not cover")
        }
        val listed = toolsWithinBudget(toolsToShow(scored, MAX_TOOL_CALLER_TOOLS), MAX_TOOL_CALLER_DECLARATION_CHARS)
        if (listed.isEmpty()) throw ToolCallerAbstained("no tool declaration fits the tool caller's context")
        val prompt = buildToolCallerPrompt(messages, listed)
        if (prompt.conversation.isBlank()) throw ToolCallerAbstained("no user turn to act on")

        val startedAt = System.currentTimeMillis()
        val raw = StringBuilder()
        // Stop at the first finished call. The model does not end its turn there on its own: it
        // went on emitting unrelated calls for 5K characters and 50 seconds, and the extra calls
        // (all scored, lowest wins) sank the one it had got right.
        localLlmManager
            .generateResponse(LocalModelRole.TOOL_CALLER, prompt.system, prompt.conversation)
            .takeWhile { !raw.contains(CALL_CLOSE) }
            .collect { raw.append(it) }
        val elapsedMs = System.currentTimeMillis() - startedAt

        val answer = raw.toString().trim()

        // What the model actually wrote. Without this an abstain says only that
        // no call was found, which reads the same whether the model declined,
        // answered in prose, or emitted a call in a format this code does not
        // parse — three different problems with three different fixes.
        //
        // Debuggable builds only: FileLogTree is planted on every build type and
        // writes every priority to an exportable file, and this line carries
        // model output derived from whatever the user typed.
        if (localLlmManager.isDebuggable) {
            Timber.tag(LOG_TAG).d(
                "raw reply (%d chars, %d char system prompt): %s",
                answer.length,
                prompt.system.length,
                answer.take(RAW_REPLY_LOG_CHARS).ifEmpty { "<empty>" },
            )
        }

        if (answer.isEmpty()) {
            abstain(ToolCallConfidence(0.0, "model returned nothing"), listed.size, elapsedMs)
        }

        val (leftover, calls) = parseFunctionGemmaCalls(answer)
        val confidence = scoreToolCalls(calls, listed, leftover)

        if (calls.isEmpty() || confidence.score < HANDOFF_THRESHOLD) {
            abstain(confidence, listed.size, elapsedMs)
        }
        // A well-formed call to the wrong tool scores 1.0: asked to set the media volume the
        // model called media_control play_pause. Only the tool the request points to is trusted.
        calls.firstOrNull { it.name != lead.name }?.let { stray ->
            abstain(
                ToolCallConfidence(0.0, "called '${stray.name}' but the request points to '${lead.name}'"),
                listed.size,
                elapsedMs,
            )
        }

        // Step 1 is a measurement exercise: these two lines are what says whether
        // a second resident model earns its place, and whether the tool cap can rise.
        Timber.tag(LOG_TAG).i(
            "accepted %d call(s) in %d ms (%d tools shown, confidence %.2f)",
            calls.size, elapsedMs, listed.size, confidence.score,
        )
        return LlmToolResponse(
            // Anything the model wrote alongside the call is working-out, not
            // an answer: the tool result is what the turn is for.
            content = "",
            toolCalls = calls,
            tokensUsed = 0,
            model = model,
            finishReason = "tool_calls",
        )
    }

    override fun streamWithTools(
        messages: List<LlmMessage>,
        tools: List<ToolDescriptor>,
    ): Flow<LlmStreamChunk> = flow {
        // No partial output: a call is emitted whole or the turn is handed on,
        // and streaming tokens the chain may discard would show the user text
        // from a provider that is about to abstain.
        val response = completeWithTools(messages, tools)
        response.toolCalls.forEach { emit(LlmStreamChunk.ToolCallDelta(it)) }
        emit(LlmStreamChunk.Done)
    }

    override suspend fun isAvailable(): Boolean = localLlmManager.isToolCallerDownloaded()

    private fun abstain(
        confidence: ToolCallConfidence,
        toolsShown: Int,
        elapsedMs: Long,
    ): Nothing {
        Timber.tag(LOG_TAG).i(
            "abstained after %d ms (%d tools shown, confidence %.2f): %s",
            elapsedMs, toolsShown, confidence.score, confidence.reason,
        )
        throw ToolCallerAbstained(confidence.reason)
    }

    private companion object {
        const val LOG_TAG = "ToolCaller"

        /** Logcat drops a line past ~4 KB; this keeps the reply well inside it. */
        const val RAW_REPLY_LOG_CHARS = 600
    }
}

/**
 * The tool caller's prompt: the model's own declaration block, then the turn.
 *
 * Shares [LocalPrompt] with the chat path but not its builder — that one trims
 * the system block to 3 KB and appends chat instructions, both of which would
 * wreck a prompt whose payload is a function-declaration block.
 *
 * There is deliberately almost no instruction text here. FunctionGemma was
 * trained to answer a declaration block, not to follow prose about how to
 * format a call, and the previous version of this prompt — a markdown tool list
 * plus a request for a `<tool_call>` envelope — produced exactly one thing on
 * device: the model reaching for its own `<start_function_call>` token, having
 * nothing to name, and replying that the query was unrelated to the functions
 * available to it.
 */
internal fun buildToolCallerPrompt(
    messages: List<LlmMessage>,
    tools: List<ToolDescriptor>,
    maxHistoryChars: Int = 600,
): LocalPrompt {
    val nonSystem = messages.filterNot { it.role == "system" }
    // A tool result is a live turn as well — see the same selection in
    // [buildLocalPrompt]. Taking only the newest user message drops the tool
    // result from the prompt entirely and the loop repeats the call forever.
    val liveIndex = nonSystem.indexOfLast(::isLiveTurn)
    // History and the live turn are untrusted (a tool result can be a web page);
    // only the declarations below may carry real control tokens.
    val liveTurn = neutralizeControlTokens(nonSystem.getOrNull(liveIndex)?.content?.trim().orEmpty())

    // A short tail of history, because device control leans on it constantly:
    // "turn it off", "do that again", "the other one" mean nothing alone.
    val history = nonSystem.take(maxOf(liveIndex, 0))
        .joinToString("\n") { "${it.role}: ${it.content.trim()}" }
        .takeLast(maxHistoryChars)
        .let(::neutralizeControlTokens)

    // Exactly what the model's template renders for a turn with tools and no system
    // message: its trained preamble, then the declarations, nothing between them. A
    // sentence of our own plus a block of history in between is a developer turn the
    // model never saw in training, and it answered with invented names like
    // `flashlight,false`. The history goes with the user's words instead.
    // History only when the request points back at it ("turn it off", "do that again"). Given
    // unrelated history the model filled a flashlight call with a file name from three turns ago.
    val system = TOOL_CALLER_PREAMBLE + renderFunctionDeclarations(tools)
    val conversation = if (history.isBlank() || !REFERS_BACK.containsMatchIn(liveTurn)) {
        liveTurn
    } else {
        "$history\nuser: $liveTurn"
    }

    return LocalPrompt(system = system, conversation = conversation)
}

/** The developer-turn preamble FunctionGemma's chat template uses when tools are offered. */
internal const val TOOL_CALLER_PREAMBLE = "You are a model that can do function calling with the following functions"

/**
 * [tools] with the ones that share words with [turn] first, otherwise in their given order.
 *
 * Only the leading few declarations fit the tool caller's context, and those used to be the first
 * few in catalogue order whatever was asked, so "turn off the flashlight" could be shown six tools
 * none of which controls it. Word overlap is crude, but it is cheap and it keeps the tool the user
 * named inside the budget.
 */
internal fun rankToolsForTurn(tools: List<ToolDescriptor>, turn: String): List<ToolDescriptor> =
    scoreToolsForTurn(tools, turn).map { it.first }

/** [tools] with their relevance to [turn], best first; ties keep the given order. */
internal fun scoreToolsForTurn(tools: List<ToolDescriptor>, turn: String): List<Pair<ToolDescriptor, Double>> {
    val words = requestWords(turn)
    if (words.isEmpty()) return tools.map { it to 0.0 }
    val texts = tools.map { rankingWords(it) }
    // A word most tools mention ("set", "turn") says little; one only a few mention
    // ("flashlight") says which tool is meant. Each match counts 1 / tools matching it.
    val weight = words.associateWith { word -> texts.count { it.contains(word) }.let { if (it == 0) 0.0 else 1.0 / it } }
    return tools.indices
        .map { i -> tools[i] to words.sumOf { if (texts[i].contains(it)) weight.getValue(it) else 0.0 } }
        .withIndex()
        .sortedWith(compareByDescending<IndexedValue<Pair<ToolDescriptor, Double>>> { it.value.second }.thenBy { it.index })
        .map { it.value }
}

/**
 * The tools to declare: the leader alone when it clearly outscores the rest, otherwise the
 * leader and the tools close to it. Shown a single tool the model can only call it or decline,
 * which is what an attempt-first caller wants; shown neighbours it drifted to them.
 */
internal fun toolsToShow(scored: List<Pair<ToolDescriptor, Double>>, max: Int): List<ToolDescriptor> {
    val lead = scored.firstOrNull() ?: return emptyList()
    return scored.take(max)
        .filterIndexed { i, (_, score) -> i == 0 || score >= lead.second * CLOSE_SCORE_RATIO }
        .map { it.first }
}

/** A tool within this fraction of the leader's score is a plausible alternative, and is shown too. */
private const val CLOSE_SCORE_RATIO = 0.75

/** How many of the request's meaningful words [tool] mentions. */
internal fun toolRelevance(tool: ToolDescriptor, turn: String): Double {
    val text = rankingWords(tool)
    return requestWords(turn).count { text.contains(it) }.toDouble()
}

/** The share of the request's meaningful words that [tool] mentions. */
internal fun toolCoverage(tool: ToolDescriptor, turn: String): Double {
    val words = requestWords(turn)
    return if (words.isEmpty()) 0.0 else toolRelevance(tool, turn) / words.size
}

/** Below this share of the request's words, the lead tool is a coincidence, not the request. */
internal const val MIN_TOOL_COVERAGE = 0.34

private fun requestWords(turn: String): Set<String> = words(turn).filter { it !in RANKING_STOPWORDS }.toSet()

/** Whole words, plural-folded ("tracks" -> "track"); substring matching let "install" hit "installed". */
private fun words(text: String): List<String> =
    text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }.map(::singular)

private fun singular(word: String): String = when {
    word.length > 4 && word.endsWith("ies") -> word.dropLast(3) + "y"
    word.length > 3 && word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)
    else -> word
}

private fun rankingWords(tool: ToolDescriptor): Set<String> = words(rankingText(tool)).toSet()

/** Name, description and parameters, with snake_case split so `media_volume` reads "media volume". */
private fun rankingText(tool: ToolDescriptor): String =
    (tool.name + " " + tool.description + " " +
        tool.parameters.joinToString(" ") { it.name + " " + it.description + " " + it.enumValues.orEmpty().joinToString(" ") })
        .replace('_', ' ')
        .lowercase()

/** Words that name no tool, dropped before ranking. */
private val RANKING_STOPWORDS = setOf(
    "the", "and", "for", "you", "your", "my", "please", "can", "could", "would", "will", "with", "this",
    "that", "what", "when", "then", "now", "quick", "ultrabrain", "turn", "make", "get", "set", "put",
)

/** A request that only makes sense with what came before it. */
private val REFERS_BACK = Regex("""\b(it|that|this|them|those|again|same|other|one)\b""", RegexOption.IGNORE_CASE)
