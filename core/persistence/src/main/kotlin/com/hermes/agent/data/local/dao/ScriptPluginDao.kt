package com.hermes.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.hermes.agent.data.local.entity.ScriptPluginEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScriptPluginDao {
    @Query("SELECT * FROM script_plugins ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<ScriptPluginEntity>>

    @Query("SELECT * FROM script_plugins ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAll(): List<ScriptPluginEntity>

    /** The load set: only enabled modules are handed to the sandbox. */
    @Query("SELECT * FROM script_plugins WHERE enabled = 1 ORDER BY name COLLATE NOCASE ASC")
    suspend fun getEnabled(): List<ScriptPluginEntity>

    @Query("SELECT * FROM script_plugins WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ScriptPluginEntity?

    @Upsert
    suspend fun upsert(entity: ScriptPluginEntity)

    @Query("UPDATE script_plugins SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("DELETE FROM script_plugins WHERE id = :id")
    suspend fun delete(id: String)
}
