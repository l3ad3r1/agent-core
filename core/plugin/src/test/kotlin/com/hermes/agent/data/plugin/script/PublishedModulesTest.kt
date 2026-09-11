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
        listOf(
            "word-count", "text-tools", "unit-convert", "date-math", "json-format",
            "base64", "color-convert", "hash-digest", "csv-summarize", "cipher-text",
        ).forEach { id ->
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
    fun `base64 encodes and decodes text, including multi-byte UTF-8`() = runTest {
        val manifest = manifest("base64")
        val engine = ScriptPluginEngine()
        engine.load(manifest)

        assertEquals(
            "SGVsbG8sIEhlcm1lcyE=",
            engine.run(manifest, "base64_convert", mapOf("text" to "Hello, Hermes!", "operation" to "encode")),
        )
        assertEquals(
            "Hello, Hermes!",
            engine.run(manifest, "base64_convert", mapOf("text" to "SGVsbG8sIEhlcm1lcyE=", "operation" to "decode")),
        )
        assertEquals(
            "Y2Fmw6kg4pyT",
            engine.run(manifest, "base64_convert", mapOf("text" to "café ✓", "operation" to "encode")),
        )
        assertEquals(
            "café ✓",
            engine.run(manifest, "base64_convert", mapOf("text" to "Y2Fmw6kg4pyT", "operation" to "decode")),
        )
    }

    @Test
    fun `color convert handles hex, rgb, and hsl`() = runTest {
        val manifest = manifest("color-convert")
        val engine = ScriptPluginEngine()
        engine.load(manifest)

        assertEquals(
            "rgb(255, 87, 51)",
            engine.run(manifest, "color_convert", mapOf("value" to "#ff5733", "to" to "rgb")),
        )
        assertEquals(
            "#ff5733",
            engine.run(manifest, "color_convert", mapOf("value" to "rgb(255, 87, 51)", "to" to "hex")),
        )
        assertEquals(
            "hsl(0, 100%, 50%)",
            engine.run(manifest, "color_convert", mapOf("value" to "#ff0000", "to" to "hsl")),
        )
        assertEquals(
            "#0000ff",
            engine.run(manifest, "color_convert", mapOf("value" to "hsl(240, 100%, 50%)", "to" to "hex")),
        )
    }

    @Test
    fun `hash text matches known digests, including multi-byte UTF-8`() = runTest {
        val manifest = manifest("hash-digest")
        val engine = ScriptPluginEngine()
        engine.load(manifest)

        assertEquals(
            "900150983cd24fb0d6963f7d28e17f72",
            engine.run(manifest, "hash_text", mapOf("text" to "abc", "algorithm" to "md5")),
        )
        assertEquals(
            "a9993e364706816aba3e25717850c26c9cd0d89d",
            engine.run(manifest, "hash_text", mapOf("text" to "abc", "algorithm" to "sha1")),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            engine.run(manifest, "hash_text", mapOf("text" to "abc", "algorithm" to "sha256")),
        )
        assertEquals(
            // cross-checked against Node's crypto.createHash('sha256') for this exact UTF-8 string
            "3c15bbb0672ec7f843be05677dce1b0c2fb7e64a16618e498decbbdf3b6cd6e2",
            engine.run(manifest, "hash_text", mapOf("text" to "café ✓", "algorithm" to "sha256")),
        )
    }

    @Test
    fun `csv summarize reports numeric and categorical columns`() = runTest {
        val manifest = manifest("csv-summarize")
        val engine = ScriptPluginEngine()
        engine.load(manifest)

        val output = engine.run(
            manifest,
            "csv_summarize",
            mapOf("csv" to "name,age,city\nAlice,30,NYC\nBob,25,LA\nCarol,30,NYC\n"),
        )
        assertTrue(output, output.contains("Rows: 3, Columns: 3"))
        assertTrue(output, output.contains("age: numeric, min=25, max=30, mean=28.33"))
        assertTrue(output, output.contains("city: categorical, 2 distinct, most common=\"NYC\" (2)"))
    }

    @Test
    fun `cipher text handles rot13, caesar, and morse`() = runTest {
        val manifest = manifest("cipher-text")
        val engine = ScriptPluginEngine()
        engine.load(manifest)

        assertEquals(
            "Uryyb, Jbeyq!",
            engine.run(manifest, "cipher_text", mapOf("text" to "Hello, World!", "cipher" to "rot13")),
        )
        assertEquals(
            "def",
            engine.run(manifest, "cipher_text", mapOf("text" to "abc", "cipher" to "caesar", "shift" to "3")),
        )
        assertEquals(
            "... --- ...",
            engine.run(manifest, "cipher_text", mapOf("text" to "SOS", "cipher" to "morse_encode")),
        )
        assertEquals(
            "SOS",
            engine.run(manifest, "cipher_text", mapOf("text" to "... --- ...", "cipher" to "morse_decode")),
        )
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
