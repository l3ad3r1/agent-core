package com.hermes.agent.data.plugin.script

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs the JavaScript actually published in the module repository.
 *
 * A module ships as JSON containing a JS string, so a stray escape or a typo in
 * the script is invisible to the manifest schema and only surfaces when a user
 * installs it and the tool silently fails. This executes the real published
 * source through the real engine.
 *
 * Skips when the sibling module checkout is absent, so CI without that repo
 * stays green.
 */
class PublishedModulesTest {

    private val modulesRoot = File("../../../hermes-jeeves-modules/modules")

    private fun manifest(id: String): ScriptPluginManifest {
        val file = File(modulesRoot, "$id/manifest.json")
        assumeTrue("module repo not checked out beside agent-core", file.exists())
        return ScriptPluginManifest.json.decodeFromString(file.readText())
    }

    private suspend fun ScriptPluginEngine.load(manifest: ScriptPluginManifest) {
        val failures = reload(
            listOf(
                ScriptPluginEngine.PluginSpec(
                    id = manifest.id,
                    source = manifest.main,
                    permissions = manifest.permissions.toSet(),
                ),
            ),
        )
        assertTrue("module ${manifest.id} failed to load: $failures", failures.isEmpty())
    }

    private suspend fun ScriptPluginEngine.run(
        manifest: ScriptPluginManifest,
        tool: String,
        args: Map<String, String>,
    ): String {
        val result = execute(manifest.id, tool, args.mapValues { JsonPrimitive(it.value) })
        assertTrue("$tool failed: ${result.exceptionOrNull()}", result.isSuccess)
        return result.getOrThrow()
    }

    /** Every published module must declare the tools its script actually registers. */
    @Test
    fun `every module registers the tools its manifest declares`() = runTest {
        assumeTrue(modulesRoot.exists())
        listOf("word-count", "text-tools", "unit-convert", "date-math", "json-format").forEach { id ->
            val manifest = manifest(id)
            val engine = ScriptPluginEngine()
            engine.load(manifest)

            val registered = engine.registeredToolNames(manifest.id).toSet()
            val declared = manifest.tools.map { it.name }.toSet()
            assertEquals("module $id declares tools it does not register", declared, registered)
        }
    }

    @Test
    fun `word count reports counts`() = runTest {
        val manifest = manifest("word-count")
        val engine = ScriptPluginEngine()
        engine.load(manifest)

        val output = engine.run(manifest, "word_count", mapOf("text" to "one two three. four five!"))
        assertTrue(output, output.contains("Words: 5"))
        assertTrue(output, output.contains("Sentences: 2"))
    }

    @Test
    fun `text transform handles each documented operation`() = runTest {
        val manifest = manifest("text-tools")
        val engine = ScriptPluginEngine()
        engine.load(manifest)

        assertEquals("HELLO", engine.run(manifest, "text_transform", mapOf("text" to "hello", "operation" to "upper")))
        assertEquals("hello", engine.run(manifest, "text_transform", mapOf("text" to "HeLLo", "operation" to "lower")))
        assertEquals("Hello World", engine.run(manifest, "text_transform", mapOf("text" to "hello world", "operation" to "title")))
        assertEquals("olleh", engine.run(manifest, "text_transform", mapOf("text" to "hello", "operation" to "reverse")))
        assertEquals("my-blog-post", engine.run(manifest, "text_transform", mapOf("text" to "My Blog Post!", "operation" to "slug")))
        assertEquals("a b", engine.run(manifest, "text_transform", mapOf("text" to "  a   b  ", "operation" to "strip")))
    }

    @Test
    fun `unit convert covers length mass and temperature`() = runTest {
        val manifest = manifest("unit-convert")
        val engine = ScriptPluginEngine()
        engine.load(manifest)

        assertTrue(engine.run(manifest, "unit_convert", mapOf("value" to "1", "from" to "km", "to" to "m")).contains("1000"))
        assertTrue(engine.run(manifest, "unit_convert", mapOf("value" to "1", "from" to "kg", "to" to "g")).contains("1000"))
        assertTrue(engine.run(manifest, "unit_convert", mapOf("value" to "100", "from" to "c", "to" to "f")).contains("212"))

        // A cross-dimension request must be refused, not silently answered.
        val bad = engine.run(manifest, "unit_convert", mapOf("value" to "1", "from" to "kg", "to" to "m"))
        assertTrue(bad, bad.contains("Cannot convert"))
    }

    @Test
    fun `date math measures and shifts dates`() = runTest {
        val manifest = manifest("date-math")
        val engine = ScriptPluginEngine()
        engine.load(manifest)

        val diff = engine.run(manifest, "date_diff", mapOf("from" to "2026-01-01", "to" to "2026-01-08"))
        assertTrue(diff, diff.contains("7 days"))

        assertEquals("2026-02-10", engine.run(manifest, "date_add", mapOf("date" to "2026-01-31", "days" to "10")))
        assertEquals("2026-01-21", engine.run(manifest, "date_add", mapOf("date" to "2026-01-31", "days" to "-10")))
    }

    @Test
    fun `json format pretty prints and reports invalid input`() = runTest {
        val manifest = manifest("json-format")
        val engine = ScriptPluginEngine()
        engine.load(manifest)

        val pretty = engine.run(manifest, "json_format", mapOf("text" to """{"b":1,"a":[2,3]}"""))
        assertTrue(pretty, pretty.contains("\n"))

        val invalid = engine.run(manifest, "json_format", mapOf("text" to "{not json"))
        assertTrue(invalid, invalid.startsWith("Invalid JSON"))
    }
}
