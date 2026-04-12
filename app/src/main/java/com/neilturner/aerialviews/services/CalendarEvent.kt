package com.neilturner.aerialviews.services

data class CalendarEvent(
    val id: Long = 0,
    val title: String = "",
    val startTime: Long = 0L,
    val endTime: Long = 0L,
    val isAllDay: Boolean = false,
    val calendarColor: Int = 0,
    val location: String = "",
    val rrule: String? = null,
)
