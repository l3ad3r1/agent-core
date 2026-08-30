package com.hermes.agent.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.hermes.agent.domain.mcp.McpServerConfig
import com.hermes.agent.domain.mcp.McpToolDefinition
import com.hermes.agent.domain.mcp.McpTransportType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(tableName = "mcp_servers")
data class McpServerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val url: String,
    val transport: String,
    val headersJson: String,
    val enabled: Boolean,
    val createdAt: Long,
    val lastError: String?,
    val supportsParallelCalls: Boolean,
) {
    fun toDomain(): McpServerConfig = McpServerConfig(
        id = id,
        name = name,
        url = url,
        transport = try { McpTransportType.valueOf(transport) } catch (e: Exception) { McpTransportType.HTTP },
        headers = try { Json.decodeFromString(headersJson) } catch (e: Exception) { emptyMap() },
        enabled = enabled,
        createdAt = createdAt,
        lastError = lastError,
        supportsParallelCalls = supportsParallelCalls,
    )

    companion object {
        fun fromDomain(s: McpServerConfig): McpServerEntity = McpServerEntity(
            id = s.id,
            name = s.name,
            url = s.url,
            transport = s.transport.name,
            headersJson = Json.encodeToString(s.headers),
            enabled = s.enabled,
            createdAt = s.createdAt,
            lastError = s.lastError,
            supportsParallelCalls = s.supportsParallelCalls,
        )
    }
}

@Entity(
    tableName = "mcp_tools",
    primaryKeys = ["serverId", "toolName"],
)
data class McpToolEntity(
    val serverId: String,
    val toolName: String,
    val qualifiedName: String,
    val description: String,
    val inputSchemaJson: String,
) {
    fun toDomain(): McpToolDefinition = McpToolDefinition(
        serverId = serverId,
        toolName = toolName,
        qualifiedName = qualifiedName,
        description = description,
        inputSchemaJson = inputSchemaJson,
    )

    companion object {
        fun fromDomain(t: McpToolDefinition): McpToolEntity = McpToolEntity(
            serverId = t.serverId,
            toolName = t.toolName,
            qualifiedName = t.qualifiedName,
            description = t.description,
            inputSchemaJson = t.inputSchemaJson,
        )
    }
}
