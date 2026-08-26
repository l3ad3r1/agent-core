package com.hermes.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.hermes.agent.data.local.entity.MoodEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MoodEntryDao {
    @Query("SELECT * FROM mood_entries ORDER BY dateMs DESC")
    fun observeAll(): Flow<List<MoodEntryEntity>>

    @Query("SELECT * FROM mood_entries WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MoodEntryEntity?

    @Upsert
    suspend fun upsert(entity: MoodEntryEntity)

    @Query("DELETE FROM mood_entries WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM mood_entries WHERE dateMs >= :startMs AND dateMs < :endMs ORDER BY dateMs DESC")
    suspend fun getInRange(startMs: Long, endMs: Long): List<MoodEntryEntity>

    @Query("SELECT * FROM mood_entries WHERE dateMs = :dateMs LIMIT 1")
    suspend fun getForDate(dateMs: Long): MoodEntryEntity?
}
