package com.hermes.agent.domain.repository

import com.hermes.agent.domain.model.CalendarEvent
import kotlinx.coroutines.flow.Flow

interface CalendarRepository {
    fun observeAll(): Flow<List<CalendarEvent>>
    suspend fun get(id: String): CalendarEvent?
    suspend fun create(title: String, description: String, startMs: Long, endMs: Long, allDay: Boolean = false, sourceCalendar: String = "default", location: String? = null, reminderMinutes: Int = 0): CalendarEvent
    suspend fun update(id: String, title: String? = null, description: String? = null, startMs: Long? = null, endMs: Long? = null, allDay: Boolean? = null, location: String? = null)
    suspend fun delete(id: String)
    suspend fun getUpcoming(limit: Int = 10): List<CalendarEvent>
    suspend fun getByDateRange(startMs: Long, endMs: Long): List<CalendarEvent>
}
