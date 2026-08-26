package com.hermes.agent.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "calendar_events",
    // Declared here, not only in the migration: Room validates an upgraded
    // database against the entities, so an index that exists in one and not the
    // other fails the upgrade with "Migration didn't properly handle".
    indices = [Index(value = ["startMs"])],
)
data class CalendarEventEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String,
    val sourceCalendar: String,
    val startMs: Long,
    val endMs: Long,
    val allDay: Boolean,
    val location: String?,
    val reminderMinutes: Int,
    val createdAt: Long,
) {
    fun toDomain() = com.hermes.agent.domain.model.CalendarEvent(
        id = id,
        title = title,
        description = description,
        sourceCalendar = sourceCalendar,
        startMs = startMs,
        endMs = endMs,
        allDay = allDay,
        location = location,
        reminderMinutes = reminderMinutes,
        createdAt = createdAt,
    )

    companion object {
        fun fromDomain(event: com.hermes.agent.domain.model.CalendarEvent) = CalendarEventEntity(
            id = event.id,
            title = event.title,
            description = event.description,
            sourceCalendar = event.sourceCalendar,
            startMs = event.startMs,
            endMs = event.endMs,
            allDay = event.allDay,
            location = event.location,
            reminderMinutes = event.reminderMinutes,
            createdAt = event.createdAt,
        )
    }
}
