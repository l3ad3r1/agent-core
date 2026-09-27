package com.hermes.agent.data.mcp

import com.hermes.agent.data.tool.ToolRegistryImpl
import com.hermes.agent.domain.mcp.McpRepository
import com.hermes.agent.domain.mcp.McpServerConfig
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class McpManagerTest {

    @Test
    fun `syncing a disabled server returns instead of deadlocking on its own lock`() = runBlocking {
        val repository = mockk<McpRepository>(relaxed = true)
        coEvery { repository.getServer("s1") } returns
            McpServerConfig("s1", "demo", "http://127.0.0.1:9/mcp", enabled = false)
        val manager = McpManager(repository, ToolRegistryImpl())

        val result = withTimeout(5_000) { manager.syncServer("s1") }

        assertTrue(result.isSuccess)
    }
}
