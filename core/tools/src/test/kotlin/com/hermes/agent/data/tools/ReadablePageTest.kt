package com.hermes.agent.data.tools

import com.hermes.agent.util.net.PublicNetworkGuard
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadablePageTest {

    private val newsPage = """
        <html><head><title>Rain returns to Chennai &#8211; Daily</title>
        <script>var tracking = "SECRET_SCRIPT";</script><style>.x{color:red}</style></head>
        <body>
          <header><a href="/">Daily home</a> <a href="/subscribe">Subscribe now</a></header>
          <nav><ul><li><a href="/world">World</a></li><li><a href="/sport">Sport</a></li></ul></nav>
          <div class="cookie" role="dialog">We use cookies. <button>Accept all</button></div>
          <main>
            <article>
              <h1>Rain returns to Chennai</h1>
              <p>The city&#8217;s reservoirs rose 12&nbsp;% overnight &amp; schools closed.</p>
              <h2>What to expect</h2>
              <ul><li>More showers on Friday</li><li>Clear by <b>Sunday</b></li></ul>
              <p>Read the <a href="/weather/chennai">full forecast</a> or the
                 <a href="https://imd.gov.in/warnings">IMD warnings</a>.</p>
              <pre>  station   mm
  Nungam    84</pre>
            </article>
          </main>
          <aside>Trending: celebrity gossip</aside>
          <footer>&copy; 2026 Daily. <a href="/privacy">Privacy</a></footer>
        </body></html>
    """.trimIndent()

    @Test
    fun `keeps the article and drops page chrome`() {
        val page = ReadablePage.extract(newsPage, "https://daily.example/news/rain")

        assertEquals("Rain returns to Chennai – Daily", page.title)
        val text = page.text
        assertTrue(text, text.contains("# Rain returns to Chennai"))
        assertTrue(text, text.contains("## What to expect"))
        assertTrue(text, text.contains("The city’s reservoirs rose 12 % overnight & schools closed."))
        assertTrue(text, text.contains("- More showers on Friday"))
        assertTrue(text, text.contains("- Clear by Sunday"))
        // (trimIndent above strips the second line's indent before parsing; the first keeps its two spaces.)
        // trimIndent() above strips the second line's indent before parsing; the first keeps its two spaces.
        assertTrue("preformatted text keeps its layout: [$text]", text.contains("  station   mm\nNungam    84"))
        listOf("Subscribe now", "World", "Sport", "cookies", "Accept all", "Trending", "Privacy", "SECRET_SCRIPT", "color:red")
            .forEach { assertFalse("'$it' is page chrome", text.contains(it)) }
    }

    @Test
    fun `lists the content's links as absolute URLs`() {
        val page = ReadablePage.extract(newsPage, "https://daily.example/news/rain")

        assertEquals(
            listOf(
                ReadablePage.Link("full forecast", "https://daily.example/weather/chennai"),
                ReadablePage.Link("IMD warnings", "https://imd.gov.in/warnings"),
            ),
            page.links,
        )
    }

    @Test
    fun `a page without main or article falls back to the body minus chrome`() {
        val html = "<body><nav>Menu</nav><header>Site banner</header><div><p>Only content here.</p></div><footer>Foot</footer></body>"

        val page = ReadablePage.extract(html, "https://x.example/")

        assertEquals("Only content here.", page.text)
    }

    @Test
    fun `web_fetch returns readable text with links, and JSON as it is`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody(newsPage))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"temp": 17}"""))
            // The mock server listens on loopback, which the real guard refuses.
            val tool = WebFetchTool(OkHttpClient(), PublicNetworkGuard { true })

            val html = tool.execute(mapOf("url" to JsonPrimitive(server.url("/news/rain").toString()))).output
            assertTrue(html, html.contains("Title: Rain returns to Chennai – Daily"))
            assertTrue(html, html.contains("The city’s reservoirs"))
            assertTrue(html, html.contains("Links:\n[1] full forecast - http"))
            assertFalse(html, html.contains("Subscribe now"))

            val json = tool.execute(mapOf("url" to JsonPrimitive(server.url("/api").toString()))).output
            assertTrue(json, json.endsWith("""{"temp": 17}"""))
        } finally {
            server.shutdown()
        }
    }
}
