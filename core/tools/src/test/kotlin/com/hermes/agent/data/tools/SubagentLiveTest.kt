package com.hermes.agent.data.tools

import com.hermes.agent.data.llm.LlmRouter
import com.hermes.agent.data.llm.RoutingDecision
import com.hermes.agent.data.tool.ToolCallExecutor
import com.hermes.agent.domain.llm.LlmMessage
import com.hermes.agent.domain.llm.LlmProvider
import com.hermes.agent.domain.llm.LlmResponse
import com.hermes.agent.domain.llm.LlmStreamChunk
import com.hermes.agent.domain.llm.LlmToolResponse
import com.hermes.agent.domain.llm.ToolCall
import com.hermes.agent.domain.product.ProductIdentity
import com.hermes.agent.domain.repository.AgentTaskRepository
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolRegistry
import com.hermes.agent.domain.tool.ToolResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentLiveTest {

    /** Round 1 calls the calculator; round 2 waits on [secondRound] (or forever). Records every prompt. */
    private class ScriptedProvider(private val secondRound: CompletableDeferred<LlmToolResponse>? = null) : LlmProvider {
        override val name = "fake"
        override val isOnDevice = false
        override val model = "fake-1"
        val prompts = mutableListOf<List<LlmMessage>>()
        private var round = 0
        override suspend fun complete(messages: List<LlmMessage>) = LlmResponse("final", 0, model)
        override fun stream(messages: List<LlmMessage>): Flow<LlmStreamChunk> = flowOf(LlmStreamChunk.Done)
        override suspend fun isAvailable() = true
        override suspend fun completeWithTools(messages: List<LlmMessage>, tools: List<ToolDescriptor>): LlmToolResponse {
            prompts += messages
            return if (round++ == 0) {
                LlmToolResponse(
                    content = "Checking the numbers first.",
                    toolCalls = listOf(ToolCall("c1", "calculator", mapOf("expression" to JsonPrimitive("6*7")))),
                    tokensUsed = 0, model = model, finishReason = "tool_calls",
                )
            } else {
                secondRound?.await() ?: awaitCancellation()
            }
        }
    }

    private fun delegate(provider: LlmProvider, monitor: SubagentMonitor): DelegateTool {
        val router = mockk<LlmRouter>()
        coEvery { router.route(any(), any()) } returns RoutingDecision.Ready(provider, "test")
        val calculator = mockk<Tool>()
        every { calculator.descriptor } returns ToolDescriptor("calculator", "Calculate", emptyList())
        val registry = mockk<ToolRegistry>()
        every { registry.all() } returns listOf(calculator)
        val executor = mockk<ToolCallExecutor>()
        coEvery { executor.execute(any(), any()) } returns ToolResult.ok("42")
        return DelegateTool(
            router = router,
            toolRegistry = dagger.Lazy { registry },
            toolCallExecutor = dagger.Lazy { executor },
            agentTaskRepository = dagger.Lazy { mockk<AgentTaskRepository>(relaxed = true) },
            productIdentity = ProductIdentity("Hermes", "hermes_notify"),
            subagentMonitor = monitor,
        )
    }

    @Test
    fun `a running subagent is listed with its activity and leaves the list when done`() = runTest {
        val monitor = SubagentMonitor()
        val release = CompletableDeferred<LlmToolResponse>()
        val tool = delegate(ScriptedProvider(release), monitor)

        val call = async { tool.execute(mapOf("prompt" to JsonPrimitive("Work out 6 times 7"))) }
        testScheduler.runCurrent()

        val run = monitor.runs.value.single()
        assertEquals("Work out 6 times 7", run.goal)
        assertTrue(run.lines.toString(), run.lines.any { it.startsWith("← calculator: 42") })

        release.complete(LlmToolResponse("It is 42.", emptyList(), 0, "fake-1", "stop"))
        val result = call.await()
        assertTrue(result.output, result.output.contains("It is 42."))
        assertTrue("finished subagents leave the list", monitor.runs.value.isEmpty())
    }

    @Test
    fun `stopping a subagent hands back its partial work`() = runTest {
        val monitor = SubagentMonitor()
        val tool = delegate(ScriptedProvider(), monitor) // round 2 never answers

        val call = async { tool.execute(mapOf("prompt" to JsonPrimitive("Work out 6 times 7"))) }
        testScheduler.runCurrent()
        assertTrue(monitor.stop(monitor.runs.value.single().id))

        val result = call.await()
        assertTrue("the parent turn still gets a result", result.success)
        assertTrue(result.output, result.output.startsWith("[stopped by the user]"))
        assertTrue("partial work kept: ${result.output}", result.output.contains("calculator: 42"))
        assertTrue(monitor.runs.value.isEmpty())
    }

    @Test
    fun `a steering note reaches the subagent's next step`() = runTest {
        val monitor2 = SubagentMonitor()
        val provider2 = object : LlmProvider by ScriptedProvider() {
            val seen = mutableListOf<List<LlmMessage>>()
            var round = 0
            override suspend fun completeWithTools(messages: List<LlmMessage>, tools: List<ToolDescriptor>): LlmToolResponse {
                seen += messages
                if (round++ == 0) {
                    // While the first step is in flight, the user steers.
                    monitor2.steer(monitor2.runs.value.single().id, "only use integers")
                    return LlmToolResponse("", listOf(ToolCall("c1", "calculator", mapOf("expression" to JsonPrimitive("6*7")))), 0, "fake-1", "tool_calls")
                }
                return LlmToolResponse("42", emptyList(), 0, "fake-1", "stop")
            }
        }
        delegate(provider2, monitor2).execute(mapOf("prompt" to JsonPrimitive("Work out 6 times 7")))
        val second = provider2.seen[1]
        assertTrue(second.joinToString { it.content }, second.any { it.role == "user" && it.content.contains("only use integers") })
    }

    @Test
    fun `stop and steer refuse unknown runs`() {
        val monitor = SubagentMonitor()
        assertTrue(!monitor.stop("missing"))
        assertTrue(!monitor.steer("missing", "hello"))
    }
}
