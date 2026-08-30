package com.hermes.agent.data.tools

import com.hermes.agent.data.local.FileCheckpointStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileToolsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun readFileTool_readsWithPagination() = runBlocking {
        val root = tempFolder.newFolder("workspace")
        val sampleFile = File(root, "lines.txt")
        sampleFile.writeText((1..20).joinToString("\n") { "Line $it" })

        val tool = ReadFileTool(root)

        // Read lines 5 to 8
        val args = mapOf(
            "path" to kotlinx.serialization.json.JsonPrimitive("lines.txt"),
            "offset" to kotlinx.serialization.json.JsonPrimitive(5),
            "limit" to kotlinx.serialization.json.JsonPrimitive(4),
        )

        val result = tool.execute(args)
        assertTrue(result.success)

        val json = Json.parseToJsonElement(result.output).jsonObject
        assertEquals("success", json["status"]?.jsonPrimitive?.content)
        assertEquals(20, json["total_lines"]?.jsonPrimitive?.intOrNull)
        assertEquals(4, json["lines_read"]?.jsonPrimitive?.intOrNull)
        assertTrue(json["content"]?.jsonPrimitive?.content?.contains("5: Line 5") == true)
        assertTrue(json["content"]?.jsonPrimitive?.content?.contains("8: Line 8") == true)
        assertFalse(json["content"]?.jsonPrimitive?.content?.contains("9: Line 9") == true)
    }

    @Test
    fun readFileTool_traversalAttempt_rejected() = runBlocking {
        val root = tempFolder.newFolder("workspace")
        val tool = ReadFileTool(root)

        val args = mapOf(
            "path" to kotlinx.serialization.json.JsonPrimitive("../../escape.txt"),
        )

        val result = tool.execute(args)
        assertFalse(result.success)
        assertTrue(result.errorMessage?.contains("Security violation") == true)
    }

    @Test
    fun writeFileTool_writesFileAndCreatesCheckpoint() = runBlocking {
        val root = tempFolder.newFolder("workspace")
        val checkpointDir = tempFolder.newFolder("checkpoints")
        val checkpointStore = FileCheckpointStore(checkpointDir)

        val tool = WriteFileTool(root, checkpointStore)

        val args = mapOf(
            "path" to kotlinx.serialization.json.JsonPrimitive("docs/readme.md"),
            "content" to kotlinx.serialization.json.JsonPrimitive("# Hello World\nTesting write_file."),
        )

        val result = tool.execute(args)
        assertTrue(result.success)

        val writtenFile = File(root, "docs/readme.md")
        assertTrue(writtenFile.exists())
        assertEquals("# Hello World\nTesting write_file.", writtenFile.readText())

        val json = Json.parseToJsonElement(result.output).jsonObject
        val checkpointId = json["checkpoint_id"]?.jsonPrimitive?.content
        assertNotNull(checkpointId)

        // Verify checkpoint exists
        val checkpoint = checkpointStore.getCheckpoint(checkpointId!!)
        assertNotNull(checkpoint)
    }

    @Test
    fun patchFileTool_appliesPatchAndCreatesCheckpoint() = runBlocking {
        val root = tempFolder.newFolder("workspace")
        val checkpointDir = tempFolder.newFolder("checkpoints")
        val checkpointStore = FileCheckpointStore(checkpointDir)

        val targetFile = File(root, "src/config.json")
        targetFile.parentFile.mkdirs()
        targetFile.writeText("{\n  \"port\": 8080,\n  \"name\": \"app\"\n}")

        val tool = PatchFileTool(root, checkpointStore)

        val patch = """
            @@ -1,4 +1,4 @@
            {
            -  "port": 8080,
            +  "port": 9000,
               "name": "app"
            }
        """.trimIndent()

        val args = mapOf(
            "path" to kotlinx.serialization.json.JsonPrimitive("src/config.json"),
            "patch" to kotlinx.serialization.json.JsonPrimitive(patch),
        )

        val result = tool.execute(args)
        assertTrue(result.success)
        assertTrue(targetFile.readText().contains("\"port\": 9000"))

        val json = Json.parseToJsonElement(result.output).jsonObject
        val checkpointId = json["checkpoint_id"]?.jsonPrimitive?.content
        assertNotNull(checkpointId)

        // Verify we can roll back using checkpoint store
        val restoreResult = checkpointStore.restoreCheckpoint(checkpointId!!)
        assertTrue(restoreResult.isSuccess)
        assertTrue(targetFile.readText().contains("\"port\": 8080"))
    }

    @Test
    fun searchFilesTool_findsByNameAndContent() = runBlocking {
        val root = tempFolder.newFolder("workspace")
        val f1 = File(root, "src/Main.kt").apply { parentFile.mkdirs(); writeText("fun main() { println(\"HERMES_START\") }") }
        val f2 = File(root, "src/Utils.kt").apply { writeText("fun helper() {}") }
        val f3 = File(root, "docs/Guide.md").apply { parentFile.mkdirs(); writeText("Reference for HERMES_START guide.") }

        val tool = SearchFilesTool(root)

        // 1. Search by name
        val nameResult = tool.execute(mapOf("pattern" to kotlinx.serialization.json.JsonPrimitive("Utils")))
        assertTrue(nameResult.success)
        val nameJson = Json.parseToJsonElement(nameResult.output).jsonObject
        assertEquals(1, nameJson["results_count"]?.jsonPrimitive?.intOrNull)

        // 2. Search by content keyword
        val contentResult = tool.execute(mapOf("pattern" to kotlinx.serialization.json.JsonPrimitive("HERMES_START")))
        assertTrue(contentResult.success)
        val contentJson = Json.parseToJsonElement(contentResult.output).jsonObject
        assertEquals(2, contentJson["results_count"]?.jsonPrimitive?.intOrNull)
    }
}
