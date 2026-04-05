package com.neilturner.aerialviews.ui.overlays

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.neilturner.aerialviews.models.enums.OverlayType
import com.neilturner.aerialviews.services.CalendarEvent
import com.neilturner.aerialviews.ui.overlays.state.CalendarOverlayState
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.min

class CalendarOverlay
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : View(context, attrs, defStyleAttr) {
        var type = OverlayType.CALENDAR
        private var events: List<CalendarEvent> = emptyList()
        var isFullSlide: Boolean = false

        // Paint objects
        private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dayNamePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dayNumberPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val todayCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val eventDotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val eventDotFuturePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val upcomingTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val upcomingEventPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val upcomingTimePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val upcomingEventBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val highlightedDayBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val monthNavPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Colors - beautiful semi-transparent dark UI
        private val colorBg = Color.argb(225, 10, 15, 30) // Darker for full screen
        private val colorWhite = Color.argb(255, 255, 255, 255)
        private val colorWhite70 = Color.argb(178, 255, 255, 255)
        private val colorWhite40 = Color.argb(102, 255, 255, 255)
        private val colorWhite10 = Color.argb(25, 255, 255, 255)
        private val colorAccentBlue = Color.argb(255, 74, 144, 226)
        private val colorAccentPurple = Color.argb(255, 155, 89, 182)
        private val colorToday = Color.argb(255, 74, 144, 226)
        private val colorWeekend = Color.argb(255, 252, 129, 74)
        private val colorDivider = Color.argb(45, 255, 255, 255)
        private val colorEventLabelBg = Color.argb(160, 40, 50, 80)

        private val cornerRadius = 28f
        private val eventDotRadius = 5f

        private val today = Calendar.getInstance()
        private val displayedMonth = Calendar.getInstance()

        init {
            alpha = 1f
        }

        fun render(state: CalendarOverlayState) {
            Timber.d("CalendarOverlay: render with ${state.events.size} events")
            events = state.events
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val desiredWidth = 1000 // roughly 1/3 of a 4K screen width
            val desiredHeight = 600

            val width = resolveSize(desiredWidth, widthMeasureSpec)
            val height = resolveSize(desiredHeight, heightMeasureSpec)
            setMeasuredDimension(width, height)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width == 0 || height == 0) return

            val w = width.toFloat()
            val h = height.toFloat()
            val padding = w * 0.03f
            
            if (isFullSlide) {
                // Full Screen Slide: Top Panel + Weekly Grid
                val topPanelHeight = h * 0.35f
                canvas.drawColor(Color.argb(140, 0, 0, 0))

                drawTopUpcomingEvents(
                    canvas,
                    left = padding,
                    top = padding,
                    right = w - padding,
                    bottom = topPanelHeight,
                )

                drawWeeklyGrid(
                    canvas,
                    left = padding,
                    top = topPanelHeight + padding * 0.5f,
                    right = w - padding,
                    bottom = h - padding,
                )
            } else {
                // Corner Overlay: Today's Events Only
                drawTodayOverlay(canvas, 0f, 0f, w, h)
            }
        }

        private fun drawTodayOverlay(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
            val w = right - left
            val h = bottom - top
            val panelRect = RectF(left, top, right, bottom)
            
            backgroundPaint.shader = null
            backgroundPaint.color = colorBg
            canvas.drawRoundRect(panelRect, cornerRadius, cornerRadius, backgroundPaint)

            val padding = w * 0.08f
            val now = System.currentTimeMillis()
            val startOfToday = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val endOfToday = startOfToday + 24 * 60 * 60 * 1000

            val todaysEvents = events.filter { 
                (it.startTime in startOfToday..endOfToday) || (it.isAllDay && it.startTime <= endOfToday && it.endTime >= startOfToday)
            }

            // "TODAY" Title
            upcomingTitlePaint.color = colorAccentBlue
            upcomingTitlePaint.textSize = h * 0.12f
            upcomingTitlePaint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            canvas.drawText("TODAY", left + padding, top + h * 0.18f, upcomingTitlePaint)

            if (todaysEvents.isEmpty()) {
                upcomingEventPaint.color = colorWhite40
                upcomingEventPaint.textSize = h * 0.1f
                canvas.drawText("No events today", left + padding, top + h * 0.45f, upcomingEventPaint)
                return
            }

            val itemPadding = h * 0.02f
            val headerAndBasePadding = h * 0.25f + padding
            val availableItemHeight = h - headerAndBasePadding
            
            var dynamicTextSize = h * 0.11f
            var itemHeight = dynamicTextSize * 2.0f
            
            if (todaysEvents.isNotEmpty()) {
                val neededHeight = todaysEvents.size * itemHeight
                if (neededHeight > availableItemHeight) {
                    itemHeight = availableItemHeight / todaysEvents.size
                    dynamicTextSize = (itemHeight * 0.5f).coerceAtLeast(h * 0.04f)
                }
            }

            todaysEvents.forEachIndexed { i, event ->
                val itemTop = top + h * 0.25f + i * itemHeight
                val color = getEventColor(event)
                
                headerPaint.color = color
                canvas.drawRect(left + padding, itemTop + 5f, left + padding + 6f, itemTop + itemHeight - 5f, headerPaint)

                upcomingEventPaint.textSize = dynamicTextSize
                upcomingEventPaint.color = colorWhite
                canvas.drawText(truncateText(event.title, upcomingEventPaint, w - padding * 2.5f), 
                    left + padding + 15f, itemTop + itemHeight * 0.6f, upcomingEventPaint)
            }
        }

        private fun drawTopUpcomingEvents(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
            val h = bottom - top
            val w = right - left
            val panelRect = RectF(left, top, right, bottom)
            
            // Draw background glass panel
            backgroundPaint.shader = null
            backgroundPaint.color = colorBg
            canvas.drawRoundRect(panelRect, cornerRadius, cornerRadius, backgroundPaint)

            val titleSize = h * 0.15f
            val padding = w * 0.02f
            
            upcomingTitlePaint.color = colorAccentBlue
            upcomingTitlePaint.textSize = titleSize
            upcomingTitlePaint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            upcomingTitlePaint.textAlign = Paint.Align.LEFT
            canvas.drawText("TODAY & UPCOMING", left + padding, top + titleSize * 1.5f, upcomingTitlePaint)

            // Prominent upcoming cards
            val now = System.currentTimeMillis()
            val upcoming = events
                .filter { (it.endTime > now || it.isAllDay) && it.startTime < now + 7L * 24 * 60 * 60 * 1000 }
                .take(3) 

            if (upcoming.isEmpty()) {
                upcomingEventPaint.color = colorWhite40
                upcomingEventPaint.textSize = h * 0.15f
                upcomingEventPaint.textAlign = Paint.Align.CENTER
                canvas.drawText("No upcoming events this week", left + w / 2, top + h / 2, upcomingEventPaint)
                return
            }

            val cardW = (w - padding * 4) / 3f
            val cardTop = top + titleSize * 2.2f
            val cardBottom = bottom - padding

            upcoming.forEachIndexed { i, event ->
                val cardL = left + padding + i * (cardW + padding)
                val cardR = cardL + cardW
                val cardRect = RectF(cardL, cardTop, cardR, cardBottom)
                
                upcomingEventBgPaint.shader = null
                upcomingEventBgPaint.color = colorWhite10
                canvas.drawRoundRect(cardRect, cornerRadius * 0.5f, cornerRadius * 0.5f, upcomingEventBgPaint)

                val accentColor = getEventColor(event)
                upcomingEventBgPaint.color = accentColor
                canvas.drawRoundRect(RectF(cardL, cardTop, cardL + 8f, cardBottom), 4f, 4f, upcomingEventBgPaint)

                val contentPadding = cardW * 0.08f
                val fontSize = h * 0.11f
                upcomingEventPaint.textSize = fontSize
                upcomingEventPaint.color = colorWhite
                upcomingEventPaint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
                
                val titleX = cardL + contentPadding + 10f
                val titleY = cardTop + contentPadding + fontSize
                
                val title = truncateText(event.title, upcomingEventPaint, cardW - contentPadding * 2 - 20f)
                canvas.drawText(title, titleX, titleY, upcomingEventPaint)

                upcomingTimePaint.textSize = h * 0.09f
                upcomingTimePaint.color = colorWhite70
                val timeStr = formatEventTime(event)
                canvas.drawText(timeStr, titleX, titleY + h * 0.14f, upcomingTimePaint)
                
                if (event.location.isNotEmpty()) {
                    canvas.drawText(truncateText(event.location, upcomingTimePaint, cardW - contentPadding * 2 - 20f), 
                        titleX, titleY + h * 0.26f, upcomingTimePaint)
                }
            }
        }

        private fun drawWeeklyGrid(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
            val w = right - left
            val h = bottom - top
            val panelRect = RectF(left, top, right, bottom)
            
            backgroundPaint.color = colorBg
            canvas.drawRoundRect(panelRect, cornerRadius, cornerRadius, backgroundPaint)

            val columnW = w / 7f
            val dayNameSize = h * 0.06f
            val dayNumSize = h * 0.1f
            val eventTextSize = h * 0.05f

            dayNamePaint.textSize = dayNameSize
            dayNamePaint.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            dayNamePaint.textAlign = Paint.Align.CENTER

            dayNumberPaint.textSize = dayNumSize
            dayNumberPaint.typeface = Typeface.create("sans-serif", Typeface.BOLD)

            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            val startTime = cal.timeInMillis

            // 1. Find the day with the most events to determine scaling
            var maxEventsOnAnyDay = 0
            val tempCal = Calendar.getInstance().apply { timeInMillis = startTime }
            for (i in 0 until 7) {
                val ds = tempCal.timeInMillis
                val de = ds + 24 * 60 * 60 * 1000 - 1
                val count = events.count { (it.startTime in ds..de) || (it.isAllDay && it.startTime <= de && it.endTime >= ds) }
                if (count > maxEventsOnAnyDay) maxEventsOnAnyDay = count
                tempCal.add(Calendar.DAY_OF_YEAR, 1)
            }

            // 2. Adjust text size if necessary (base is h * 0.05, vertical space is h * 0.6)
            val availableHeight = h * 0.6f
            var dynamicEventTextSize = eventTextSize
            val minTextSize = h * 0.025f // Minimum readable size
            
            if (maxEventsOnAnyDay > 0) {
                val neededHeight = maxEventsOnAnyDay * (dynamicEventTextSize * 2.2f)
                if (neededHeight > availableHeight) {
                    dynamicEventTextSize = (availableHeight / (maxEventsOnAnyDay * 2.2f)).coerceAtLeast(minTextSize)
                }
            }

            for (i in 0 until 7) {
                val cx = left + i * columnW + columnW / 2
                val isToday = i == 0
                val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
                val isWeekend = dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY

                if (i > 0) {
                    canvas.drawLine(left + i * columnW, top + h * 0.1f, left + i * columnW, bottom - h * 0.1f, dividerPaint)
                }

                dayNamePaint.color = when {
                    isToday -> colorAccentBlue
                    isWeekend -> colorWeekend
                    else -> colorWhite70
                }
                val dayName = SimpleDateFormat("EEE", Locale.getDefault()).format(cal.time).uppercase()
                canvas.drawText(dayName, cx, top + h * 0.12f, dayNamePaint)

                dayNumberPaint.color = if (isToday) colorWhite else colorWhite40
                val dayNum = cal.get(Calendar.DAY_OF_MONTH).toString()
                canvas.drawText(dayNum, cx, top + h * 0.25f, dayNumberPaint)

                drawEventsForDayInGrid(canvas, cal, cx, top + h * 0.35f, columnW, bottom - h * 0.05f, dynamicEventTextSize)

                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        private fun drawEventsForDayInGrid(canvas: Canvas, day: Calendar, cx: Float, top: Float, width: Float, bottom: Float, textSize: Float) {
            val dayStart = day.timeInMillis
            val dayEnd = dayStart + 24 * 60 * 60 * 1000 - 1
            
            val dayEvents = events.filter { 
                (it.startTime in dayStart..dayEnd) || 
                (it.isAllDay && it.startTime <= dayEnd && it.endTime >= dayStart)
            }.sortedBy { !it.isAllDay }

            if (dayEvents.isEmpty()) return

            val itemH = textSize * 2.2f
            val maxItems = ((bottom - top) / itemH).toInt()
            
            dayEvents.forEachIndexed { index, event ->
                val itemTop = top + index * itemH
                val itemRect = RectF(cx - width / 2 + 10f, itemTop, cx + width / 2 - 10f, itemTop + itemH * 0.85f)
                
                headerPaint.shader = null
                headerPaint.color = colorEventLabelBg
                canvas.drawRoundRect(itemRect, 8f, 8f, headerPaint)
                
                val accentColor = getEventColor(event)
                headerPaint.color = accentColor
                canvas.drawRect(itemRect.left, itemRect.top, itemRect.left + 6f, itemRect.bottom, headerPaint)
                
                upcomingEventPaint.textSize = textSize
                upcomingEventPaint.color = colorWhite
                upcomingEventPaint.typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
                upcomingEventPaint.textAlign = Paint.Align.LEFT
                
                val text = truncateText(event.title, upcomingEventPaint, itemRect.width() - 15f)
                canvas.drawText(text, itemRect.left + 15f, itemRect.centerY() + textSize * 0.35f, upcomingEventPaint)
            }
        }

        private fun getEventColor(event: CalendarEvent): Int {
            if (event.calendarColor != 0) {
                return Color.argb(
                    255,
                    (Color.red(event.calendarColor) * 0.85f + 40).toInt().coerceIn(0, 255),
                    (Color.green(event.calendarColor) * 0.85f + 40).toInt().coerceIn(0, 255),
                    (Color.blue(event.calendarColor) * 0.85f + 40).toInt().coerceIn(0, 255),
                )
            }
            return colorAccentBlue
        }

        private fun formatEventTime(event: CalendarEvent): String {
            if (event.isAllDay) return "All day"
            val sdf = SimpleDateFormat("EEE, MMM d • h:mm a", Locale.getDefault())
            return sdf.format(Date(event.startTime))
        }

        private fun truncateText(text: String, paint: Paint, maxWidth: Float): String {
            if (paint.measureText(text) <= maxWidth) return text
            val ellipsis = "…"
            var truncated = text
            while (truncated.isNotEmpty() && paint.measureText(truncated + ellipsis) > maxWidth) {
                truncated = truncated.dropLast(1)
            }
            return truncated + ellipsis
        }
    }
