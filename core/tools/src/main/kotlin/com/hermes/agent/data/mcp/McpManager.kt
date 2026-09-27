package com.hermes.agent.data.mcp

import com.hermes.agent.domain.mcp.McpRepository
import com.hermes.agent.domain.mcp.McpServerConfig
import com.hermes.agent.domain.mcp.McpToolDefinition
import com.hermes.agent.domain.tool.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class McpManager @Inject constructor(
    private val mcpRepository: McpRepository,
    private val toolRegistry: ToolRegistry,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clients = ConcurrentHashMap<String, McpClient>()
    private val registeredToolNamesByServer = ConcurrentHashMap<String, MutableSet<String>>()
    private val mutex = Mutex()

    init {
        scope.launch {
            loadAndRegisterCachedTools()
        }
    }

    suspend fun loadAndRegisterCachedTools() = mutex.withLock {
        try {
            val servers = mcpRepository.getAllServers().associateBy { it.id }
            val allCachedTools = mcpRepository.getAllCachedTools()

            for (toolDef in allCachedTools) {
                val server = servers[toolDef.serverId]
                if (server != null && server.enabled) {
                    registerToolInstance(toolDef, server)
                }
            }
            Timber.d("Registered ${allCachedTools.size} cached MCP tools into ToolRegistry")
        } catch (e: Exception) {
            Timber.e(e, "Failed to load cached MCP tools")
        }
    }

    suspend fun syncServer(serverId: String): Result<List<McpToolDefinition>> = mutex.withLock {
        val server = mcpRepository.getServer(serverId)
            ?: return Result.failure(Exception("MCP Server not found: $serverId"))

        if (!server.enabled) {
            // Already holding the lock: unregisterServerTools() would take it again and hang.
            unregisterServerToolsInternal(serverId)
            clients.remove(serverId)?.close()
            return Result.success(emptyList())
        }

        try {
            val client = McpClient(server)
            val initRes = client.initialize()
            if (initRes.isFailure) {
                val err = initRes.exceptionOrNull()?.message ?: "Handshake failed"
                client.close()
                mcpRepository.updateServerError(serverId, err)
                return Result.failure(Exception(err))
            }

            val listRes = client.listTools()
            if (listRes.isFailure) {
                val err = listRes.exceptionOrNull()?.message ?: "Failed to list tools"
                client.close()
                mcpRepository.updateServerError(serverId, err)
                return Result.failure(Exception(err))
            }

            val tools = listRes.getOrNull().orEmpty()
            // An SSE client holds a stream open; the one it replaces must let go of it.
            clients.put(serverId, client)?.takeIf { it !== client }?.close()

            // Save to Room cache
            mcpRepository.saveCachedTools(serverId, tools)
            mcpRepository.updateServerError(serverId, null)

            // Update ToolRegistry
            unregisterServerToolsInternal(serverId)
            for (t in tools) {
                registerToolInstance(t, server)
            }

            Timber.i("Successfully synced ${tools.size} tools from MCP server '${server.name}'")
            Result.success(tools)
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Unknown error syncing MCP server"
            mcpRepository.updateServerError(serverId, errorMsg)
            Result.failure(e)
        }
    }

    suspend fun syncAllServers() {
        val servers = mcpRepository.getAllServers()
        for (server in servers) {
            if (server.enabled) {
                syncServer(server.id)
            } else {
                unregisterServerTools(server.id)
            }
        }
    }

    suspend fun testConnection(server: McpServerConfig): Result<List<McpToolDefinition>> {
        val client = McpClient(server)
        try {
            val initRes = client.initialize()
            if (initRes.isFailure) {
                return Result.failure(initRes.exceptionOrNull() ?: Exception("Handshake failed"))
            }
            return client.listTools()
        } finally {
            client.close()
        }
    }

    suspend fun unregisterServerTools(serverId: String) = mutex.withLock {
        unregisterServerToolsInternal(serverId)
        clients.remove(serverId)?.close()
    }

    private fun unregisterServerToolsInternal(serverId: String) {
        val toolNames = registeredToolNamesByServer.remove(serverId) ?: emptySet()
        for (name in toolNames) {
            toolRegistry.unregister(name)
        }
    }

    private fun registerToolInstance(toolDef: McpToolDefinition, server: McpServerConfig) {
        val toolInstance = McpTool(
            definition = toolDef,
            clientProvider = { getOrConnectClient(server.id) }
        )
        toolRegistry.register(toolInstance)

        val set = registeredToolNamesByServer.getOrPut(server.id) { ConcurrentHashMap.newKeySet() }
        set.add(toolDef.qualifiedName)
    }

    /** Serializes lazy connects: two tool calls racing here would each connect, and the
     *  second would close the first one's client while its call is still running. */
    private val connectMutex = Mutex()

    private suspend fun getOrConnectClient(serverId: String): McpClient? {
        clients[serverId]?.let { return it }
        return connectMutex.withLock { clients[serverId] ?: connect(serverId) }
    }

    private suspend fun connect(serverId: String): McpClient? {
        val server = mcpRepository.getServer(serverId) ?: return null
        if (!server.enabled) return null

        val client = McpClient(server)
        val initRes = client.initialize()
        return if (initRes.isSuccess) {
            // An SSE client holds a stream open; the one it replaces must let go of it.
            clients.put(serverId, client)?.takeIf { it !== client }?.close()
            client
        } else {
            client.close()
            null
        }
    }
}
