package com.hermes.agent.data.llm

import com.hermes.agent.data.memory.EmbeddingService
import com.hermes.agent.domain.llm.LlmMessage
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ToolSemanticRoutingTest {

    private val torch = ToolDescriptor(
        name = "device_control",
        description = "Toggle the flashlight.",
        parameters = listOf(ToolParameter("action", ToolParameterType.STRING, "flashlight", required = true)),
    )
    private val weather = ToolDescriptor("get_weather", "Get the current weather for a city.", emptyList())

    /** Two concepts: light (axis 0) and weather (axis 1). Text is mapped by keyword. */
    private class FakeEmbeddings(override val isGemmaBacked: Boolean = true) : EmbeddingService {
        override val dimension = 2
        private fun vec(t: String): FloatArray {
            val l = t.lowercase()
            return when {
                "torch" in l || "flashlight" in l || "lamp" in l -> floatArrayOf(1f, 0f)
                "weather" in l || "rain" in l -> floatArrayOf(0f, 1f)
                else -> floatArrayOf(0.1f, 0.1f)
            }
        }
        override suspend fun embed(text: String) = vec(text)
    }

    private suspend fun ToolEmbeddingIndex.warmed(tools: List<ToolDescriptor>, turn: String): List<Pair<ToolDescriptor, Double>> {
        assertNull("first call only starts indexing", rank(turn, tools))
        withTimeout(5_000) {
            while (true) { rank(turn, tools)?.let { return@withTimeout it }; delay(20) }
        }
        return rank(turn, tools)!!
    }

    @Test
    fun `index ranks by meaning once the tools are embedded`() = runBlocking {
        val ranked = ToolEmbeddingIndex(FakeEmbeddings()).warmed(listOf(weather, torch), "switch on the lamp")
        assertEquals("device_control", ranked.first().first.name)
        assertTrue(ranked.first().second > ranked.last().second)
    }

    @Test
    fun `index is inert unless EmbeddingGemma is the active embedder`() = runBlocking {
        val index = ToolEmbeddingIndex(FakeEmbeddings(isGemmaBacked = false))
        assertNull(index.rank("switch on the lamp", listOf(torch)))
        delay(100)
        assertNull(index.rank("switch on the lamp", listOf(torch)))
    }

    private fun managerReplying(reply: String) = mockk<LocalLlmManager>(relaxed = true) {
        every { isDebuggable } returns false
        every { generateResponse(any(), any(), any()) } returns flow { emit(reply) }
    }

    private val goodCall =
        "<start_function_call>call:device_control{action:<escape>flashlight<escape>}<end_function_call>"

    @Test
    fun `a paraphrase with no shared words reaches the model when meaning is confident`() = runTest {
        val index = mockk<ToolEmbeddingIndex>()
        coEvery { index.rank(any(), any()) } returns listOf(torch to 0.78, weather to 0.60)

        val response = ToolCallerLlmProvider(managerReplying(goodCall), index).completeWithTools(
            listOf(LlmMessage("user", "illuminate the room")),
            listOf(weather, torch),
        )
        assertEquals(listOf("device_control"), response.toolCalls.map { it.name })
    }

    @Test
    fun `a paraphrase still goes to the cloud when meaning is not confident enough`() = runTest {
        val index = mockk<ToolEmbeddingIndex>()
        coEvery { index.rank(any(), any()) } returns listOf(torch to (SEMANTIC_ACCEPT_SCORE - 0.01), weather to 0.5)
        try {
            ToolCallerLlmProvider(managerReplying(goodCall), index).completeWithTools(
                listOf(LlmMessage("user", "illuminate the room")),
                listOf(weather, torch),
            )
            fail("expected an abstain")
        } catch (e: ToolCallerAbstained) {
            assertTrue(e.message, "names none" in e.message.orEmpty())
        }
    }

    @Test
    fun `without a ready index the old word-matching refusal is unchanged`() = runTest {
        val index = mockk<ToolEmbeddingIndex>()
        coEvery { index.rank(any(), any()) } returns null
        try {
            ToolCallerLlmProvider(managerReplying(goodCall), index).completeWithTools(
                listOf(LlmMessage("user", "illuminate the room")),
                listOf(weather, torch),
            )
            fail("expected an abstain")
        } catch (e: ToolCallerAbstained) {
            assertTrue(e.message, "names none" in e.message.orEmpty())
        }
    }

    @Test
    fun `a call to a tool the meaning ranking did not offer is still refused`() = runTest {
        val index = mockk<ToolEmbeddingIndex>()
        coEvery { index.rank(any(), any()) } returns listOf(torch to 0.8, weather to 0.5)
        val wrong = "<start_function_call>call:get_weather{city:<escape>Paris<escape>}<end_function_call>"
        try {
            ToolCallerLlmProvider(managerReplying(wrong), index).completeWithTools(
                listOf(LlmMessage("user", "illuminate the room")),
                listOf(weather, torch),
            )
            fail("expected an abstain")
        } catch (e: ToolCallerAbstained) {
            assertTrue(e.message, "get_weather" in e.message.orEmpty())
        }
    }

    private fun capturing(systems: MutableList<String>) = mockk<LocalLlmManager>(relaxed = true) {
        every { isDebuggable } returns false
        every { generateResponse(any(), capture(systems), any()) } returns flow { emit(goodCall) }
    }

    @Test
    fun `a confident meaning pick is declared alone even when words match several tools`() = runTest {
        val index = mockk<ToolEmbeddingIndex>()
        coEvery { index.rank(any(), any()) } returns listOf(torch to 0.80, weather to 0.72)
        val systems = mutableListOf<String>()

        ToolCallerLlmProvider(capturing(systems), index).completeWithTools(
            // Words match both tools; meaning is sure it is the flashlight.
            listOf(LlmMessage("user", "turn the flashlight on, the weather is dark")),
            listOf(weather, torch),
        )
        assertTrue("flashlight declared", "declaration:device_control" in systems.single())
        assertTrue("weather not declared", "declaration:get_weather" !in systems.single())
    }

    @Test
    fun `below the threshold the word match is used as before`() = runTest {
        val index = mockk<ToolEmbeddingIndex>()
        // Meaning is not sure (0.68) and prefers the weather tool; the request plainly names the flashlight.
        coEvery { index.rank(any(), any()) } returns listOf(weather to (SEMANTIC_ACCEPT_SCORE - 0.02), torch to 0.60)
        val systems = mutableListOf<String>()

        val response = ToolCallerLlmProvider(capturing(systems), index).completeWithTools(
            listOf(LlmMessage("user", "turn on the flashlight")),
            listOf(weather, torch),
        )
        assertEquals(listOf("device_control"), response.toolCalls.map { it.name })
        assertTrue("word match declared the flashlight", "declaration:device_control" in systems.single())
    }

    @Test
    fun `a confident meaning pick overrides a wrong word match`() = runTest {
        val index = mockk<ToolEmbeddingIndex>()
        // Words point at the weather tool ("weather"); meaning says the request is about the torch.
        coEvery { index.rank(any(), any()) } returns listOf(torch to 0.78, weather to 0.66)
        val systems = mutableListOf<String>()

        val response = ToolCallerLlmProvider(capturing(systems), index).completeWithTools(
            listOf(LlmMessage("user", "light up the room, the weather is dark")),
            listOf(weather, torch),
        )
        assertEquals(listOf("device_control"), response.toolCalls.map { it.name })
        assertTrue("get_weather" !in systems.single().substringAfter("declaration:").substringBefore("}"))
    }

    @Test
    fun `with one tool declared a call to any other tool is refused`() = runTest {
        val index = mockk<ToolEmbeddingIndex>()
        coEvery { index.rank(any(), any()) } returns listOf(torch to 0.80, weather to 0.72)
        val wrong = "<start_function_call>call:get_weather{city:<escape>Paris<escape>}<end_function_call>"
        try {
            ToolCallerLlmProvider(managerReplying(wrong), index).completeWithTools(
                listOf(LlmMessage("user", "turn the flashlight on, the weather is dark")),
                listOf(weather, torch),
            )
            fail("expected an abstain")
        } catch (e: ToolCallerAbstained) {
            assertTrue(e.message, "get_weather" in e.message.orEmpty())
        }
    }
}
