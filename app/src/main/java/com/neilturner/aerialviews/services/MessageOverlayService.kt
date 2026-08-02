package com.neilturner.aerialviews.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.neilturner.aerialviews.R
import com.neilturner.aerialviews.models.enums.OverlayType
import com.neilturner.aerialviews.models.prefs.GeneralPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.kosert.flowbus.EventsReceiver
import me.kosert.flowbus.subscribe
import timber.log.Timber

/**
 * Foreground service that keeps the Ktor HTTP server alive and displays
 * incoming messages as a system-level overlay window, even when the
 * screensaver (DreamService / TestActivity) is not running.
 */
class MessageOverlayService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ktorServer: KtorServer? = null
    private val receiver = EventsReceiver()

    private var windowManager: WindowManager? = null
    private var overlayTextView: TextView? = null

    // Per-slot text storage (MESSAGE1..4)
    private val slotTexts = mutableMapOf<OverlayType, String>()

    // Auto-clear job per slot
    private val clearJobs = mutableMapOf<OverlayType, Job>()

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Timber.i("MessageOverlayService: onCreate")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        setupOverlayWindow()
        startServer()
        subscribeToMessages()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.i("MessageOverlayService: onDestroy")
        receiver.unsubscribe()
        clearJobs.values.forEach { it.cancel() }
        clearJobs.clear()
        removeOverlayWindow()
        ktorServer?.stop()
        serviceScope.cancel()
    }

    // -------------------------------------------------------------------------
    // Ktor server
    // -------------------------------------------------------------------------

    private fun startServer() {
        ktorServer = KtorServer(applicationContext) { event ->
            Timber.i("MessageOverlayService: Posting MessageEvent to GlobalBus: ${event.text}")
            // Forward to GlobalBus. This service AND the normal screensaver will receive it.
            me.kosert.flowbus.GlobalBus.post(event)
            
            // If it's a MESSAGE1 or MESSAGE2, also broadcast it as TICKER 
            // so it shows up in the "original messages" area at the bottom during the screensaver.
            if (event.type == OverlayType.MESSAGE1 || event.type == OverlayType.MESSAGE2) {
                me.kosert.flowbus.GlobalBus.post(event.copy(type = OverlayType.TICKER, isTicker = true))
            }
        }.apply { start() }
    }

    // -------------------------------------------------------------------------
    // FlowBus subscription (for events emitted by the screensaver's KtorServer)
    // -------------------------------------------------------------------------

    private fun subscribeToMessages() {
        receiver.subscribe<MessageEvent> { event ->
            // Do not handle FlowBus messages if they were already handled by our own KtorServer
            // Actually, we do not need to do anything here because our KtorServer handles it directly.
            // But we keep this in case another part of the app broadcasts a MessageEvent.
            serviceScope.launch { handleMessageEvent(event) }
        }
        receiver.subscribe<ScreensaverStateEvent> { event ->
            serviceScope.launch { refreshDisplay() }
        }
    }

    // -------------------------------------------------------------------------
    // Message handling
    // -------------------------------------------------------------------------

    private fun handleMessageEvent(event: MessageEvent) {
        val type = event.type
        val rawText = event.text
        
        // Only show MESSAGE1 and MESSAGE2 in the system overlay
        if (type != OverlayType.MESSAGE1 && type != OverlayType.MESSAGE2) {
            return
        }

        // #AllClear# — remove this slot
        if (rawText.contains("#AllClear#", ignoreCase = true)) {
            slotTexts.remove(type)
            clearJobs[type]?.cancel()
            clearJobs.remove(type)
            refreshDisplay()
            return
        }

        val processedText = rawText.replace("#Announce#", "", ignoreCase = true).trim()

        if (processedText.isEmpty()) {
            slotTexts.remove(type)
            clearJobs[type]?.cancel()
            clearJobs.remove(type)
        } else {
            // Always overwrite the slot text, never append for the system overlay
            slotTexts[type] = processedText

            // Schedule auto-clear (default to system screensaver timeout if no duration provided)
            clearJobs[type]?.cancel()
            
            val systemTimeoutMs = try {
                android.provider.Settings.System.getInt(
                    contentResolver,
                    android.provider.Settings.System.SCREEN_OFF_TIMEOUT,
                    300000
                ).toLong()
            } catch (e: Exception) {
                300000L
            }

            val delayMs = if (event.duration != null && event.duration > 0) {
                event.duration * 1000L
            } else {
                systemTimeoutMs
            }

            clearJobs[type] = serviceScope.launch {
                delay(delayMs)
                slotTexts.remove(type)
                clearJobs.remove(type)
                refreshDisplay()
            }
        }

        // Apply per-message text size to overlay view
        event.textSize?.let { size ->
            overlayTextView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, size.toFloat())
        }

        refreshDisplay()
    }

    // -------------------------------------------------------------------------
    // Overlay window
    // -------------------------------------------------------------------------

    private fun setupOverlayWindow() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Timber.w("MessageOverlayService: SYSTEM_ALERT_WINDOW permission not granted — overlay window disabled")
            return
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setShadowLayer(4f, 0f, 2f, Color.BLACK)
            
            // Nice translucent rounded background with an accent border for eye-catching
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#E6111111")) // 90% very dark gray
                setStroke(6, Color.parseColor("#FFC107")) // Amber/Gold border
                cornerRadius = 24f
            }
            
            setPadding(64, 32, 64, 32)
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        overlayTextView = tv

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 64
            y = 64
        }

        try {
            windowManager?.addView(tv, params)
            tv.visibility = android.view.View.GONE
            Timber.i("MessageOverlayService: Overlay window added")
        } catch (e: Exception) {
            Timber.e(e, "MessageOverlayService: Failed to add overlay window")
            overlayTextView = null
        }
    }

    private fun removeOverlayWindow() {
        try {
            overlayTextView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            Timber.e(e, "MessageOverlayService: Failed to remove overlay window")
        }
        overlayTextView = null
        windowManager = null
    }

    private fun refreshDisplay() {
        val combined = slotTexts.values.filter { it.isNotBlank() }.joinToString("   •   ")
        val tv = overlayTextView ?: return

        // Do not show the system overlay if the screensaver is running.
        // The normal screensaver UI will handle the message instead.
        if (com.neilturner.aerialviews.ui.core.ScreenController.isScreensaverActive) {
            Timber.i("MessageOverlayService: Screensaver is active, hiding system overlay.")
            tv.visibility = android.view.View.GONE
            return
        }

        if (combined.isBlank()) {
            tv.visibility = android.view.View.GONE
            tv.text = ""
        } else {
            val isEntering = tv.visibility == android.view.View.GONE
            tv.text = combined
            
            if (isEntering) {
                tv.alpha = 0f
                tv.scaleX = 0.8f
                tv.scaleY = 0.8f
                tv.visibility = android.view.View.VISIBLE
                
                tv.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(400)
                    .setInterpolator(android.view.animation.OvershootInterpolator())
                    .start()
            } else {
                // Subtle pulse on update
                tv.animate()
                    .scaleX(1.05f)
                    .scaleY(1.05f)
                    .setDuration(150)
                    .withEndAction {
                        tv.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(150)
                            .start()
                    }
                    .start()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channelId = getString(R.string.message_overlay_channel_id)
        val channelName = getString(R.string.message_overlay_channel_name)
        val channel = NotificationChannel(
            channelId,
            channelName,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps the Message API overlay service running"
            setShowBadge(false)
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, getString(R.string.message_overlay_channel_id))
            .setContentTitle(getString(R.string.message_overlay_service_notification_title))
            .setContentText(
                getString(
                    R.string.message_overlay_service_notification_text,
                    GeneralPrefs.messageApiPort,
                ),
            )
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    companion object {
        private const val NOTIFICATION_ID = 9001

        /** Start the service if both API and always-on overlay settings are enabled */
        fun startIfEnabled(context: Context) {
            if (GeneralPrefs.messageApiEnabled && GeneralPrefs.messageApiAlwaysOnOverlay) {
                val intent = Intent(context, MessageOverlayService::class.java)
                context.startForegroundService(intent)
                Timber.i("MessageOverlayService: startForegroundService called")
            }
        }

        /** Stop the always-on overlay service */
        fun stop(context: Context) {
            val intent = Intent(context, MessageOverlayService::class.java)
            context.stopService(intent)
            Timber.i("MessageOverlayService: stopService called")
        }
    }
}
