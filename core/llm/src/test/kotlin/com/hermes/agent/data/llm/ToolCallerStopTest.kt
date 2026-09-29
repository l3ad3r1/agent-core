package com.hermes.agent.data.llm

import com.hermes.agent.domain.llm.LlmMessage
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallerStopTest {

    private val torch = ToolDescriptor(
        name = "device_control",
        description = "Toggle the flashlight.",
        parameters = listOf(
            ToolParameter("action", ToolParameterType.STRING, "flashlight", required = true),
            ToolParameter("enabled", ToolParameterType.BOOLEAN, "On or off"),
        ),
    )

    @Test
    fun `generation stops at the first finished call`() = runTest {
        // On the tablet the model went on after a good call with invented ones
        // (call:lightbulb{...}) for 5K characters; those sank the turn.
        var piecesRead = 0
        val pieces = listOf(
            "<start_function_call>call:device_control{action:<escape>flashlight<escape>,",
            "enabled:true}<end_function_call>",
            "<start_function_call>call:lightbulb{mode:<escape>on<escape>}<end_function_call>",
            "<start_function_call>call:clarify{}<end_function_call>",
        )
        val manager = mockk<LocalLlmManager>(relaxed = true) {
            every { isDebuggable } returns false
            every { generateResponse(any(), any(), any()) } returns flow {
                for (piece in pieces) {
                    piecesRead++
                    emit(piece)
                }
            }
        }

        val response = ToolCallerLlmProvider(manager).completeWithTools(
            listOf(LlmMessage("user", "Turn on the flashlight")),
            listOf(torch),
        )

        assertEquals(listOf("device_control"), response.toolCalls.map { it.name })
        assertEquals(JsonPrimitive(true), response.toolCalls.single().arguments["enabled"])
        assertEquals("nothing after the finished call is generated", 3, piecesRead)
    }

    private val media = ToolDescriptor(
        name = "media_control",
        description = "Play or pause media, skip tracks, change the volume.",
        parameters = listOf(ToolParameter("action", ToolParameterType.STRING, "Media action.", required = true)),
    )
    private val volume = ToolDescriptor(
        name = "device_settings",
        description = "Read or change screen brightness and media volume.",
        parameters = listOf(ToolParameter("setting", ToolParameterType.STRING, "Which setting.", required = true)),
    )

    private fun managerReplying(reply: String, onRun: () -> Unit = {}) = mockk<LocalLlmManager>(relaxed = true) {
        every { isDebuggable } returns false
        every { generateResponse(any(), any(), any()) } returns flow { onRun(); emit(reply) }
    }

    @Test
    fun `a well-formed call to a tool the request does not point to is not trusted`() = runTest {
        // Asked to set the media volume, the model called media_control play_pause.
        val manager = managerReplying(
            "<start_function_call>call:media_control{action:<escape>play_pause<escape>}<end_function_call>",
        )

        val refused = runCatching {
            ToolCallerLlmProvider(manager).completeWithTools(
                listOf(LlmMessage("user", "Set media volume to 30 percent")),
                listOf(volume, media),
            )
        }.exceptionOrNull()

        assertTrue(refused is ToolCallerAbstained)
        assertTrue(refused!!.message.orEmpty(), refused.message.orEmpty().contains("device_settings"))
    }

    @Test
    fun `after its call has run the tool caller steps aside for the reply`() = runTest {
        var ran = false
        val manager = managerReplying(
            "<start_function_call>call:media_control{action:<escape>play_pause<escape>}<end_function_call>",
        ) { ran = true }

        val refused = runCatching {
            ToolCallerLlmProvider(manager).completeWithTools(
                listOf(
                    LlmMessage("user", "Pause the music"),
                    LlmMessage("assistant", "", toolCalls = listOf(com.hermes.agent.domain.llm.ToolCall("c", "media_control", emptyMap()))),
                    LlmMessage("tool", "Paused.", toolCallId = "c"),
                ),
                listOf(media),
            )
        }.exceptionOrNull()

        assertTrue(refused is ToolCallerAbstained)
        assertFalse("the call must not be issued a second time", ran)
    }

    @Test
    fun `one stray shared word does not make a tool the request`() = runTest {
        // "Install ... skill" matched media_control's "installed music app"; shown only that
        // tool, the model toggled playback on the tablet.
        var ran = false
        val player = ToolDescriptor(
            name = "media_control",
            description = "Play or pause media, skip tracks, or ask an installed music app to play a search.",
            parameters = listOf(ToolParameter("action", ToolParameterType.STRING, "Media action.", required = true)),
        )
        val manager = managerReplying("<start_function_call>call:media_control{action:<escape>play_pause<escape>}<end_function_call>") { ran = true }

        val refused = runCatching {
            ToolCallerLlmProvider(manager).completeWithTools(
                listOf(LlmMessage("user", "Install the canvas-design skill from the Skills Hub.")),
                listOf(player, volume),
            )
        }.exceptionOrNull()

        assertTrue(refused is ToolCallerAbstained)
        assertFalse("the model is not run for a coincidence", ran)
        // Real requests for the tool still reach it.
        assertTrue(toolCoverage(player, "Play the next track") >= MIN_TOOL_COVERAGE)
        assertTrue(toolCoverage(volume, "Set media volume to 30 percent") >= MIN_TOOL_COVERAGE)
    }

    @Test
    fun `a request that names no tool is handed on without running the model`() = runTest {
        var ran = false
        val manager = managerReplying("<start_function_call>call:media_control{}<end_function_call>") { ran = true }

        val refused = runCatching {
            ToolCallerLlmProvider(manager).completeWithTools(
                listOf(LlmMessage("user", "Tell me a joke about penguins")),
                listOf(volume, media),
            )
        }.exceptionOrNull()

        assertTrue(refused is ToolCallerAbstained)
        assertFalse("the model is not run for nothing", ran)
    }
}
