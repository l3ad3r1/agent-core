package com.hermes.agent.data.llm
import com.hermes.agent.domain.llm.*
import com.hermes.agent.domain.settings.*

import androidx.work.Data
import androidx.work.WorkInfo
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelDownloadCoordinatorTest {

    @Test
    fun `queued and running work recover as active downloads`() {
        val queued = modelDownloadSnapshot(WorkInfo.State.ENQUEUED, 0, "")
        val running = modelDownloadSnapshot(WorkInfo.State.RUNNING, 37, "")

        assertTrue(queued.isDownloading)
        assertTrue(running.isDownloading)
        assertEquals(0.37f, running.progress)
    }

    @Test
    fun `successful work is complete and no longer active`() {
        val snapshot = modelDownloadSnapshot(WorkInfo.State.SUCCEEDED, 0, "")

        assertFalse(snapshot.isDownloading)
        assertEquals(1f, snapshot.progress)
        assertEquals("", snapshot.error)
    }

    @Test
    fun `failed work surfaces its actionable worker error`() {
        val snapshot = modelDownloadSnapshot(
            WorkInfo.State.FAILED,
            81,
            "Free storage and try again.",
        )

        assertFalse(snapshot.isDownloading)
        assertEquals("Free storage and try again.", snapshot.error)
    }

    @Test
    fun `progress is clamped to a valid percentage`() {
        assertEquals(1f, modelDownloadSnapshot(WorkInfo.State.RUNNING, 400, "").progress)
        assertEquals(0f, modelDownloadSnapshot(WorkInfo.State.RUNNING, -20, "").progress)
    }

    // --- Which of the retained runs the UI follows ---

    private fun workInfo(state: WorkInfo.State, id: UUID = UUID.randomUUID()): WorkInfo =
        WorkInfo(id = id, state = state, tags = emptySet(), outputData = Data.EMPTY)

    @Test
    fun `the run this process enqueued wins over a replaced one`() {
        val trackedId = UUID.randomUUID()
        val replaced = workInfo(WorkInfo.State.CANCELLED)
        val mine = workInfo(WorkInfo.State.RUNNING, trackedId)

        // Order is deliberately "wrong": the cancelled leftover comes last, which
        // is exactly what lastOrNull() used to pick.
        val selected = selectActiveWorkInfo(listOf(mine, replaced), trackedId)

        assertEquals(trackedId, selected?.id)
        assertTrue(modelDownloadSnapshot(selected?.state, 42, "").isDownloading)
    }

    @Test
    fun `without a tracked id an unfinished run is preferred over a cancelled one`() {
        val cancelled = workInfo(WorkInfo.State.CANCELLED)
        val running = workInfo(WorkInfo.State.RUNNING)

        val selected = selectActiveWorkInfo(listOf(running, cancelled), trackedId = null)

        assertEquals(WorkInfo.State.RUNNING, selected?.state)
    }

    @Test
    fun `a real failure is reported rather than a replaced run`() {
        val cancelled = workInfo(WorkInfo.State.CANCELLED)
        val failed = workInfo(WorkInfo.State.FAILED)

        val selected = selectActiveWorkInfo(listOf(failed, cancelled), trackedId = null)

        assertEquals(WorkInfo.State.FAILED, selected?.state)
    }

    @Test
    fun `an empty work list selects nothing`() {
        assertNull(selectActiveWorkInfo(emptyList(), trackedId = null))
    }
}
