package com.hermes.agent.data.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AuxiliaryInference] picks the KV lane, and it reaches the engine through a
 * coroutine context rather than a parameter. That only works if the marker
 * survives the hops the real call makes — `withContext(AuxiliaryInference)` at
 * the compressor, then a `flow { }.flowOn(Dispatchers.IO)` in the manager, then
 * `withContext(dispatcher)` in the engine. If it did not survive, background
 * work would silently land on the conversation lane and quietly undo the whole
 * point, with nothing failing to show it.
 */
class AuxiliaryInferenceContextTest {

    /** Mirrors LocalLlmManager.generateResponse: a flow that reads the marker. */
    private fun laneReadingFlow() = flow {
        emit(currentCoroutineContext()[AuxiliaryInference.Key] != null)
    }.flowOn(Dispatchers.IO)

    @Test
    fun `marker survives flowOn and a nested withContext`() = runTest {
        val sawMarker = withContext(AuxiliaryInference) {
            // The engine hops dispatchers again before it reads the lane.
            withContext(Dispatchers.Default) {
                laneReadingFlow().first()
            }
        }
        assertTrue("auxiliary work would have landed on the chat lane", sawMarker)
    }

    @Test
    fun `an untagged call stays on the chat lane`() = runTest {
        assertFalse(
            "chat turns must not be diverted onto the auxiliary lane",
            laneReadingFlow().first(),
        )
    }

    @Test
    fun `the marker does not leak out of its scope`() = runTest {
        withContext(AuxiliaryInference) { laneReadingFlow().first() }
        assertFalse(
            "marker leaked past the auxiliary call",
            laneReadingFlow().first(),
        )
    }
}
