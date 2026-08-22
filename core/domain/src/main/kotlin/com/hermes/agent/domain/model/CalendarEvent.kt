package com.hermes.agent.domain.model

data class CalendarEvent(
    val id: String,
    val title: String,
    val description: String = "",
    val sourceCalendar: String = "default",
    val startMs: Long = 0L,
    val endMs: Long = 0L,
    val allDay: Boolean = false,
    val location: String? = null,
    val reminderMinutes: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)
