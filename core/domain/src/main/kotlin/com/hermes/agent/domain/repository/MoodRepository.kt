package com.hermes.agent.domain.repository

import com.hermes.agent.domain.model.MoodEntry
import com.hermes.agent.domain.model.MoodLevel
import kotlinx.coroutines.flow.Flow

interface MoodRepository {
    fun observeAll(): Flow<List<MoodEntry>>
    suspend fun get(id: String): MoodEntry?
    suspend fun create(dateMs: Long, mood: MoodLevel = MoodLevel.MID, intensity: Int = 5, note: String = "", tags: List<String> = emptyList()): MoodEntry
    suspend fun update(id: String, mood: MoodLevel? = null, intensity: Int? = null, note: String? = null, tags: List<String>? = null)
    suspend fun delete(id: String)
    suspend fun getForDate(dateMs: Long): MoodEntry?
    suspend fun getWeeklyStats(days: Int = 7): Map<MoodLevel, Int>
    suspend fun getTrend(days: Int = 30): List<MoodEntry>
    suspend fun getMostFrequentMood(days: Int = 30): MoodLevel?
}
