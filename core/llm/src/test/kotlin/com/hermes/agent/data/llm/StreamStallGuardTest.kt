package com.hermes.agent.data.llm

import com.hermes.agent.domain.llm.LlmMessage
import com.hermes.agent.domain.llm.LlmProvider
import com.hermes.agent.domain.llm.LlmStreamChunk
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StreamStallGuardTest {

    @Test
    fun `a stream that never starts is cut after the first limit`() = runTest {
        try {
            flow<Int> { awaitCancellation() }.stallGuard(firstMillis = 30_000, betweenMillis = 120_000).toList()
            fail("expected a stall")
        } catch (e: StreamStalledException) {
            assertTrue(e.beforeOutput)
            assertEquals(30_000L, e.idleMillis)
        }
    }

    @Test
    fun `a stream that stops mid-reply is cut after the between limit, keeping what arrived`() = runTest {
        val got = mutableListOf<Int>()
        try {
            flow { emit(1); emit(2); awaitCancellation() }
                .stallGuard(firstMillis = 30_000, betweenMillis = 120_000)
                .collect { got += it }
            fail("expected a stall")
        } catch (e: StreamStalledException) {
            assertTrue(!e.beforeOutput)
            assertEquals(listOf(1, 2), got)
        }
    }

    @Test
    fun `a slow but steady stream is left alone`() = runTest {
        val out = flow { repeat(5) { delay(100_000); emit(it) } }
            .stallGuard(firstMillis = 110_000, betweenMillis = 110_000).toList()
        assertEquals(listOf(0, 1, 2, 3, 4), out)
    }

    @Test
    fun `upstream errors pass through unchanged`() = runTest {
        try {
            flow<Int> { emit(1); throw IllegalStateException("boom") }.stallGuard(1_000, 1_000).toList()
            fail("expected the upstream error")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
    }

    private fun provider(providerName: String): LlmProvider = mockk(relaxed = true) {
        every { name } returns providerName
        every { model } returns "m"
        every { isOnDevice } returns false
        coEvery { isAvailable() } returns true
    }

    @Test
    fun `the chain moves past a provider that never answers`() = runTest {
        val silent = provider("Silent")
        val next = provider("Next")
        every { silent.stream(any()) } returns flow { awaitCancellation() }
        every { next.stream(any()) } returns flowOf(LlmStreamChunk.Delta("hi"), LlmStreamChunk.Done)

        val chunks = RoutedProviderChain(listOf(silent, next)).stream(listOf(LlmMessage("user", "hello"))).toList()

        assertEquals(listOf(LlmStreamChunk.Delta("hi"), LlmStreamChunk.Done), chunks)
    }

    @Test
    fun `a reply that stalls midway ends with an error, not as finished`() = runTest {
        val stalls = provider("Stalls")
        every { stalls.stream(any()) } returns flow { emit(LlmStreamChunk.Delta("partial")); awaitCancellation() }

        val chunks = RoutedProviderChain(listOf(stalls, provider("Next"))).stream(listOf(LlmMessage("user", "hello"))).toList()

        assertEquals(LlmStreamChunk.Delta("partial"), chunks.first())
        val last = chunks.last()
        assertTrue("expected an error, got $last", last is LlmStreamChunk.Error)
        assertTrue((last as LlmStreamChunk.Error).message.contains("incomplete"))
        assertTrue("must not claim the turn finished", chunks.none { it == LlmStreamChunk.Done })
    }
}
