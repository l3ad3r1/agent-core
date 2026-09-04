package com.hermes.agent.data.llm

import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every expected string here is taken from the chat template embedded in
 * FunctionGemma's own GGUF, and the "device capture" cases are literal output
 * recorded from the model running on an S24U.
 */
class FunctionGemmaFormatTest {

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
            ToolParameter(
                "action", ToolParameterType.STRING, "What to do",
                enumValues = listOf("create", "list"),
            ),
            ToolParameter("title", ToolParameterType.STRING, "Task title"),
        ),
    )

    // ── declarations ──────────────────────────────────────────────────────────

    @Test
    fun `declaration matches the template's grammar`() {
        val rendered = renderFunctionDeclarations(listOf(torch))
        assertEquals(
            "<start_function_declaration>declaration:set_torch" +
                "{description:<escape>Turn the torch on or off.<escape>," +
                "parameters:{properties:{on:{description:<escape>Torch state<escape>}}," +
                "required:[<escape>on<escape>]," +
                "type:<escape>OBJECT<escape>}}" +
                "<end_function_declaration>",
            rendered,
        )
    }

    @Test
    fun `enum values are declared so the model picks from the closed set`() {
        val rendered = renderFunctionDeclarations(listOf(todo))
        assertTrue(rendered.contains("enum:[<escape>create<escape>,<escape>list<escape>]"))
        // Nothing is required on an action-style tool, so no required array.
        assertTrue(!rendered.contains("required:["))
    }

    @Test
    fun `every tool gets its own declaration block`() {
        val rendered = renderFunctionDeclarations(listOf(torch, todo))
        assertEquals(2, Regex("<start_function_declaration>").findAll(rendered).count())
        assertEquals(2, Regex("<end_function_declaration>").findAll(rendered).count())
    }

    // ── calls ─────────────────────────────────────────────────────────────────

    @Test
    fun `a boolean call parses`() {
        val (leftover, calls) = parseFunctionGemmaCalls(
            "<start_function_call>call:set_torch{on:true}<end_function_call>",
        )
        assertEquals("", leftover)
        assertEquals(1, calls.size)
        assertEquals("set_torch", calls[0].name)
        assertEquals(true, calls[0].arguments["on"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `escape delimited strings parse without their delimiters`() {
        val (_, calls) = parseFunctionGemmaCalls(
            "<start_function_call>call:todo{action:<escape>create<escape>," +
                "title:<escape>Buy milk<escape>}<end_function_call>",
        )
        assertEquals("create", calls[0].arguments["action"]?.jsonPrimitive?.content)
        assertEquals("Buy milk", calls[0].arguments["title"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a comma inside an escape span does not split the arguments`() {
        // The reason splitting is escape-aware: titles and descriptions contain
        // commas constantly, and a naive split truncates them silently.
        val (_, calls) = parseFunctionGemmaCalls(
            "<start_function_call>call:todo{title:<escape>Milk, eggs, bread<escape>}" +
                "<end_function_call>",
        )
        assertEquals(1, calls[0].arguments.size)
        assertEquals("Milk, eggs, bread", calls[0].arguments["title"]?.jsonPrimitive?.content)
    }

    @Test
    fun `numbers parse as numbers`() {
        val (_, calls) = parseFunctionGemmaCalls(
            "<start_function_call>call:vibrate{duration_ms:500}<end_function_call>",
        )
        assertEquals("500", calls[0].arguments["duration_ms"]?.jsonPrimitive?.content)
    }

    @Test
    fun `nested objects and arrays parse`() {
        val (_, calls) = parseFunctionGemmaCalls(
            "<start_function_call>call:x{opts:{a:true,b:<escape>two<escape>}," +
                "tags:[<escape>p<escape>,<escape>q<escape>]}<end_function_call>",
        )
        val opts = calls[0].arguments["opts"] as JsonObject
        assertEquals("two", opts["b"]?.jsonPrimitive?.content)
        val tags = calls[0].arguments["tags"] as JsonArray
        assertEquals(2, tags.size)
        assertEquals("p", tags[0].jsonPrimitive.content)
    }

    @Test
    fun `two calls in one reply both parse`() {
        val (_, calls) = parseFunctionGemmaCalls(
            "<start_function_call>call:set_torch{on:true}<end_function_call>" +
                "<start_function_call>call:todo{action:<escape>list<escape>}<end_function_call>",
        )
        assertEquals(2, calls.size)
        assertEquals("set_torch", calls[0].name)
        assertEquals("todo", calls[1].name)
    }

    @Test
    fun `a truncated call missing its closing token still parses`() {
        // 270M models run out mid-emission; the name and arguments are already
        // there and the confidence gate can judge them.
        val (_, calls) = parseFunctionGemmaCalls(
            "<start_function_call>call:set_torch{on:true}",
        )
        assertEquals(1, calls.size)
        assertEquals("set_torch", calls[0].name)
    }

    @Test
    fun `the device capture surfaces as an invented name, not as silence`() {
        // Literal output recorded on the S24U before declarations were sent.
        // It must reach the confidence gate as a bad name — "tool 'turn off' is
        // not in the advertised set" — rather than vanishing as "no call".
        val capture = "<start_function_call>call: turn off\n\nThis query is unrelated " +
            "to the available tools and functions."
        val (leftover, calls) = parseFunctionGemmaCalls(capture)
        assertEquals(1, calls.size)
        assertEquals("turn off", calls[0].name)
        assertTrue(calls[0].arguments.isEmpty())
        assertEquals(0.0, scoreToolCalls(calls, listOf(torch, todo), leftover).score, 0.001)
    }

    @Test
    fun `prose with no call yields no calls`() {
        val (leftover, calls) = parseFunctionGemmaCalls("I cannot help with that.")
        assertTrue(calls.isEmpty())
        assertEquals("I cannot help with that.", leftover)
    }

    @Test
    fun `a parsed call from a declared tool clears the confidence gate`() {
        // End to end: what we declare, parsed back from what the model emits,
        // scores high enough to run.
        val (leftover, calls) = parseFunctionGemmaCalls(
            "<start_function_call>call:set_torch{on:true}<end_function_call>",
        )
        val confidence = scoreToolCalls(calls, listOf(torch, todo), leftover)
        assertEquals(1.0, confidence.score, 0.001)
    }
}
