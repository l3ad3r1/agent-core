package com.hermes.agent.data.tools.browser

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserToolTest {

    /** A scripted page: what the snapshot script "finds", and a log of what the tool asked for. */
    private class FakeEngine : BrowserEngine {
        val calls = mutableListOf<String>()
        var page = BrowserTool.Snapshot()
        var blocked: String? = null
        var clickResult = """{"ok":true}"""
        var closed = false

        override suspend fun load(url: String, timeoutMs: Long) { calls += "load $url" }
        override suspend fun evaluate(script: String): String = when {
            script == BrowserScripts.SNAPSHOT -> Json.encodeToString(BrowserTool.Snapshot.serializer(), page)
            script.contains(".click()") -> clickResult.also { calls += "click" }
            script.contains("dispatchEvent(new Event('input'") -> """{"ok":true}""".also { calls += "type" }
            else -> "null"
        }
        override suspend fun settle(timeoutMs: Long) { calls += "settle" }
        override suspend fun back(timeoutMs: Long): Boolean { calls += "back"; return false }
        override fun takeBlockedNavigation(): String? = blocked.also { blocked = null }
        override fun close() { closed = true }
    }

    private val engine = FakeEngine()
    private val tool = BrowserTool { engine }

    private fun args(vararg pairs: Pair<String, Any>): Map<String, JsonElement> = pairs.associate { (k, v) ->
        k to when (v) { is Boolean -> JsonPrimitive(v); else -> JsonPrimitive(v.toString()) }
    }

    private val shop = BrowserTool.Snapshot(
        title = "Search – Shop",
        url = "https://shop.example/search",
        elements = listOf(
            BrowserTool.Element("e1", "input", "", placeholder = "Search products"),
            BrowserTool.Element("e2", "button", "Search"),
            BrowserTool.Element("e3", "link", "Next page", href = "https://shop.example/search?p=2"),
            BrowserTool.Element("e4", "button", "Buy now"),
        ),
        html = "<html><body><nav>Menu</nav><main><h1>Results</h1><p>Kettle &#8211; &#8377;1,299</p></main></body></html>",
    )

    @Test
    fun `open loads the page and returns elements and rendered text`() = runTest {
        engine.page = shop

        val out = tool.execute(args("action" to "open", "url" to "https://shop.example/search")).output

        assertEquals("load https://shop.example/search", engine.calls.first())
        assertTrue(out, out.startsWith("URL: https://shop.example/search\nTitle: Search – Shop"))
        assertTrue(out, out.contains("[e1] input (Search products)"))
        assertTrue(out, out.contains("[e3] link \"Next page\" -> https://shop.example/search?p=2"))
        assertTrue(out, out.contains("# Results"))
        assertTrue(out, out.contains("Kettle – ₹1,299"))
        assertFalse("page chrome is dropped", out.contains("Menu"))
    }

    @Test
    fun `every click and typing asks first, reading does not`() = runTest {
        engine.page = shop
        tool.execute(args("action" to "open", "url" to "https://shop.example/search"))

        assertTrue("a page-made link can still act", tool.requiresConfirmation(args("action" to "click", "ref" to "e3")))
        assertTrue("a button can submit or buy", tool.requiresConfirmation(args("action" to "click", "ref" to "e4")))
        assertTrue(tool.requiresConfirmation(args("action" to "type", "ref" to "e1", "text" to "kettle")))
        assertTrue("an unknown ref is not waved through", tool.requiresConfirmation(args("action" to "click", "ref" to "e99")))
        assertFalse(tool.requiresConfirmation(args("action" to "open", "url" to "https://x.example")))
        assertFalse(tool.requiresConfirmation(args("action" to "snapshot")))
    }

    @Test
    fun `click and type act on the snapshot's elements, then re-read the page`() = runTest {
        engine.page = shop
        tool.execute(args("action" to "open", "url" to "https://shop.example/search"))

        val typed = tool.execute(args("action" to "type", "ref" to "e1", "text" to "kettle", "submit" to true))
        val clicked = tool.execute(args("action" to "click", "ref" to "e3"))

        assertTrue(typed.success && clicked.success)
        assertEquals(listOf("load https://shop.example/search", "type", "settle", "click", "settle"), engine.calls)
    }

    @Test
    fun `an element that is not on the page, or vanished, is reported`() = runTest {
        engine.page = shop
        tool.execute(args("action" to "open", "url" to "https://shop.example/search"))

        val unknown = tool.execute(args("action" to "click", "ref" to "e42"))
        assertFalse(unknown.success)
        assertTrue(unknown.errorMessage!!.contains("No element 'e42'"))

        engine.clickResult = """{"ok":false,"error":"missing"}"""
        val gone = tool.execute(args("action" to "click", "ref" to "e3"))
        assertFalse(gone.success)
        assertTrue(gone.errorMessage!!.contains("page changed"))
    }

    @Test
    fun `a refused navigation is reported, not shown as a page`() = runTest {
        engine.page = shop
        engine.blocked = "http://192.168.1.1/"

        val result = tool.execute(args("action" to "open", "url" to "http://192.168.1.1/"))

        assertFalse(result.success)
        assertTrue(result.errorMessage!!, result.errorMessage!!.contains("only goes to public http(s) sites"))
    }

    @Test
    fun `actions before open, bad URLs and close behave`() = runTest {
        assertTrue(tool.execute(args("action" to "snapshot")).errorMessage!!.contains("No page is open"))
        assertTrue(tool.execute(args("action" to "open", "url" to "file:///sdcard/secret")).errorMessage!!.contains("http"))
        assertTrue(tool.execute(args("action" to "open", "url" to "javascript:alert(1)")).errorMessage!!.contains("http"))

        engine.page = shop
        tool.execute(args("action" to "open", "url" to "https://shop.example/"))
        assertEquals("Browser closed.", tool.execute(args("action" to "close")).output)
        assertTrue(engine.closed)
        assertTrue(tool.execute(args("action" to "snapshot")).errorMessage!!.contains("No page is open"))
    }
}
