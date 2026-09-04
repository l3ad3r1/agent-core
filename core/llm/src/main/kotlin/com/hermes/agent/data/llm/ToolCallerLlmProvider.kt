package com.hermes.agent.data.llm
import com.hermes.agent.domain.llm.*
import com.hermes.agent.domain.settings.*

import com.hermes.agent.domain.tool.ToolDescriptor
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
 * 40 is a starting point, five times what the chat model gets, chosen to be
 * measurable rather than to be right. Whether it can rise is what step 1's
 * timing log is for; a stable system prompt across turns should also be cacheable
 * natively, which would make the cap moot.
 */
private const val MAX_TOOL_CALLER_TOOLS = 40

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

        val listed = tools.take(MAX_TOOL_CALLER_TOOLS)
        val prompt = buildToolCallerPrompt(messages, listed)
        if (prompt.conversation.isBlank()) throw ToolCallerAbstained("no user turn to act on")

        val startedAt = System.currentTimeMillis()
        val raw = StringBuilder()
        localLlmManager
            .generateResponse(LocalModelRole.TOOL_CALLER, prompt.system, prompt.conversation)
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
    val liveIndex = nonSystem.indexOfLast { it.role == "user" }
    val liveTurn = nonSystem.getOrNull(liveIndex)?.content?.trim().orEmpty()

    // A short tail of history, because device control leans on it constantly:
    // "turn it off", "do that again", "the other one" mean nothing alone.
    val history = nonSystem.take(maxOf(liveIndex, 0))
        .joinToString("\n") { "${it.role}: ${it.content.trim()}" }
        .takeLast(maxHistoryChars)

    // The template emits this content into the developer turn immediately
    // before where its own declaration loop would run, so the blocks land
    // exactly where the model expects them.
    val system = buildString {
        append("You call functions on the user's phone.")
        if (history.isNotBlank()) append("\n\nRecent turns:\n").append(history)
        append(renderFunctionDeclarations(tools))
    }

    return LocalPrompt(system = system, conversation = liveTurn)
}
