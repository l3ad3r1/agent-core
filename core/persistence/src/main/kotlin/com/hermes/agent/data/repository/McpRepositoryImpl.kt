package com.hermes.agent.data.repository

import com.hermes.agent.data.local.dao.McpDao
import com.hermes.agent.data.local.entity.McpServerEntity
import com.hermes.agent.data.local.entity.McpToolEntity
import com.hermes.agent.domain.mcp.McpRepository
import com.hermes.agent.domain.mcp.McpServerConfig
import com.hermes.agent.domain.mcp.McpToolDefinition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class McpRepositoryImpl @Inject constructor(
    private val mcpDao: McpDao,
) : McpRepository {

    override fun getServers(): Flow<List<McpServerConfig>> =
        mcpDao.observeServers().map { list -> list.map { it.toDomain() } }

    override suspend fun getAllServers(): List<McpServerConfig> =
        mcpDao.getAllServers().map { it.toDomain() }

    override suspend fun getServer(id: String): McpServerConfig? =
        mcpDao.getServerById(id)?.toDomain()

    override suspend fun saveServer(server: McpServerConfig) {
        mcpDao.upsertServer(McpServerEntity.fromDomain(server))
    }

    override suspend fun deleteServer(id: String) {
        mcpDao.deleteServer(id)
        mcpDao.deleteToolsByServer(id)
    }

    override suspend fun updateServerError(id: String, lastError: String?) {
        mcpDao.updateServerError(id, lastError)
    }

    override suspend fun setServerEnabled(id: String, enabled: Boolean) {
        mcpDao.setServerEnabled(id, enabled)
    }

    override fun getCachedTools(serverId: String): Flow<List<McpToolDefinition>> =
        mcpDao.observeToolsByServer(serverId).map { list -> list.map { it.toDomain() } }

    override suspend fun getAllCachedTools(): List<McpToolDefinition> =
        mcpDao.getAllTools().map { it.toDomain() }

    override suspend fun saveCachedTools(serverId: String, tools: List<McpToolDefinition>) {
        mcpDao.deleteToolsByServer(serverId)
        if (tools.isNotEmpty()) {
            mcpDao.upsertTools(tools.map { McpToolEntity.fromDomain(it) })
        }
    }

    override suspend fun clearCachedTools(serverId: String) {
        mcpDao.deleteToolsByServer(serverId)
    }
}
