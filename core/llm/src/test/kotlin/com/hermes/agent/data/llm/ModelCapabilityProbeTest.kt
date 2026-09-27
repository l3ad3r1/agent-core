package com.hermes.agent.data.llm

import com.hermes.agent.data.llm.ModelCapabilityProbe.Check
import com.hermes.agent.domain.llm.LlmMessage
import com.hermes.agent.domain.llm.LlmProvider
import com.hermes.agent.domain.llm.LlmResponse
import com.hermes.agent.domain.llm.LlmStreamChunk
import com.hermes.agent.domain.llm.LlmToolResponse
import com.hermes.agent.domain.llm.ToolCall
import com.hermes.agent.domain.tool.ToolDescriptor
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCapabilityProbeTest {

    private val probe = ModelCapabilityProbe(mockk())

    /** Answers the probe's prompts the way a capable model would. */
    private open class CapableModel : LlmProvider {
        override val name = "fake"
        override val isOnDevice = false
        override val model = "fake-1"
        override suspend fun isAvailable() = true

        override suspend fun complete(messages: List<LlmMessage>): LlmResponse {
            val last = messages.last().content
            val text = if ("code word?" in last) "KESTREL" else "PONG"
            return LlmResponse(text, 1, model)
        }

        override fun stream(messages: List<LlmMessage>): Flow<LlmStreamChunk> =
            flowOf(LlmStreamChunk.Delta("PO"), LlmStreamChunk.Delta("NG"), LlmStreamChunk.Done)

        override suspend fun completeWithTools(messages: List<LlmMessage>, tools: List<ToolDescriptor>): LlmToolResponse =
            if (messages.last().role == "tool") {
                LlmToolResponse("It is 17°C and cloudy in Paris.", emptyList(), 1, model, "stop")
            } else {
                val call = ToolCall("call_1", "get_weather", mapOf("city" to JsonPrimitive("Paris")))
                LlmToolResponse("", listOf(call), 1, model, "tool_calls")
            }
    }

    @Test
    fun `a capable model passes every check`() = runTest {
        val results = probe.run(CapableModel())

        assertEquals(Check.entries, results.map { it.check })
        results.forEach { assertTrue("${it.check}: ${it.detail}", it.passed) }
    }

    @Test
    fun `a model that answers in text instead of calling the tool fails only that check`() = runTest {
        var toolMessageSeen: LlmMessage? = null
        val model = object : CapableModel() {
            override suspend fun completeWithTools(messages: List<LlmMessage>, tools: List<ToolDescriptor>): LlmToolResponse {
                if (messages.last().role == "tool") {
                    toolMessageSeen = messages.last()
                    return LlmToolResponse("17 degrees.", emptyList(), 1, model, "stop")
                }
                return LlmToolResponse("I cannot check the weather.", emptyList(), 1, model, "stop")
            }
        }

        val results = probe.run(model).associateBy { it.check }

        assertFalse(results.getValue(Check.TOOL_CALL).passed)
        assertTrue(results.getValue(Check.TOOL_CALL).detail.contains("instead of calling the tool"))
        // The continuation check still runs, on a stand-in call.
        assertTrue(results.getValue(Check.TOOL_RESULT).passed)
        assertEquals("call_probe_1", toolMessageSeen?.toolCallId)
        assertTrue(results.getValue(Check.REPLY).passed)
    }

    @Test
    fun `a streaming error and a silent model are failures, not crashes`() = runTest {
        val model = object : CapableModel() {
            override fun stream(messages: List<LlmMessage>): Flow<LlmStreamChunk> =
                flowOf(LlmStreamChunk.Error("HTTP 400: stream not supported"))

            override suspend fun complete(messages: List<LlmMessage>): LlmResponse {
                if ("code word?" in messages.last().content) delay(120_000)
                return super.complete(messages)
            }
        }

        val results = probe.run(model).associateBy { it.check }

        assertFalse(results.getValue(Check.STREAMING).passed)
        assertTrue(results.getValue(Check.STREAMING).detail.contains("stream not supported"))
        assertFalse(results.getValue(Check.MULTI_TURN).passed)
        assertTrue(results.getValue(Check.MULTI_TURN).detail.contains("No answer within"))
    }

    @Test
    fun `tool results become the reliability routing uses, and no answer changes nothing`() {
        fun run(call: Boolean, result: Boolean, inconclusive: Boolean = false) = listOf(
            ModelCapabilityProbe.CheckResult(Check.TOOL_CALL, call, "", inconclusive = inconclusive),
            ModelCapabilityProbe.CheckResult(Check.TOOL_RESULT, result, ""),
        )
        assertEquals(0.85, ModelCapabilityProbe.toolReliability(run(true, true), current = 0.6)!!, 0.0)
        assertEquals("a better preset estimate is kept", 0.95, ModelCapabilityProbe.toolReliability(run(true, true), 0.95)!!, 0.0)
        assertEquals(0.2, ModelCapabilityProbe.toolReliability(run(false, true), 0.9)!!, 0.0)
        assertEquals(0.5, ModelCapabilityProbe.toolReliability(run(true, false), 0.9)!!, 0.0)
        assertEquals(null, ModelCapabilityProbe.toolReliability(run(false, false, inconclusive = true), 0.9))
    }

    @Test
    fun `a measurement only counts for the model it was taken on`() {
        val profile = com.hermes.agent.domain.settings.CloudProviderProfile(
            id = "openrouter", name = "OpenRouter", baseUrl = "https://openrouter.ai/api/v1", model = "cheap-chat",
            apiKey = "k", quality = 0.7, cost = 0.2, latency = 0.7, toolReliability = 0.9,
            measuredToolReliability = 0.2, measuredModel = "cheap-chat",
        )
        assertEquals(0.2, profile.effectiveToolReliability, 0.0)
        assertEquals("switching model drops the old measurement", 0.9, profile.copy(model = "big-tools").effectiveToolReliability, 0.0)
        assertEquals(0.9, profile.copy(measuredToolReliability = null).effectiveToolReliability, 0.0)
    }

    @Test
    fun `a check that got no answer is marked inconclusive`() = runTest {
        val model = object : CapableModel() {
            override suspend fun completeWithTools(messages: List<LlmMessage>, tools: List<ToolDescriptor>): LlmToolResponse =
                throw java.io.IOException("HTTP 503")
        }

        val results = probe.run(model).associateBy { it.check }

        assertTrue(results.getValue(Check.TOOL_CALL).inconclusive)
        assertEquals(null, ModelCapabilityProbe.toolReliability(results.values.toList(), 0.9))
    }

    @Test
    fun `a call with the wrong arguments does not pass`() = runTest {
        val model = object : CapableModel() {
            override suspend fun completeWithTools(messages: List<LlmMessage>, tools: List<ToolDescriptor>): LlmToolResponse =
                if (messages.last().role == "tool") {
                    LlmToolResponse("Nice weather.", emptyList(), 1, model, "stop")
                } else {
                    LlmToolResponse("", listOf(ToolCall("c", "get_weather", emptyMap())), 1, model, "tool_calls")
                }
        }

        val results = probe.run(model).associateBy { it.check }

        assertFalse(results.getValue(Check.TOOL_CALL).passed)
        assertFalse("never mentioned the tool's temperature", results.getValue(Check.TOOL_RESULT).passed)
    }
}
