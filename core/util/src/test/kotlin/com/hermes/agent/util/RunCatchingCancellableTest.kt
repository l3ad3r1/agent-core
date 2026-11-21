package com.hermes.agent.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunCatchingCancellableTest {

    @Test
    fun `ordinary failures are wrapped like runCatching`() {
        val r = runCatchingCancellable { error("boom") }
        assertTrue(r.isFailure)
        assertEquals("boom", r.exceptionOrNull()?.message)
        assertEquals(3, runCatchingCancellable { 1 + 2 }.getOrThrow())
    }

    @Test(expected = CancellationException::class)
    fun `cancellation is rethrown`() {
        runCatchingCancellable { throw CancellationException("stop") }
    }

    @Test
    fun `a cancelled coroutine stops instead of running its fallback`() = runTest {
        var fellBack = false
        val job = launch {
            runCatchingCancellable { delay(10_000) }.onFailure { fellBack = true }
            fellBack = true
        }
        testScheduler.runCurrent()
        job.cancel()
        job.join()
        assertFalse(fellBack)
    }
}
