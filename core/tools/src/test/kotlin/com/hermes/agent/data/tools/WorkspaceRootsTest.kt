package com.hermes.agent.data.tools

import com.hermes.agent.data.local.FileCheckpointStore
import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.domain.settings.UserSettings
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceRootsTest {

    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `a picked folder maps to the folder it names`() {
        // What the system folder picker returned on the tablet; the tools used to try File(uri).
        assertEquals(
            "/storage/emulated/0/Documents/Jeeves",
            WorkspaceRoots.pathOf("content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FJeeves"),
        )
        assertEquals("/storage/emulated/0", WorkspaceRoots.pathOf("content://com.android.externalstorage.documents/tree/primary%3A"))
        assertEquals("/storage/1A2B-3C4D/Work", WorkspaceRoots.pathOf("content://com.android.externalstorage.documents/tree/1A2B-3C4D%3AWork"))
        assertEquals("/sdcard/Work", WorkspaceRoots.pathOf("/sdcard/Work"))
        assertNull(WorkspaceRoots.pathOf("content://com.google.android.apps.docs.storage/tree/abc"))
    }

    @Test
    fun `a granted folder that cannot be opened is reported, not swapped for another`() = runTest {
        val settings = settingsWithRoot("/definitely/not/here")

        val error = runCatching { WorkspaceRoots.resolve(null, settings) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message.orEmpty(), error.message.orEmpty().contains(java.io.File("/definitely/not/here").path))
    }

    @Test
    fun `a readable granted folder is used`() = runTest {
        val dir = temp.newFolder("workspace")

        assertEquals(dir, WorkspaceRoots.resolve(null, settingsWithRoot(dir.path)))
    }

    @Test
    fun `file tools write and read in the selected workspace`() = runTest {
        val selected = temp.newFolder("selected")
        val repo = settingsWithRoot(selected.absolutePath)
        val checkpoints = FileCheckpointStore(temp.newFolder("checkpoints"))
        val writer = WriteFileTool(null, repo, checkpoints, null)
        val reader = ReadFileTool(null, repo, null)

        assertTrue(writer.execute(mapOf("path" to JsonPrimitive("notes.txt"), "content" to JsonPrimitive("Selected folder"))).success)
        assertEquals("Selected folder", java.io.File(selected, "notes.txt").readText())
        assertTrue(reader.execute(mapOf("path" to JsonPrimitive("notes.txt"))).output.contains("Selected folder"))
    }

    @Test
    fun `file tools refuse an inaccessible document tree instead of writing elsewhere`() = runTest {
        val repo = settingsWithRoot("content://com.android.externalstorage.documents/tree/removed-volume%3AMissing")
        val writer = WriteFileTool(null, repo, FileCheckpointStore(temp.newFolder("checkpoints")), null)

        val error = runCatching {
            writer.execute(mapOf("path" to JsonPrimitive("notes.txt"), "content" to JsonPrimitive("must not write")))
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message.orEmpty().contains("cannot be opened"))
    }

    private fun settingsWithRoot(root: String): SettingsRepository = mockk {
        coEvery { current() } returns UserSettings(filesRootUri = root)
    }
}
