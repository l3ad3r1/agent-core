package com.hermes.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.hermes.agent.data.local.entity.CalendarEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CalendarEventDao {
    @Query("SELECT * FROM calendar_events ORDER BY startMs DESC")
    fun observeAll(): Flow<List<CalendarEventEntity>>

    @Query("SELECT * FROM calendar_events WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CalendarEventEntity?

    @Upsert
    suspend fun upsert(entity: CalendarEventEntity)

    @Query("DELETE FROM calendar_events WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM calendar_events WHERE startMs > :now ORDER BY startMs ASC LIMIT :limit")
    suspend fun getUpcoming(now: Long = System.currentTimeMillis(), limit: Int = 10): List<CalendarEventEntity>

    @Query("SELECT * FROM calendar_events WHERE startMs >= :startMs AND startMs < :endMs ORDER BY startMs ASC")
    suspend fun getByDateRange(startMs: Long, endMs: Long): List<CalendarEventEntity>

    @Query(
        """
        UPDATE calendar_events
        SET title = COALESCE(:title, title),
            description = COALESCE(:description, description),
            startMs = COALESCE(:startMs, startMs),
            endMs = COALESCE(:endMs, endMs),
            allDay = COALESCE(:allDay, allDay),
            location = COALESCE(:location, location)
        WHERE id = :id
        """,
    )
    suspend fun update(id: String, title: String?, description: String?, startMs: Long?, endMs: Long?, allDay: Boolean?, location: String?)
}
