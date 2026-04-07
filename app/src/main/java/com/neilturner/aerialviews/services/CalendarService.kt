package com.neilturner.aerialviews.services

import android.content.Context
import android.graphics.Color
import android.provider.CalendarContract
import android.speech.tts.TextToSpeech
import com.neilturner.aerialviews.models.prefs.GeneralPrefs
import com.neilturner.aerialviews.utils.PermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.kosert.flowbus.GlobalBus
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class CalendarService(private val context: Context) : TextToSpeech.OnInitListener {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var updateJob: Job? = null
    private var announcementJob: Job? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    
    private var cachedEvents: List<CalendarEvent> = emptyList()
    private val announcedEventIds = mutableSetOf<String>()

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            tts?.language = Locale.getDefault()
            Timber.i("CalendarService: TTS initialized successfully")
        } else {
            Timber.e("CalendarService: TTS initialization failed")
        }
    }

    fun startUpdates() {
        updateJob?.cancel()
        updateJob = scope.launch {
            while (true) {
                fetchAndPostEvents()
                delay(UPDATE_INTERVAL_MS)
            }
        }
        
        startAnnouncementWatcher()
    }

    private fun startAnnouncementWatcher() {
        announcementJob?.cancel()
        announcementJob = scope.launch {
            while (true) {
                if (GeneralPrefs.calendarAnnouncementsEnabled) {
                    checkAnnouncements()
                }
                delay(60 * 1000L) // Check every minute
            }
        }
    }

    fun stop() {
        scope.cancel()
        tts?.stop()
        tts?.shutdown()
    }

    fun refreshNow() {
        Timber.i("CalendarService: force-refreshing events...")
        scope.launch { fetchAndPostEvents() }
    }

    private fun fetchAndPostEvents() {
        val events = if (GeneralPrefs.calendarSource == "ICAL") {
            val rawUrls = if (GeneralPrefs.calendarIcalUrls.isNotEmpty()) GeneralPrefs.calendarIcalUrls else GeneralPrefs.calendarIcalUrl
            val urls = rawUrls.split(";").filter { it.isNotBlank() }
            val hexColors = GeneralPrefs.calendarIcalColors.split(";").filter { it.isNotBlank() }
            
            val allEvents = mutableListOf<CalendarEvent>()
            urls.forEachIndexed { i, url ->
                val colorStr = hexColors.getOrNull(i)
                val colorInt = parseColorSafely(colorStr)
                allEvents.addAll(fetchIcalEvents(url, colorInt))
            }
            allEvents.sortedBy { it.startTime }
        } else {
            if (!hasCalendarPermission()) {
                Timber.w("CalendarService: READ_CALENDAR permission not granted")
                emptyList()
            } else {
                queryUpcomingEvents()
            }
        }
        Timber.i("CalendarService: fetched ${events.size} total events")
        cachedEvents = events
        GlobalBus.post(CalendarDataEvent(events))
    }

    private fun checkAnnouncements() {
        if (!ttsReady) return
        
        val now = System.currentTimeMillis()
        val minutesBefore = GeneralPrefs.calendarAnnouncementMinutes
        val targetTime = now + minutesBefore * 60 * 1000L
        
        // Find events starting in the target window
        // This ensures the 1-minute loop catches it
        val upcoming = cachedEvents.filter { 
            !it.isAllDay && 
            it.startTime > now && 
            it.startTime <= targetTime + 60 * 1000L && // started within 1 minute of target
            it.startTime >= targetTime - 60 * 1000L
        }

        upcoming.forEach { event ->
            val eventKey = "announce_${event.startTime}_${event.title}"
            if (!announcedEventIds.contains(eventKey)) {
                announceEvent(event)
                announcedEventIds.add(eventKey)
            }
        }
        
        // Clean up old IDs once a day or periodically
        if (announcedEventIds.size > 100) {
            val recentlyannounced = announcedEventIds.filter { it.substringAfter("announce_").substringBefore("_").toLongOrNull() ?: 0L > now - 24 * 60 * 60 * 1000L }
            announcedEventIds.clear()
            announcedEventIds.addAll(recentlyannounced)
        }
    }

    private fun announceEvent(event: CalendarEvent) {
        val minutesBefore = GeneralPrefs.calendarAnnouncementMinutes
        val text = "Next event, ${event.title}, starts in $minutesBefore minutes"
        Timber.i("CalendarService: Announcing: $text")
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "announcement_${event.startTime}")
    }

    private fun parseColorSafely(hex: String?): Int {
        if (hex == null) return 0
        return try {
            val h = if (!hex.startsWith("#")) "#$hex" else hex
            Color.parseColor(h)
        } catch (e: Exception) {
            0
        }
    }

    private fun fetchIcalEvents(url: String, color: Int): List<CalendarEvent> {
        return try {
            val client = OkHttpClient()
            val request = Request.Builder()
                .url(url.trim())
                .addHeader("Cache-Control", "no-cache")
                .addHeader("Pragma", "no-cache")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.e("CalendarService: failed to fetch iCal ($url): ${response.code}")
                    return emptyList()
                }
                val body = response.body.string()
                parseIcal(body, color)
            }
        } catch (e: Exception) {
            Timber.e(e, "CalendarService: error fetching iCal events from $url")
            emptyList()
        }
    }

    private fun parseIcal(data: String, calendarColor: Int): List<CalendarEvent> {
        val events = mutableListOf<CalendarEvent>()
        val lines = data.lines().map { it.trim() }
        var currentEvent: CalendarEvent? = null
        var inEvent = false

        val now = System.currentTimeMillis()
        val limit = now + 60L * 24 * 60 * 60 * 1000 // 60 days

        for (line in lines) {
            when {
                line.startsWith("BEGIN:VEVENT") -> {
                    inEvent = true
                    currentEvent = CalendarEvent(0, "", 0, 0, false, calendarColor, "")
                }
                line.startsWith("END:VEVENT") -> {
                    inEvent = false
                    currentEvent?.let {
                        if (it.endTime > now && it.startTime < limit) {
                            events.add(it)
                        }
                    }
                    currentEvent = null
                }
                inEvent -> {
                    val colonPos = line.indexOf(':')
                    if (colonPos == -1) continue
                    val key = line.substring(0, colonPos)
                    val value = line.substring(colonPos + 1)

                    when {
                        key.startsWith("SUMMARY") -> currentEvent = currentEvent?.copy(title = value)
                        key.startsWith("LOCATION") -> currentEvent = currentEvent?.copy(location = value)
                        key.startsWith("DTSTART") -> {
                            val time = parseIcalTime(value)
                            currentEvent = currentEvent?.copy(startTime = time, isAllDay = !value.contains('T'))
                        }
                        key.startsWith("DTEND") -> {
                            val time = parseIcalTime(value)
                            currentEvent = currentEvent?.copy(endTime = time)
                        }
                    }
                }
            }
        }
        return events
    }

    private fun parseIcalTime(value: String): Long {
        return try {
            val cleanValue = value.substringAfterLast(':').substringAfterLast('=')
            val formatStr = if (cleanValue.contains('T')) {
                if (cleanValue.endsWith('Z')) "yyyyMMdd'T'HHmmss'Z'" else "yyyyMMdd'T'HHmmss"
            } else {
                "yyyyMMdd"
            }
            val sdf = SimpleDateFormat(formatStr, Locale.US)
            if (cleanValue.endsWith('Z')) sdf.timeZone = TimeZone.getTimeZone("UTC")
            sdf.parse(cleanValue)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun hasCalendarPermission(): Boolean = PermissionHelper.hasCalendarPermission(context)

    private fun queryUpcomingEvents(): List<CalendarEvent> {
        val events = mutableListOf<CalendarEvent>()

        // Query from start of today to 60 days ahead
        val now = Calendar.getInstance()
        val startOfToday = now.clone() as Calendar
        startOfToday.set(Calendar.HOUR_OF_DAY, 0)
        startOfToday.set(Calendar.MINUTE, 0)
        startOfToday.set(Calendar.SECOND, 0)
        startOfToday.set(Calendar.MILLISECOND, 0)

        val endWindow = now.clone() as Calendar
        endWindow.add(Calendar.DAY_OF_YEAR, 60)

        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.CALENDAR_COLOR,
            CalendarContract.Events.EVENT_LOCATION,
        )

        val selection = "(${CalendarContract.Events.DTSTART} >= ?) AND " +
            "(${CalendarContract.Events.DTEND} <= ?) AND " +
            "(${CalendarContract.Events.DELETED} != 1)"

        val selectionArgs = arrayOf(
            startOfToday.timeInMillis.toString(),
            endWindow.timeInMillis.toString(),
        )

        val sortOrder = "${CalendarContract.Events.DTSTART} ASC"

        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder,
        )?.use { cursor ->
            while (cursor.moveToNext() && events.size < MAX_EVENTS) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.Events._ID))
                val title = cursor.getString(cursor.getColumnIndexOrThrow(CalendarContract.Events.TITLE)) ?: ""
                val dtStart = cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.Events.DTSTART))
                val dtEnd = cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.Events.DTEND))
                val allDay = cursor.getInt(cursor.getColumnIndexOrThrow(CalendarContract.Events.ALL_DAY)) == 1
                val color = cursor.getInt(cursor.getColumnIndexOrThrow(CalendarContract.Events.CALENDAR_COLOR))
                val location = cursor.getString(cursor.getColumnIndexOrThrow(CalendarContract.Events.EVENT_LOCATION)) ?: ""

                if (title.isNotBlank()) {
                    events.add(
                        CalendarEvent(
                            id = id,
                            title = title,
                            startTime = dtStart,
                            endTime = dtEnd,
                            isAllDay = allDay,
                            calendarColor = color,
                            location = location,
                        ),
                    )
                }
            }
        }

        return events
    }

    companion object {
        private const val UPDATE_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes
        private const val MAX_EVENTS = 100
    }
}

/** Bus event carrying the list of upcoming calendar events. */
data class CalendarDataEvent(val events: List<CalendarEvent>)
