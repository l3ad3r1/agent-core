package com.hermes.agent.data.plugin.script

import com.hermes.agent.domain.repository.BookmarkRepository
import com.hermes.agent.domain.repository.NotesRepository
import com.hermes.agent.domain.repository.TodoRepository
import com.hermes.agent.util.net.PublicNetworkGuard
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class ModuleHostsTest {

    @Test
    fun `entries match exactly, or by subdomain when wildcarded`() {
        val hosts = listOf("api.open-meteo.com", "*.example.org")
        assertTrue(ModuleHosts.matches("api.open-meteo.com", hosts))
        assertTrue(ModuleHosts.matches("API.Open-Meteo.com.", hosts))
        assertTrue(ModuleHosts.matches("cdn.example.org", hosts))
        assertFalse(ModuleHosts.matches("example.org", hosts))
        assertFalse(ModuleHosts.matches("open-meteo.com", hosts))
        assertFalse(ModuleHosts.matches("api.open-meteo.com.evil.net", hosts))
        assertFalse(ModuleHosts.matches("evilexample.org", hosts))
        assertTrue("no list means any host", ModuleHosts.matches("anything.net", emptyList()))
    }

    @Test
    fun `only bare hostnames are valid entries`() {
        listOf("api.example.com", "*.example.com", "a-b.c.io").forEach { assertTrue(it, ModuleHosts.isValidEntry(it)) }
        listOf(
            "https://api.example.com", "api.example.com/path", "api.example.com:443", "localhost",
            "192.168.1.1", "API.Example.com", "*", "*.com", "a..b.com", "-a.com",
        ).forEach { assertFalse(it, ModuleHosts.isValidEntry(it)) }
    }

    @Test
    fun `the install prompt names the hosts`() {
        assertEquals("Connect to any website", ScriptPluginPermissions.describe(ScriptPluginPermissions.NETWORK))
        assertEquals(
            "Connect to api.frankfurter.app",
            ScriptPluginPermissions.describe(ScriptPluginPermissions.NETWORK, listOf("api.frankfurter.app")),
        )
    }

    private fun host() = ScriptPluginHostImpl(
        mockk<NotesRepository>(), mockk<TodoRepository>(), mockk<BookmarkRepository>(),
        OkHttpClient(),
        // The test server is on loopback; this test is about the host list, not the network guard.
        PublicNetworkGuard { true },
    )

    // MockWebServer names its URL by reverse DNS, which can be any alias of loopback.
    private fun literal(server: MockWebServer, path: String) =
        server.url(path).newBuilder().host("127.0.0.1").build().toString()

    @Test
    fun `a module reaches its declared host but not a redirect off it`() {
        val server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        try {
            server.enqueue(MockResponse().setBody("ok"))
            val allowed = listOf("127.0.0.1")
            assertEquals("ok", host().httpGet("m", literal(server, "/a"), allowed))

            // Same server, reached by a name the module did not declare.
            val other = server.url("/b").newBuilder().host("localhost").build()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other))
            val error = assertThrows(java.io.IOException::class.java) {
                host().httpGet("m", literal(server, "/c"), allowed)
            }
            assertTrue(error.message, error.message!!.contains("may only connect to 127.0.0.1"))
            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `the engine hands the module's hosts to the host`() = runTest {
        var seen: List<String>? = null
        val engine = ScriptPluginEngine()
        engine.host = object : ScriptPluginHost {
            override fun log(pluginId: String, message: String) = Unit
            override fun httpGet(pluginId: String, url: String, allowedHosts: List<String>): String {
                seen = allowedHosts
                return "body"
            }
            override fun readData(pluginId: String, collection: String, query: String) = ""
            override fun writeData(pluginId: String, collection: String, payload: String) = ""
        }
        engine.reload(
            listOf(
                ScriptPluginEngine.PluginSpec(
                    id = "fx",
                    source = "hermes.registerTool('rate', function (args) { return hermes.http.get('https://api.frankfurter.app/latest'); });",
                    permissions = setOf(ScriptPluginPermissions.NETWORK),
                    hosts = listOf("api.frankfurter.app"),
                ),
            ),
        )

        assertEquals("body", engine.execute("fx", "rate", emptyMap()).getOrThrow())
        assertEquals(listOf("api.frankfurter.app"), seen)
    }
}
