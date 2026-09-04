package com.hermes.agent.data.llm

import com.hermes.agent.domain.llm.ToolCall
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The score below which [ToolCallerLlmProvider] hands the turn to the cloud. */
private const val THRESHOLD = 0.60

class ToolCallConfidenceTest {

    private val torch = ToolDescriptor(
        name = "set_torch",
        description = "Turn the torch on or off.",
        parameters = listOf(
            ToolParameter("on", ToolParameterType.BOOLEAN, "Torch state", required = true),
        ),
    )

    private val todo = ToolDescriptor(
        name = "todo",
        description = "Manage the todo list.",
        parameters = listOf(
            // Action-style tools declare everything optional on purpose, so the
            // required-argument signal is unavailable here — the enum carries it.
            ToolParameter(
                "action", ToolParameterType.STRING, "What to do",
                enumValues = listOf("create", "list", "complete"),
            ),
            ToolParameter("title", ToolParameterType.STRING, "Task title"),
            ToolParameter("priority", ToolParameterType.INTEGER, "1-5"),
        ),
    )

    private val tools = listOf(torch, todo)

    private fun call(
        name: String,
        args: Map<String, JsonElement> = emptyMap(),
        id: String = "text_call_1",
    ) = ToolCall(id = id, name = name, arguments = args)

    @Test
    fun `a clean call scores full confidence`() {
        val result = scoreToolCalls(
            listOf(call("set_torch", mapOf("on" to JsonPrimitive(true)))),
            tools,
        )
        assertEquals(1.0, result.score, 0.001)
        assertTrue("clean call must clear the gate", result.score >= THRESHOLD)
    }

    @Test
    fun `a name outside the advertised set scores zero`() {
        val result = scoreToolCalls(
            listOf(call("launch_missiles", mapOf("target" to JsonPrimitive("moon")))),
            tools,
        )
        // Not low confidence — nothing to execute at all.
        assertEquals(0.0, result.score, 0.001)
        assertTrue(result.reason.contains("not in the advertised set"))
    }

    @Test
    fun `a missing required argument drops the call below the gate`() {
        val result = scoreToolCalls(listOf(call("set_torch", mapOf())), tools)
        assertTrue("expected abstain, got ${result.score}", result.score < THRESHOLD)
        assertTrue(result.reason.contains("required argument 'on' is missing"))
    }

    @Test
    fun `a value outside a declared enum is penalised`() {
        val clean = scoreToolCalls(
            listOf(call("todo", mapOf("action" to JsonPrimitive("create"), "title" to JsonPrimitive("Milk")))),
            tools,
        )
        val violating = scoreToolCalls(
            listOf(call("todo", mapOf("action" to JsonPrimitive("obliterate"), "title" to JsonPrimitive("Milk")))),
            tools,
        )
        assertTrue(clean.score >= THRESHOLD)
        assertTrue("enum violation must cost more than the gate", violating.score < clean.score)
        assertTrue(violating.reason.contains("outside create/list/complete"))
    }

    @Test
    fun `an argument the tool does not declare is penalised`() {
        val result = scoreToolCalls(
            listOf(
                call(
                    "todo",
                    mapOf("action" to JsonPrimitive("list"), "colour" to JsonPrimitive("red")),
                ),
            ),
            tools,
        )
        assertTrue(result.score < 1.0)
        assertTrue(result.reason.contains("'colour' is not declared"))
    }

    @Test
    fun `a placeholder value is penalised`() {
        val result = scoreToolCalls(
            listOf(
                call(
                    "todo",
                    mapOf("action" to JsonPrimitive("create"), "title" to JsonPrimitive("string")),
                ),
            ),
            tools,
        )
        assertTrue(result.score < 1.0)
        assertTrue(result.reason.contains("placeholder"))
    }

    @Test
    fun `a call rescued from loose text is trusted less than a clean envelope`() {
        val args = mapOf("on" to JsonPrimitive(true))
        val envelope = scoreToolCalls(listOf(call("set_torch", args)), tools)
        val recovered = scoreToolCalls(
            listOf(call("set_torch", args, id = "recovered_call_7")),
            tools,
        )
        assertTrue(recovered.score < envelope.score)
        // Still a usable call: a rescued call is worse evidence, not bad evidence.
        assertTrue(recovered.score >= THRESHOLD)
    }

    @Test
    fun `a quoted number satisfies an integer parameter`() {
        // Models quote numeric arguments constantly and the tools parse them
        // anyway; treating this as a type error would abstain on working calls.
        val result = scoreToolCalls(
            listOf(
                call(
                    "todo",
                    mapOf("action" to JsonPrimitive("create"), "priority" to JsonPrimitive("3")),
                ),
            ),
            tools,
        )
        assertEquals(1.0, result.score, 0.001)
    }

    @Test
    fun `empty arguments on a tool that declares some is penalised`() {
        val result = scoreToolCalls(listOf(call("todo", emptyMap())), tools)
        assertTrue(result.score < 1.0)
        assertTrue(result.reason.contains("supplied none"))
    }

    @Test
    fun `prose alongside the envelope is penalised`() {
        val result = scoreToolCalls(
            listOf(call("set_torch", mapOf("on" to JsonPrimitive(true)))),
            tools,
            leftoverText = "Sure! I will now turn on the torch for you, hold on a moment.",
        )
        assertTrue(result.score < 1.0)
        assertTrue(result.reason.contains("prose"))
    }

    @Test
    fun `the weakest call decides a multi-call turn`() {
        val weak = call(
            "todo",
            mapOf("action" to JsonPrimitive("obliterate"), "colour" to JsonPrimitive("red")),
        )
        val weakAlone = scoreToolCalls(listOf(weak), tools)
        val together = scoreToolCalls(
            listOf(call("set_torch", mapOf("on" to JsonPrimitive(true))), weak),
            tools,
        )

        // One good call does not license a guessed one alongside it: the turn
        // scores exactly what its worse call scores.
        assertEquals(weakAlone.score, together.score, 0.001)
        assertTrue("expected the turn to abstain, got ${together.score}", together.score < THRESHOLD)
    }

    @Test
    fun `no calls scores zero`() {
        assertEquals(0.0, scoreToolCalls(emptyList(), tools).score, 0.001)
    }
}
