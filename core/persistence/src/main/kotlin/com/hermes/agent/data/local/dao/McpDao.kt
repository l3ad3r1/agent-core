package com.hermes.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.hermes.agent.data.local.entity.McpServerEntity
import com.hermes.agent.data.local.entity.McpToolEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface McpDao {
    @Query("SELECT * FROM mcp_servers ORDER BY createdAt ASC")
    fun observeServers(): Flow<List<McpServerEntity>>

    @Query("SELECT * FROM mcp_servers ORDER BY createdAt ASC")
    suspend fun getAllServers(): List<McpServerEntity>

    @Query("SELECT * FROM mcp_servers WHERE id = :id")
    suspend fun getServerById(id: String): McpServerEntity?

    @Upsert
    suspend fun upsertServer(server: McpServerEntity)

    @Query("DELETE FROM mcp_servers WHERE id = :id")
    suspend fun deleteServer(id: String)

    @Query("UPDATE mcp_servers SET lastError = :lastError WHERE id = :id")
    suspend fun updateServerError(id: String, lastError: String?)

    @Query("UPDATE mcp_servers SET enabled = :enabled WHERE id = :id")
    suspend fun setServerEnabled(id: String, enabled: Boolean)

    @Query("SELECT * FROM mcp_tools WHERE serverId = :serverId ORDER BY toolName ASC")
    fun observeToolsByServer(serverId: String): Flow<List<McpToolEntity>>

    @Query("SELECT * FROM mcp_tools ORDER BY qualifiedName ASC")
    suspend fun getAllTools(): List<McpToolEntity>

    @Query("SELECT * FROM mcp_tools WHERE serverId = :serverId ORDER BY toolName ASC")
    suspend fun getToolsByServer(serverId: String): List<McpToolEntity>

    @Upsert
    suspend fun upsertTools(tools: List<McpToolEntity>)

    @Query("DELETE FROM mcp_tools WHERE serverId = :serverId")
    suspend fun deleteToolsByServer(serverId: String)

    @Query("DELETE FROM mcp_tools")
    suspend fun deleteAllTools()
}
