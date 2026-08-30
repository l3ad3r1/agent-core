package com.hermes.agent.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileCheckpointStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun createAndRetrieveCheckpoint_storesOriginalContent() {
        val checkpointDir = tempFolder.newFolder("checkpoints")
        val store = FileCheckpointStore(checkpointDir)

        val targetFile = tempFolder.newFile("sample.txt")
        targetFile.writeText("Original line 1\nOriginal line 2", Charsets.UTF_8)

        val checkpointId = store.createCheckpoint(targetFile)
        assertNotNull(checkpointId)
        assertTrue(checkpointId.startsWith("chk_"))

        val checkpoint = store.getCheckpoint(checkpointId)
        assertNotNull(checkpoint)
        assertEquals("Original line 1\nOriginal line 2", checkpoint?.content)
        assertEquals(targetFile.absolutePath, checkpoint?.filePath)
    }

    @Test
    fun restoreCheckpoint_revertsFileContent() {
        val checkpointDir = tempFolder.newFolder("checkpoints")
        val store = FileCheckpointStore(checkpointDir)

        val targetFile = tempFolder.newFile("notes.txt")
        targetFile.writeText("V1 Content", Charsets.UTF_8)

        val chk1 = store.createCheckpoint(targetFile)

        // Mutate the file
        targetFile.writeText("V2 Mutated Content", Charsets.UTF_8)
        assertEquals("V2 Mutated Content", targetFile.readText())

        // Restore checkpoint
        val result = store.restoreCheckpoint(chk1)
        assertTrue(result.isSuccess)
        assertEquals("V1 Content", targetFile.readText())
    }

    @Test
    fun listCheckpoints_filtersByPathAndOrdersDescending() {
        val checkpointDir = tempFolder.newFolder("checkpoints")
        val store = FileCheckpointStore(checkpointDir)

        val fileA = tempFolder.newFile("fileA.txt")
        val fileB = tempFolder.newFile("fileB.txt")

        fileA.writeText("A1")
        val chkA1 = store.createCheckpoint(fileA)
        fileA.writeText("A2")
        val chkA2 = store.createCheckpoint(fileA)

        fileB.writeText("B1")
        val chkB1 = store.createCheckpoint(fileB)

        val allCheckpoints = store.listCheckpoints()
        assertEquals(3, allCheckpoints.size)

        val fileACheckpoints = store.listCheckpoints(fileA.absolutePath)
        assertEquals(2, fileACheckpoints.size)
        assertEquals(chkA2, fileACheckpoints[0].id)
    }
}
