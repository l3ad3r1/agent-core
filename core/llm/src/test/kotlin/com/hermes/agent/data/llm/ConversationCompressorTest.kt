package com.hermes.agent.data.llm

import com.hermes.agent.domain.llm.LlmMessage
import com.hermes.agent.domain.llm.LlmProvider
import com.hermes.agent.domain.llm.LlmResponse
import com.hermes.agent.domain.llm.LlmStreamChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The compressor's whole job on-device is to summarise rarely. These cover the
 * two properties that matter: it does not call the model every turn, and what
 * it returns on the turns it stays quiet is still complete.
 */
class ConversationCompressorTest {

    /** Captures every prompt the compressor sends and replies with a fixed brief. */
    private class FakeRouter(
        private val reply: (Int) -> String = { "BRIEF#$it" },
    ) : LlmRouter {
        val prompts = mutableListOf<String>()

        private val provider = object : LlmProvider {
            override val name = "fake"
            override val isOnDevice = true
            override val model = "fake-1"
            override suspend fun complete(messages: List<LlmMessage>): LlmResponse {
                prompts += messages.joinToString("\n") { "${it.role}: ${it.content}" }
                return LlmResponse(reply(prompts.size), tokensUsed = 0, model = model)
            }
            override fun stream(messages: List<LlmMessage>): Flow<LlmStreamChunk> = emptyFlow()
            override suspend fun isAvailable() = true
        }

        override suspend fun route(
            messages: List<LlmMessage>,
            context: RoutingContext,
        ): RoutingDecision = RoutingDecision.Ready(provider, "test")

        override suspend fun activeTarget(context: RoutingContext): ActiveTarget? = null
    }

    private fun msgs(n: Int) = (0 until n).map {
        LlmMessage(role = if (it % 2 == 0) "user" else "assistant", content = "message $it")
    }

    @Test
    fun `too short a thread is not summarised at all`() = runTest {
        val router = FakeRouter()
        val c = ConversationCompressor(router)
        assertNull(c.brief("conv", msgs(5)))
        assertEquals(0, router.prompts.size)
    }

    @Test
    fun `summarises once, then rides on a verbatim tail`() = runTest {
        val router = FakeRouter()
        val c = ConversationCompressor(router)

        assertEquals("BRIEF#1", c.brief("conv", msgs(6)))
        assertEquals(1, router.prompts.size)

        // Two more turns have dropped out of the verbatim window. That must not
        // cost an inference, and must not lose them either.
        val second = c.brief("conv", msgs(8))
        assertEquals("model was called again", 1, router.prompts.size)
        assertTrue(second!!.startsWith("BRIEF#1"))
        assertTrue("uncovered turn missing", second.contains("message 6"))
        assertTrue("uncovered turn missing", second.contains("message 7"))
    }

    @Test
    fun `folds the tail in once it is big enough, without re-reading the thread`() = runTest {
        val router = FakeRouter()
        val c = ConversationCompressor(router)

        c.brief("conv", msgs(6))
        val merged = c.brief("conv", msgs(12))

        assertEquals("expected exactly one merge", 2, router.prompts.size)
        assertEquals("BRIEF#2", merged)

        val mergePrompt = router.prompts[1]
        assertTrue("previous brief not carried in", mergePrompt.contains("BRIEF#1"))
        assertTrue("new turn missing", mergePrompt.contains("message 11"))
        // The point of the exercise: already-summarised turns are not re-read.
        assertTrue(
            "merge re-read already-summarised history",
            !mergePrompt.contains("message 0"),
        )
    }

    @Test
    fun `a failed merge still returns complete context`() = runTest {
        var calls = 0
        val router = FakeRouter { n ->
            calls = n
            if (n > 1) throw IllegalStateException("model down") else "BRIEF#1"
        }
        val c = ConversationCompressor(router)
        c.brief("conv", msgs(6))

        val out = c.brief("conv", msgs(12))
        assertNotNull("context dropped when the merge failed", out)
        assertTrue(out!!.startsWith("BRIEF#1"))
        assertTrue("uncovered turn lost on failure", out.contains("message 11"))
        assertTrue(calls >= 2)
    }
}
