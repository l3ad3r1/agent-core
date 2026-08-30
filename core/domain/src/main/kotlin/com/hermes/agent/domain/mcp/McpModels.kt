package com.hermes.agent.domain.mcp

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
enum class McpTransportType {
    HTTP,
    SSE,
}

@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val transport: McpTransportType = McpTransportType.HTTP,
    val headers: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastError: String? = null,
    val supportsParallelCalls: Boolean = false,
)

@Serializable
data class McpToolDefinition(
    val serverId: String,
    val toolName: String,
    val qualifiedName: String,
    val description: String,
    val inputSchemaJson: String,
)

interface McpRepository {
    fun getServers(): Flow<List<McpServerConfig>>
    suspend fun getAllServers(): List<McpServerConfig>
    suspend fun getServer(id: String): McpServerConfig?
    suspend fun saveServer(server: McpServerConfig)
    suspend fun deleteServer(id: String)
    suspend fun updateServerError(id: String, lastError: String?)
    suspend fun setServerEnabled(id: String, enabled: Boolean)
    fun getCachedTools(serverId: String): Flow<List<McpToolDefinition>>
    suspend fun getAllCachedTools(): List<McpToolDefinition>
    suspend fun saveCachedTools(serverId: String, tools: List<McpToolDefinition>)
    suspend fun clearCachedTools(serverId: String)
}
