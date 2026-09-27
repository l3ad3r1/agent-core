package com.hermes.agent.data.mcp

import com.hermes.agent.domain.mcp.McpServerConfig
import com.hermes.agent.domain.mcp.McpTransportType
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class McpClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `initialize and listTools over HTTP JSON-RPC 2_0 succeeds`() = runTest {
        // Response for initialize
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {
                        "jsonrpc": "2.0",
                        "id": 1,
                        "result": {
                            "protocolVersion": "2024-11-05",
                            "capabilities": {"tools": {}},
                            "serverInfo": {"name": "test-server", "version": "1.0.0"}
                        }
                    }
                """.trimIndent())
        )

        // Response for notifications/initialized
        server.enqueue(MockResponse().setResponseCode(200))

        // Response for tools/list
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {
                        "jsonrpc": "2.0",
                        "id": 2,
                        "result": {
                            "tools": [
                                {
                                    "name": "echo",
                                    "description": "Echoes back input",
                                    "inputSchema": {
                                        "type": "object",
                                        "properties": {
                                            "message": {"type": "string", "description": "text to echo"}
                                        },
                                        "required": ["message"]
                                    }
                                }
                            ]
                        }
                    }
                """.trimIndent())
        )

        val config = McpServerConfig(
            id = "server-1",
            name = "github",
            url = server.url("/mcp").toString(),
            transport = McpTransportType.HTTP,
            headers = mapOf("Authorization" to "Bearer secret-token-12345")
        )

        val client = McpClient(config)
        val initRes = client.initialize()
        assertTrue(initRes.isSuccess)

        val listRes = client.listTools()
        assertTrue(listRes.isSuccess)
        val tools = listRes.getOrNull()
        assertNotNull(tools)
        assertEquals(1, tools!!.size)
        val tool = tools[0]
        assertEquals("echo", tool.toolName)
        assertEquals("echo", tool.remoteName)
        assertEquals("mcp__github__echo", tool.qualifiedName)
        assertEquals("Echoes back input", tool.description)
    }

    @Test
    fun `listTools preserves mixed case and punctuation remote names`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(
                """{"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"repo.searchV2","inputSchema":{"type":"object"}}]}}""",
            ),
        )
        val client = McpClient(McpServerConfig("server-1", "demo", server.url("/mcp").toString()))

        val tool = client.listTools().getOrThrow().single()

        assertEquals("repo.searchV2", tool.remoteName)
        assertEquals("repo_searchv2", tool.toolName)
    }

    @Test
    fun `callTool succeeds and returns text content`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {
                        "jsonrpc": "2.0",
                        "id": 1,
                        "result": {
                            "content": [
                                {"type": "text", "text": "Hello world from MCP!"}
                            ],
                            "isError": false
                        }
                    }
                """.trimIndent())
        )

        val config = McpServerConfig(
            id = "server-1",
            name = "demo",
            url = server.url("/mcp").toString(),
        )

        val client = McpClient(config)
        val callRes = client.callTool("echo", mapOf("message" to JsonPrimitive("Hello")))
        assertTrue(callRes.isSuccess)
        assertEquals("Hello world from MCP!", callRes.getOrNull())
    }

    @Test
    fun `error message redacts sensitive authorization tokens`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("Error: unauthorized access with secret-token-99999")
        )

        val config = McpServerConfig(
            id = "server-1",
            name = "demo",
            url = server.url("/mcp").toString(),
            headers = mapOf("Authorization" to "Bearer secret-token-99999")
        )

        val client = McpClient(config)
        val callRes = client.callTool("echo", emptyMap())
        assertTrue(callRes.isFailure)
        val err = callRes.exceptionOrNull()?.message ?: ""
        assertFalse(err.contains("secret-token-99999"))
        assertTrue(err.contains("[REDACTED]"))
    }

    private fun rpc(id: Int, result: String) = """{"jsonrpc":"2.0","id":$id,"result":$result}"""

    @Test
    fun `the session id from initialize is sent on every later request`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setHeader("Mcp-Session-Id", "sess-42")
                .setBody(rpc(1, """{"protocolVersion":"2024-11-05"}""")),
        )
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(rpc(2, """{"tools":[]}""")))
        val client = McpClient(McpServerConfig("server-1", "demo", server.url("/mcp").toString()))

        assertTrue(client.initialize().isSuccess)
        assertTrue(client.listTools().isSuccess)

        assertEquals(null, server.takeRequest().getHeader("Mcp-Session-Id"))
        assertEquals("sess-42", server.takeRequest().getHeader("Mcp-Session-Id"))
        assertEquals("sess-42", server.takeRequest().getHeader("Mcp-Session-Id"))
    }

    @Test
    fun `a session the server dropped is re-established and the call resent`() = runTest {
        val json = "application/json"
        server.enqueue(
            MockResponse().setHeader("Content-Type", json).setHeader("Mcp-Session-Id", "old")
                .setBody(rpc(1, """{"protocolVersion":"2024-11-05"}""")),
        )
        server.enqueue(MockResponse().setResponseCode(202))
        // The server restarted: it no longer knows "old".
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(
            MockResponse().setHeader("Content-Type", json).setHeader("Mcp-Session-Id", "new")
                .setBody(rpc(3, """{"protocolVersion":"2024-11-05"}""")),
        )
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(MockResponse().setHeader("Content-Type", json).setBody(rpc(2, """{"tools":[]}""")))
        val client = McpClient(McpServerConfig("server-1", "demo", server.url("/mcp").toString()))

        assertTrue(client.initialize().isSuccess)
        val listed = client.listTools()
        assertTrue(listed.exceptionOrNull()?.message, listed.isSuccess)

        server.takeRequest() // initialize
        server.takeRequest() // notifications/initialized
        assertEquals("old", server.takeRequest().getHeader("Mcp-Session-Id"))
        // The new handshake must not present the dead session.
        val reinit = server.takeRequest()
        assertTrue(reinit.body.readUtf8().contains("\"initialize\""))
        assertEquals(null, reinit.getHeader("Mcp-Session-Id"))
        assertEquals("new", server.takeRequest().getHeader("Mcp-Session-Id"))
        assertEquals("new", server.takeRequest().getHeader("Mcp-Session-Id"))
    }

    @Test
    fun `a 404 without a session is an ordinary failure, not a reconnect loop`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        val client = McpClient(McpServerConfig("server-1", "demo", server.url("/mcp").toString()))

        assertTrue(client.listTools().isFailure)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `an SSE reply is read past the notifications that precede the result`() = runTest {
        val body = "event: message\n" +
            "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\n\n" +
            "event: message\n" +
            "data: " + rpc(1, """{"content":[{"type":"text","text":"done"}]}""") + "\n\n"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body))
        val client = McpClient(McpServerConfig("server-1", "demo", server.url("/mcp").toString()))

        assertEquals("done", client.callTool("echo", emptyMap()).getOrThrow())
    }

    @Test
    fun `SSE transport reads replies from the event stream, not the 202 POST`() = runTest {
        // The stream stays open a few seconds (padding comments, throttled) the way a
        // real server holds it, and carries the replies to initialize and tools/list.
        val stream = "event: endpoint\ndata: /messages?session=1\n\n" +
            "event: message\ndata: " + rpc(1, """{"protocolVersion":"2024-11-05"}""") + "\n\n" +
            "event: message\ndata: " + rpc(2, """{"tools":[{"name":"echo","inputSchema":{"type":"object"}}]}""") + "\n\n" +
            ":\n".repeat(1500)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                if (request.method == "GET") {
                    MockResponse().setHeader("Content-Type", "text/event-stream").setBody(stream)
                        .throttleBody(256, 100, java.util.concurrent.TimeUnit.MILLISECONDS)
                } else {
                    MockResponse().setResponseCode(202)
                }
        }
        val client = McpClient(
            McpServerConfig("server-1", "demo", server.url("/sse").toString(), transport = McpTransportType.SSE),
        )

        val init = client.initialize()
        assertTrue(init.exceptionOrNull()?.message, init.isSuccess)
        assertEquals("echo", client.listTools().getOrThrow().single().remoteName)
        client.close()
    }
}
