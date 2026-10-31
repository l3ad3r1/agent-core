package com.hermes.agent.data.llm

import com.hermes.agent.domain.llm.LlmMessage
import com.hermes.agent.domain.llm.LlmProvider
import com.hermes.agent.domain.llm.LlmStreamChunk
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepetitionLoopGuardTest {

    @Test
    fun `a phrase repeated over and over is a loop`() {
        assertTrue(RepetitionDetector.endsInLoop("Here is the answer. " + "I will now call the tool. ".repeat(20)))
    }

    @Test
    fun `a single character run is a loop`() {
        assertTrue(RepetitionDetector.endsInLoop("ok " + "!".repeat(500)))
    }

    @Test
    fun `ordinary prose and short repeats are not`() {
        val prose = "The quick brown fox jumps over the lazy dog while the cat watches from the fence. ".let { base ->
            (1..12).joinToString(" ") { "$base Sentence number $it adds something new." }
        }
        assertFalse(RepetitionDetector.endsInLoop(prose))
        // A markdown table rule repeated a few times is shorter than the span floor.
        assertFalse(RepetitionDetector.endsInLoop("| a | b |\n" + "|---|---|\n".repeat(3)))
        // Trailing whitespace on its own is never a loop.
        assertFalse(RepetitionDetector.endsInLoop("Done." + " ".repeat(600)))
    }

    @Test
    fun `the stream stops with an error after the loop starts, keeping earlier text`() = runTest {
        val chunks = (listOf("Sure, here it is. ") + List(60) { "again and again " } + listOf("never reached"))
            .map { LlmStreamChunk.Delta(it) as LlmStreamChunk }
            .plus(LlmStreamChunk.Done)
            .asFlow()
            .cutRepetitionLoops("Local")
            .toList()

        assertEquals(LlmStreamChunk.Delta("Sure, here it is. "), chunks.first())
        val last = chunks.last()
        assertTrue("expected an error, got $last", last is LlmStreamChunk.Error)
        assertTrue(chunks.none { it == LlmStreamChunk.Delta("never reached") })
        assertTrue("must not claim the turn finished", chunks.none { it == LlmStreamChunk.Done })
    }

    @Test
    fun `a normal reply passes through untouched`() = runTest {
        val input = listOf("Hello", " there,", " how can I help today?").map { LlmStreamChunk.Delta(it) } + LlmStreamChunk.Done
        assertEquals(input, input.asFlow().cutRepetitionLoops("Cloud").toList())
    }

    @Test
    fun `the routed chain applies it`() = runTest {
        val looping: LlmProvider = mockk(relaxed = true) {
            every { name } returns "Local"
            every { model } returns "m"
            every { isOnDevice } returns true
            coEvery { isAvailable() } returns true
            every { stream(any()) } returns flow { repeat(200) { emit(LlmStreamChunk.Delta("loop ")) }; emit(LlmStreamChunk.Done) }
        }
        val chunks = RoutedProviderChain(listOf(looping)).stream(listOf(LlmMessage("user", "hi"))).toList()
        assertTrue(chunks.last() is LlmStreamChunk.Error)
        assertTrue("stopped well before the end", chunks.count { it is LlmStreamChunk.Delta } < 200)
    }
}
