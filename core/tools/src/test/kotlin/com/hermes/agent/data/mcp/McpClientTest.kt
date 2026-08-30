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
        assertEquals("mcp__github__echo", tool.qualifiedName)
        assertEquals("Echoes back input", tool.description)
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
}
