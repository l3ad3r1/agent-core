package com.hermes.agent.data.tool

import com.hermes.agent.data.security.OutputRedactor
import com.hermes.agent.data.tools.ReadToolResultTool
import com.hermes.agent.data.tools.WebFetchTool
import com.hermes.agent.domain.llm.ToolCall
import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.domain.settings.UserSettings
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolResult
import com.hermes.agent.util.net.PublicNetworkGuard
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultOverflowTest {

    private val store = ToolResultStore()
    private val reader = ReadToolResultTool(store)

    private fun redactor(settings: UserSettings = UserSettings()): OutputRedactor {
        val repo = mockk<SettingsRepository>(relaxed = true)
        coEvery { repo.current() } returns settings
        return OutputRedactor(repo)
    }

    private fun executorFor(output: String, limit: Int = 1_000, settings: UserSettings = UserSettings()): ToolCallExecutor {
        val registry = ToolRegistryImpl()
        registry.register(object : Tool {
            override val descriptor = ToolDescriptor("big", "stub", emptyList(), maxResultSizeChars = limit)
            override suspend fun execute(arguments: Map<String, JsonElement>) = ToolResult.ok(output)
        })
        registry.register(reader)
        return ToolCallExecutor(registry, redactor(settings), store)
    }

    private val footerId = Regex("result_id \"(res_[0-9a-f]+)\" and offset (\\d+)")

    /** Follows the footers like a model would and returns everything it was shown. */
    private suspend fun readAll(executor: ToolCallExecutor, first: String): String {
        val seen = StringBuilder(first.substringBefore("\n\n[Showing"))
        var footer = footerId.find(first)
        while (footer != null) {
            val (id, offset) = footer.destructured
            val page = executor.execute(
                ToolCall("r", ReadToolResultTool.NAME, mapOf("result_id" to JsonPrimitive(id), "offset" to JsonPrimitive(offset.toInt()))),
            ).output
            seen.append(page.substringBefore("\n\n[Showing"))
            footer = footerId.find(page)
        }
        return seen.toString()
    }

    @Test
    fun `a long result is previewed, and the rest can be read to the end`() = runTest {
        val log = (1..2_000).joinToString("\n") { "line $it of the build log" }
        val executor = executorFor(log)

        val first = executor.execute(ToolCall("c", "big", emptyMap())).output

        assertTrue("preview stays near the limit", first.length < 1_300)
        assertTrue(first, first.contains("of ${log.length}. The full output is saved as result_id"))
        assertTrue("preview ends on a whole line", first.substringBefore("\n\n[Showing").endsWith("of the build log\n"))
        assertEquals(log, readAll(executor, first))
    }

    @Test
    fun `a short result is untouched`() = runTest {
        assertEquals("fits", executorFor("fits").execute(ToolCall("c", "big", emptyMap())).output)
    }

    @Test
    fun `what is stored has already been redacted`() = runTest {
        val secret = "sk-live-0123456789abcdefghij"
        val output = "x".repeat(2_000) + "\nkey=$secret\n" + "y".repeat(2_000)
        val executor = executorFor(output, settings = UserSettings(cloudApiKey = secret))

        val everything = readAll(executor, executor.execute(ToolCall("c", "big", emptyMap())).output)

        assertFalse("the secret must not survive into the store", everything.contains(secret))
    }

    @Test
    fun `a redaction failure never exposes or stores the raw tool output`() = runTest {
        val registry = ToolRegistryImpl()
        registry.register(object : Tool {
            override val descriptor = ToolDescriptor("secret", "stub", emptyList(), maxResultSizeChars = 100)
            override suspend fun execute(arguments: Map<String, JsonElement>) = ToolResult.ok("sensitive".repeat(1_000))
        })
        val failedRedactor = mockk<OutputRedactor>()
        coEvery { failedRedactor.redact(any()) } throws IllegalStateException("credentials unavailable")

        val result = ToolCallExecutor(registry, failedRedactor, store).execute(ToolCall("c", "secret", emptyMap()))

        assertFalse(result.success)
        assertEquals("", result.output)
        assertEquals("Tool output could not be redacted safely.", result.errorMessage)
    }

    @Test
    fun `unavailable configured credentials fail closed before paging`() = runTest {
        val repo = mockk<SettingsRepository>()
        coEvery { repo.current() } throws IllegalStateException("settings unavailable")
        val registry = ToolRegistryImpl()
        registry.register(object : Tool {
            override val descriptor = ToolDescriptor("secret", "stub", emptyList(), maxResultSizeChars = 100)
            override suspend fun execute(arguments: Map<String, JsonElement>) = ToolResult.ok("arbitrary-password".repeat(1_000))
        })

        val result = ToolCallExecutor(registry, OutputRedactor(repo), store).execute(ToolCall("c", "secret", emptyMap()))

        assertFalse(result.success)
        assertEquals("", result.output)
        assertEquals("Tool output could not be redacted safely.", result.errorMessage)
    }

    @Test
    fun `an unknown or expired id and a bad offset say what to do`() = runTest {
        val unknown = reader.execute(mapOf("result_id" to JsonPrimitive("res_missing")))
        assertFalse(unknown.success)
        assertTrue(unknown.errorMessage!!, unknown.errorMessage!!.contains("Run the original tool again"))

        val id = footerId.find(store.overflow("big", "a".repeat(5_000), 1_000))!!.groupValues[1]
        val past = reader.execute(mapOf("result_id" to JsonPrimitive(id), "offset" to JsonPrimitive(9_999)))
        assertFalse(past.success)
        assertTrue(past.errorMessage!!.contains("past the end"))
    }

    @Test
    fun `only the most recent results are kept`() = runTest {
        val ids = (1..ToolResultStore.MAX_ENTRIES + 1).map {
            footerId.find(store.overflow("big", "b".repeat(3_000), 1_000))!!.groupValues[1]
        }
        assertEquals(null, store.get(ids.first()))
        assertTrue(store.get(ids.last()) != null)
    }

    @Test
    fun `memory pressure evicts as many old results as necessary`() {
        val older = (1..3).map {
            footerId.find(store.overflow("big", "a".repeat(1_000_000), 1_000))!!.groupValues[1]
        }
        val latest = footerId.find(store.overflow("big", "b".repeat(3_500_000), 1_000))!!.groupValues[1]

        older.forEach { assertEquals(null, store.get(it)) }
        assertTrue(store.get(latest) != null)
    }

    @Test
    fun `an oversized result does not advertise an unavailable stored id`() {
        val preview = store.overflow("big", "x".repeat(ToolResultStore.MAX_TOTAL_CHARS.toInt() + 1), 1_000)

        assertFalse(preview.contains("result_id"))
        assertTrue(preview.contains("remainder was not saved"))
    }

    @Test
    fun `web_fetch keeps a page longer than max_chars`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val paragraphs = (1..400).joinToString("") { "<p>Paragraph $it of the long article.</p>" }
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<main>$paragraphs</main>"))
            val registry = ToolRegistryImpl()
            // The mock server listens on loopback, which the real guard refuses.
            registry.register(WebFetchTool(OkHttpClient(), PublicNetworkGuard { true }, store))
            registry.register(reader)
            val executor = ToolCallExecutor(registry, redactor(), store)

            val first = executor.execute(
                ToolCall("w", "web_fetch", mapOf("url" to JsonPrimitive(server.url("/a").toString()), "max_chars" to JsonPrimitive(2_000))),
            ).output

            assertTrue(first, first.length < 2_400 && first.contains("read_tool_result"))
            val everything = readAll(executor, first)
            assertTrue(everything.contains("Paragraph 1 of the long article."))
            assertTrue(everything.contains("Paragraph 400 of the long article."))
        } finally {
            server.shutdown()
        }
    }
}
