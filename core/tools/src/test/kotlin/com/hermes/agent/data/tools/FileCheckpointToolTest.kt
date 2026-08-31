package com.hermes.agent.data.tools

import com.hermes.agent.data.local.FileCheckpointStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for [FileCheckpointTool] — the restore half of the checkpoint story.
 *
 * Writes and patches had snapshotted since the file tools landed, but nothing
 * called [FileCheckpointStore.restoreCheckpoint], so rollback was unreachable
 * from chat no matter how many checkpoints piled up.
 */
class FileCheckpointToolTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var store: FileCheckpointStore
    private lateinit var tool: FileCheckpointTool

    @Before
    fun setUp() {
        root = temp.newFolder("workspace")
        store = FileCheckpointStore(temp.newFolder("checkpoints"))
        tool = FileCheckpointTool(root, store)
    }

    private fun args(vararg pairs: Pair<String, String>): Map<String, JsonElement> =
        pairs.associate { (k, v) -> k to JsonPrimitive(v) }

    @Test
    fun `list reports nothing when no checkpoint has been taken`() = runBlocking {
        val result = tool.execute(args("action" to "list"))

        assertTrue(result.success)
        assertTrue(result.output, result.output.contains("No checkpoints"))
    }

    @Test
    fun `restore puts back the content captured before the edit`() = runBlocking {
        val file = File(root, "notes.txt")
        file.writeText("original")
        val id = store.createCheckpoint(file)
        file.writeText("clobbered")

        val result = tool.execute(args("action" to "restore", "checkpoint_id" to id))

        assertTrue(result.errorMessage ?: "", result.success)
        assertEquals("original", file.readText())
    }

    @Test
    fun `list surfaces the checkpoint id needed to restore`() = runBlocking {
        val file = File(root, "notes.txt")
        file.writeText("original")
        val id = store.createCheckpoint(file)

        val result = tool.execute(args("action" to "list"))

        assertTrue(result.success)
        assertTrue(result.output, result.output.contains(id))
        assertTrue(result.output, result.output.contains("notes.txt"))
    }

    @Test
    fun `restore without an id explains what to do instead of failing silently`() = runBlocking {
        val result = tool.execute(args("action" to "restore"))

        assertFalse(result.success)
        assertTrue(result.errorMessage!!, result.errorMessage!!.contains("checkpoint_id"))
    }

    @Test
    fun `an unknown checkpoint id is rejected`() = runBlocking {
        val result = tool.execute(args("action" to "restore", "checkpoint_id" to "chk_nope"))

        assertFalse(result.success)
        assertTrue(result.errorMessage!!, result.errorMessage!!.contains("not found"))
    }

    @Test
    fun `a checkpoint outside the current workspace is refused`() = runBlocking {
        // A snapshot taken while a different folder was the workspace root must
        // not become a way to write outside the tree granted today.
        val outside = File(temp.newFolder("elsewhere"), "secret.txt")
        outside.writeText("original")
        val id = store.createCheckpoint(outside)
        outside.writeText("changed")

        val result = tool.execute(args("action" to "restore", "checkpoint_id" to id))

        assertFalse(result.success)
        assertTrue(result.errorMessage!!, result.errorMessage!!.contains("outside the current workspace"))
        assertEquals("changed", outside.readText())
    }

    @Test
    fun `list hides checkpoints belonging to another workspace`() = runBlocking {
        val outside = File(temp.newFolder("elsewhere2"), "secret.txt")
        outside.writeText("x")
        val hiddenId = store.createCheckpoint(outside)

        val inside = File(root, "notes.txt")
        inside.writeText("y")
        val visibleId = store.createCheckpoint(inside)

        val result = tool.execute(args("action" to "list"))

        assertTrue(result.output, result.output.contains(visibleId))
        assertFalse(result.output, result.output.contains(hiddenId))
    }

    @Test
    fun `an unknown action is rejected`() = runBlocking {
        val result = tool.execute(args("action" to "delete"))

        assertFalse(result.success)
        assertTrue(result.errorMessage!!, result.errorMessage!!.contains("Unknown action"))
    }

    @Test
    fun `the tool is confirmation gated`() {
        // Restoring overwrites a file the user may not expect to change.
        assertTrue(tool.descriptor.requiresConfirmation)
        // "files" still carries the grant; "deferrable" moves it behind the
        // tool_search bridge so its schema is not sent on every turn.
        assertTrue(tool.descriptor.capabilities.contains("files"))
        assertTrue(tool.descriptor.capabilities.contains("deferrable"))
    }
}
