package com.hermes.agent.util.net

import com.hermes.agent.data.tools.WebFetchTool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

class PublicNetworkGuardTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun ip(text: String) = InetAddress.getByName(text)

    // MockWebServer names its URL by reverse DNS, which can be any alias of loopback.
    private fun MockWebServer.literal(path: String, address: String = "127.0.0.1") =
        url(path).newBuilder().host(address).build()

    @Test
    fun `private, local and reserved addresses are refused`() {
        listOf(
            "127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1", "169.254.169.254",
            "100.64.0.1", "100.127.255.254", "0.0.0.0", "224.0.0.1", "255.255.255.255", "198.18.0.1",
            "192.0.2.10", "::1", "::", "fe80::1", "fc00::1", "fd12:3456::1", "::ffff:192.168.1.1",
            "64:ff9b::a00:1", "2002:c0a8:101::1", "2001:0:4136:e378::1", "2001:db8::1",
        ).forEach { assertFalse("$it must be refused", PublicNetworkGuard.isPublicAddress(ip(it))) }
    }

    @Test
    fun `public addresses are allowed`() {
        listOf(
            "8.8.8.8", "1.1.1.1", "172.15.0.1", "172.32.0.1", "100.63.0.1", "100.128.0.1",
            "2606:4700:4700::1111", "::ffff:8.8.8.8", "64:ff9b::808:808", "2002:808:808::1",
        ).forEach { assertTrue("$it must be allowed", PublicNetworkGuard.isPublicAddress(ip(it))) }
    }

    @Test
    fun `a hostname resolving to loopback is refused without connecting`() {
        val client = PublicNetworkGuard().restrict(OkHttpClient())
        val url = server.url("/").newBuilder().host("localhost").build()

        assertBlocked { client.newCall(Request.Builder().url(url).build()).execute().close() }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an IP-literal URL, which skips DNS, is refused before the request is sent`() {
        val client = PublicNetworkGuard().restrict(OkHttpClient())

        assertBlocked { client.newCall(Request.Builder().url(server.literal("/")).build()).execute().close() }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a refused address is never even connected to, so no TLS handshake reaches it`() {
        // Found on device: checking after connect still let https://192.168.1.1/
        // open a socket and start TLS with the router. A connect that completed
        // would sit in this listener's backlog; none may be there.
        java.net.ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { listener ->
            val client = PublicNetworkGuard().restrict(OkHttpClient())
            val url = "https://127.0.0.1:${listener.localPort}/"

            assertBlocked { client.newCall(Request.Builder().url(url).build()).execute().close() }

            listener.soTimeout = 300
            try {
                listener.accept().close()
                fail("the guard let a TCP connection through")
            } catch (expected: java.net.SocketTimeoutException) {
            }
        }
    }

    @Test
    fun `a redirect to a refused address is stopped at the hop`() {
        // Two loopback addresses stand in for "public" and "private": the guard
        // allows only 127.0.0.1, and that server redirects to 127.0.0.2.
        val inner = MockWebServer().apply { start(InetAddress.getByName("127.0.0.2"), 0) }
        try {
            inner.enqueue(MockResponse().setBody("router admin"))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", inner.literal("/admin", "127.0.0.2")))
            val guard = PublicNetworkGuard { it.hostAddress == "127.0.0.1" }
            val client = guard.restrict(OkHttpClient())

            assertBlocked { client.newCall(Request.Builder().url(server.literal("/start")).build()).execute().close() }
            assertEquals(1, server.requestCount)
            assertEquals(0, inner.requestCount)
        } finally {
            inner.shutdown()
        }
    }

    @Test
    fun `web_fetch refuses a local address and says why`() = runTest {
        server.enqueue(MockResponse().setBody("<html>secret</html>"))
        val tool = WebFetchTool(OkHttpClient(), PublicNetworkGuard())

        val result = tool.execute(mapOf("url" to JsonPrimitive(server.literal("/").toString())))

        assertFalse(result.success)
        assertTrue(result.errorMessage, result.errorMessage!!.contains("private or local network"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `web_fetch still reads a page the guard allows`() = runTest {
        server.enqueue(MockResponse().setBody("<html><body><p>Hello public web</p></body></html>"))
        val tool = WebFetchTool(OkHttpClient(), PublicNetworkGuard { true })

        val result = tool.execute(mapOf("url" to JsonPrimitive(server.literal("/").toString())))

        assertTrue(result.errorMessage, result.success)
        assertTrue(result.output.contains("Hello public web"))
    }

    private fun assertBlocked(block: () -> Unit) {
        try {
            block()
            fail("expected the request to be refused")
        } catch (e: PublicNetworkGuard.BlockedDestinationException) {
            assertTrue(e.message!!.contains("private or local network"))
        }
    }
}
