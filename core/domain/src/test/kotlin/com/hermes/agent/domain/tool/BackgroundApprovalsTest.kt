package com.hermes.agent.domain.tool

import com.hermes.agent.domain.agent.ExecutionOrigin
import com.hermes.agent.domain.llm.ToolCall
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundApprovalsTest {

    private fun policy(available: Boolean): ToolExecutionPolicy {
        val settings = mockk<ToolAuthorizationSettings>(relaxed = true)
        coEvery { settings.trustedBackgroundPhoneActions() } returns false
        return ToolExecutionPolicy(settings, BackgroundApprovals().apply { this.available = available })
    }

    @Test
    fun `without a way to ask, background approvals are denied at once as before`() = runTest {
        val d = policy(available = false).evaluate(ExecutionOrigin.BACKGROUND, "browser", requiresConfirmation = true)
        assertTrue(d is ToolExecutionDecision.Deny)
    }

    @Test
    fun `with a way to ask, a background run asks instead of refusing`() = runTest {
        val d = policy(available = true).evaluate(ExecutionOrigin.BACKGROUND, "browser", requiresConfirmation = true)
        assertEquals(ToolExecutionDecision.Confirm, d)
    }

    @Test
    fun `never-autonomous and interactive-only tools stay denied in the background`() = runTest {
        val p = policy(available = true)
        for (tool in ToolExecutionPolicy.NEVER_AUTONOMOUS + ToolExecutionPolicy.INTERACTIVE_ONLY) {
            assertTrue(tool, p.evaluate(ExecutionOrigin.BACKGROUND, tool, requiresConfirmation = true) is ToolExecutionDecision.Deny)
        }
    }

    private fun service(): ToolConfirmationService =
        ToolConfirmationService(mockk(relaxed = true), mockk(relaxed = true))

    @Test
    fun `a background request is marked and outlives the interactive timeout`() = runTest {
        val s = service()
        val answer = async { s.awaitConfirmation(ToolCall("c1", "browser", emptyMap()), background = true) }
        runCurrent()
        val pending = s.pendingRequest.value!!
        assertTrue(pending.background)
        advanceTimeBy(ToolConfirmationService.CONFIRMATION_TIMEOUT_MS + 1_000)
        assertTrue("still waiting after a minute", s.pendingRequest.value != null)
        s.submitConfirmation(pending.id, true)
        assertTrue(answer.await())
    }

    @Test
    fun `an unanswered background request is denied when its wait runs out`() = runTest {
        val s = service()
        val answer = async { s.awaitConfirmation(ToolCall("c2", "browser", emptyMap()), background = true) }
        advanceTimeBy(ToolConfirmationService.BACKGROUND_CONFIRMATION_TIMEOUT_MS + 1_000)
        assertFalse(answer.await())
    }

    @Test
    fun `an interactive request is not marked background`() = runTest {
        val s = service()
        val answer = async { s.awaitConfirmation(ToolCall("c3", "browser", emptyMap())) }
        runCurrent()
        assertFalse(s.pendingRequest.value!!.background)
        s.submitConfirmation(s.pendingRequest.value!!.id, false)
        assertFalse(answer.await())
    }
}
