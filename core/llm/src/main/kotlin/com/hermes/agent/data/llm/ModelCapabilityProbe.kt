package com.hermes.agent.data.llm

import com.hermes.agent.domain.llm.LlmMessage
import com.hermes.agent.domain.llm.LlmProvider
import com.hermes.agent.domain.llm.LlmStreamChunk
import com.hermes.agent.domain.llm.ToolCall
import com.hermes.agent.domain.settings.CloudProviderProfile
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Checks what a configured model can actually do before it is trusted with chat.
 *
 * A model list says a model exists, not that it can run the agent: plenty answer
 * a plain prompt but never call a tool, ignore the tool result, or break on
 * streaming. Each check sends a tiny request (a few tokens) and passes only on an
 * exact, checkable answer, so a result means the same thing on every provider.
 */
@Singleton
class ModelCapabilityProbe @Inject constructor(
    private val providers: ProfileCloudProviderFactory,
) {
    enum class Check(val label: String) {
        REPLY("Answers a prompt"),
        STREAMING("Streams its answer"),
        TOOL_CALL("Calls a tool"),
        TOOL_RESULT("Uses a tool's result"),
        MULTI_TURN("Follows the conversation"),
    }

    /**
     * [inconclusive]: the check never got an answer (timeout, network, HTTP error), so
     * it says nothing about what the model can do.
     */
    data class CheckResult(
        val check: Check,
        val passed: Boolean,
        val detail: String,
        val inconclusive: Boolean = false,
    )


    /** Runs every check against [profile]'s model, in order. */
    suspend fun run(profile: CloudProviderProfile): List<CheckResult> = run(providers.create(profile))

    suspend fun run(provider: LlmProvider): List<CheckResult> {
        val results = mutableListOf<CheckResult>()
        var toolCall: ToolCall? = null
        for (check in Check.entries) {
            results += attempt(check) {
                when (check) {
                    Check.REPLY -> reply(provider)
                    Check.STREAMING -> streaming(provider)
                    Check.TOOL_CALL -> callTool(provider).also { toolCall = it.second }.first
                    Check.TOOL_RESULT -> useToolResult(provider, toolCall)
                    Check.MULTI_TURN -> multiTurn(provider)
                }
            }
        }
        return results
    }

    private suspend fun attempt(check: Check, block: suspend () -> CheckResult): CheckResult = try {
        withTimeout(CHECK_TIMEOUT_MS) { block() }
    } catch (e: CancellationException) {
        // A timeout is a result; a cancelled screen is not.
        if (e is kotlinx.coroutines.TimeoutCancellationException) {
            CheckResult(check, false, "No answer within ${CHECK_TIMEOUT_MS / 1000} s", inconclusive = true)
        } else {
            throw e
        }
    } catch (e: Exception) {
        CheckResult(check, false, e.message?.take(200) ?: e.javaClass.simpleName, inconclusive = true)
    }

    private suspend fun reply(provider: LlmProvider): CheckResult {
        val answer = provider.complete(pongPrompt).content
        return verdict(Check.REPLY, answer.contains(PONG, ignoreCase = true), answer)
    }

    private suspend fun streaming(provider: LlmProvider): CheckResult {
        val text = StringBuilder()
        var deltas = 0
        provider.stream(pongPrompt).collect { chunk ->
            when (chunk) {
                is LlmStreamChunk.Delta -> { text.append(chunk.text); deltas++ }
                is LlmStreamChunk.Error -> throw IllegalStateException(chunk.message)
                else -> Unit
            }
        }
        val passed = deltas > 0 && text.contains(PONG, ignoreCase = true)
        return verdict(Check.STREAMING, passed, text.toString())
    }

    private suspend fun callTool(provider: LlmProvider): Pair<CheckResult, ToolCall?> {
        val response = provider.completeWithTools(
            listOf(system, LlmMessage("user", WEATHER_QUESTION)),
            listOf(weatherTool),
        )
        val call = response.toolCalls.firstOrNull { it.name == weatherTool.name }
        val city = (call?.arguments?.get("city") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val result = when {
            call == null && response.toolCalls.isEmpty() ->
                CheckResult(Check.TOOL_CALL, false, "Answered in text instead of calling the tool: ${response.content.take(120)}")
            call == null -> CheckResult(Check.TOOL_CALL, false, "Called ${response.toolCalls.first().name} instead")
            !city.contains("paris", ignoreCase = true) ->
                CheckResult(Check.TOOL_CALL, false, "Called the tool without the city (got \"$city\")")
            else -> CheckResult(Check.TOOL_CALL, true, "Called ${call.name}(city=$city)")
        }
        return result to call
    }

    /** Continues from the model's own call when it made one, or a stand-in call otherwise. */
    private suspend fun useToolResult(provider: LlmProvider, call: ToolCall?): CheckResult {
        val used = call ?: ToolCall(STAND_IN_CALL_ID, weatherTool.name, mapOf("city" to JsonPrimitive("Paris")))
        val response = provider.completeWithTools(
            listOf(
                system,
                LlmMessage("user", WEATHER_QUESTION),
                LlmMessage("assistant", "", toolCalls = listOf(used)),
                LlmMessage("tool", """{"city":"Paris","temperature_c":$TOOL_TEMPERATURE,"sky":"cloudy"}""", toolCallId = used.id),
            ),
            listOf(weatherTool),
        )
        return verdict(Check.TOOL_RESULT, response.content.contains(TOOL_TEMPERATURE), response.content)
    }

    private suspend fun multiTurn(provider: LlmProvider): CheckResult {
        val answer = provider.complete(
            listOf(
                system,
                LlmMessage("user", "Remember this code word: $CODE_WORD. Reply only with OK."),
                LlmMessage("assistant", "OK"),
                LlmMessage("user", "What was the code word? Reply with just the word."),
            ),
        ).content
        return verdict(Check.MULTI_TURN, answer.contains(CODE_WORD, ignoreCase = true), answer)
    }

    private fun verdict(check: Check, passed: Boolean, answer: String): CheckResult {
        val shown = answer.trim().replace(Regex("\\s+"), " ").take(120).ifBlank { "(empty reply)" }
        return CheckResult(check, passed, if (passed) "OK" else "Unexpected reply: $shown")
    }

    companion object {
        /** What routing requires of a model before it gets tool-heavy turns. */
        const val RELIABLE_TOOLS = 0.85

        /**
         * The tool reliability [results] show, for routing: passing both tool checks
         * clears the bar for tool-heavy turns; failing to call a tool at all puts the
         * model well below it. Null when the run tells nothing (a check did not run
         * or got no answer).
         */
        fun toolReliability(results: List<CheckResult>, current: Double): Double? {
            val call = results.firstOrNull { it.check == Check.TOOL_CALL } ?: return null
            val result = results.firstOrNull { it.check == Check.TOOL_RESULT } ?: return null
            if (call.inconclusive || result.inconclusive) return null
            return when {
                call.passed && result.passed -> maxOf(current, RELIABLE_TOOLS)
                !call.passed -> 0.2
                else -> 0.5
            }
        }

        private const val CHECK_TIMEOUT_MS = 60_000L
        private const val PONG = "PONG"
        private const val CODE_WORD = "KESTREL"
        private const val TOOL_TEMPERATURE = "17"
        private const val STAND_IN_CALL_ID = "call_probe_1"
        private const val WEATHER_QUESTION = "What is the weather in Paris right now? Use the get_weather tool."

        private val system = LlmMessage("system", "You are being tested. Follow each instruction exactly and briefly.")
        private val pongPrompt = listOf(system, LlmMessage("user", "Reply with exactly the word PONG and nothing else."))

        private val weatherTool = ToolDescriptor(
            name = "get_weather",
            description = "Get the current weather for a city.",
            parameters = listOf(
                ToolParameter("city", ToolParameterType.STRING, "The city name, such as Paris.", required = true),
            ),
        )
    }
}
