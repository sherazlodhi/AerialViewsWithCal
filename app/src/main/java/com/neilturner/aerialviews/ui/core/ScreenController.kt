package com.neilturner.aerialviews.ui.core

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import com.neilturner.aerialviews.models.enums.AerialMediaSource
import com.neilturner.aerialviews.R
import com.neilturner.aerialviews.databinding.AerialActivityBinding
import com.neilturner.aerialviews.databinding.ImageViewBinding
import com.neilturner.aerialviews.databinding.OverlayViewBinding
import com.neilturner.aerialviews.databinding.VideoViewBinding
import com.neilturner.aerialviews.models.MediaPlaylist
import com.neilturner.aerialviews.models.enums.AerialMediaType
import com.neilturner.aerialviews.models.enums.DateType
import com.neilturner.aerialviews.models.enums.LocationType
import com.neilturner.aerialviews.models.enums.MetadataType
import com.neilturner.aerialviews.models.enums.OverlayType
import com.neilturner.aerialviews.models.enums.ProgressBarLocation
import com.neilturner.aerialviews.models.prefs.GeneralPrefs
import com.neilturner.aerialviews.models.videos.AerialMedia
import com.neilturner.aerialviews.services.KtorServer
import com.neilturner.aerialviews.services.MediaService
import com.neilturner.aerialviews.services.NowPlayingService
import com.neilturner.aerialviews.services.CalendarService
import com.neilturner.aerialviews.services.weather.WeatherService
import com.neilturner.aerialviews.ui.core.ImagePlayerView.OnImagePlayerEventListener
import com.neilturner.aerialviews.ui.core.VideoPlayerView.OnVideoPlayerEventListener
import com.neilturner.aerialviews.ui.overlays.MessageOverlay
import com.neilturner.aerialviews.ui.overlays.MetadataOverlay
import com.neilturner.aerialviews.ui.overlays.NowPlayingOverlay
import com.neilturner.aerialviews.ui.overlays.CalendarOverlay
import com.neilturner.aerialviews.ui.overlays.ProgressBar
import com.neilturner.aerialviews.ui.overlays.ProgressBarEvent
import com.neilturner.aerialviews.ui.overlays.ProgressState
import com.neilturner.aerialviews.ui.overlays.WeatherForecastOverlay
import com.neilturner.aerialviews.ui.overlays.WeatherCurrentOverlay
import com.neilturner.aerialviews.ui.overlays.state.OverlayEventBridge
import com.neilturner.aerialviews.ui.overlays.state.MessageOverlayState
import com.neilturner.aerialviews.ui.overlays.state.OverlayStateStore
import com.neilturner.aerialviews.ui.overlays.state.OverlayUiState
import com.neilturner.aerialviews.services.NewsService
import com.neilturner.aerialviews.ui.overlays.NewsOverlay
import com.neilturner.aerialviews.utils.ColourHelper
import com.neilturner.aerialviews.utils.FontHelper
import com.neilturner.aerialviews.utils.GradientHelper
import com.neilturner.aerialviews.utils.OverlayHelper
import com.neilturner.aerialviews.utils.PermissionHelper
import com.neilturner.aerialviews.utils.RefreshRateHelper
import com.neilturner.aerialviews.utils.ToastHelper
import com.neilturner.aerialviews.utils.VideoRotationStore
import com.neilturner.aerialviews.utils.WindowHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.kosert.flowbus.GlobalBus
import me.kosert.flowbus.EventsReceiver
import me.kosert.flowbus.subscribe
import com.neilturner.aerialviews.services.SpeechEvent
import com.neilturner.aerialviews.services.TtsService
import timber.log.Timber
import kotlin.math.abs

class ScreenController(
    private val context: Context,
) : OnVideoPlayerEventListener,
    OnImagePlayerEventListener {
    private val mainScope = CoroutineScope(Dispatchers.Main)
    private lateinit var playlist: MediaPlaylist
    private var overlayHelper: OverlayHelper
    private val resources by lazy { context.resources }

    private var nowPlayingService: NowPlayingService? = null
    private var weatherService: WeatherService? = null
    private var calendarService: CalendarService? = null
    private var newsService: NewsService? = null
    private var ktorServer: KtorServer? = null
    private var ttsService: TtsService? = null
    private val overlayStateStore = OverlayStateStore()
    private val overlayEventBridge = OverlayEventBridge(overlayStateStore)
    private val metadataResolver = MetadataResolver()

    private val shouldAlternateOverlays = GeneralPrefs.alternateTextPosition
    private val autoHideOverlayDelay = GeneralPrefs.overlayAutoHide.toLong()
    private val overlayRevealTimeout = GeneralPrefs.overlayRevealTimeout.toLong()
    private val overlayFadeOut: Long = GeneralPrefs.overlayFadeOutDuration.toLong()
    private val overlayFadeIn: Long = GeneralPrefs.overlayFadeInDuration.toLong()
    private val mediaFadeIn = GeneralPrefs.mediaFadeInDuration.toLong()
    private val mediaFadeOut = GeneralPrefs.mediaFadeOutDuration.toLong()

    private var isDualLoading = false
    private var canShowOverlays = false
    private var alternate = false
    private var previousItem = false
    private var canSkip = false
    private var isPaused = false
    private var pauseStartTime: Long = 0
    private var sleepTimerJob: Job? = null
    private val metadataJobs = mutableMapOf<OverlayType, Job>()
    private var currentMedia: AerialMedia? = null
    private val receiver = EventsReceiver()

    private val videoViewBinding: VideoViewBinding
    private val imageViewBinding: ImageViewBinding
    private val overlayViewBinding: OverlayViewBinding
    private val loadingView: View
    private val overlayView: View
    private var loadingText: TextView
    private lateinit var activeVideoPlayer: VideoPlayerView
    private var imagePlayer: ImagePlayerView
    private val brightnessView: View
    private val gradientTopView: View
    private val gradientBottomView: View
    private val progressBarView: ProgressBar
    private val debugTextView: TextView
    val view: View

    private val topLeftIds: List<Int>
    private val topRightIds: List<Int>
    private val bottomLeftIds: List<Int>
    private val bottomRightIds: List<Int>

    var blackOutMode = false
        private set

    init {
        isScreensaverActive = true
        me.kosert.flowbus.GlobalBus.post(com.neilturner.aerialviews.services.ScreensaverStateEvent(true))
        val inflater = LayoutInflater.from(context)
        val binding = AerialActivityBinding.inflate(inflater)

        val backgroundLoading = ColourHelper.colourFromString(GeneralPrefs.backgroundLoading)
        val backgroundVideos = ColourHelper.colourFromString(GeneralPrefs.backgroundVideos)
        val backgroundPhotos = ColourHelper.colourFromString(GeneralPrefs.backgroundPhotos)

        // Setup binding for all views and controls
        view = binding.root
        loadingView = binding.loadingView.root
        loadingView.setBackgroundColor(backgroundLoading)
        loadingText = binding.loadingView.loadingText

        overlayViewBinding = binding.overlayView
        overlayView = overlayViewBinding.root
        gradientTopView = overlayViewBinding.gradientTop
        gradientBottomView = overlayViewBinding.gradientBottom

        videoViewBinding = binding.videoView

        videoViewBinding.root.setBackgroundColor(backgroundVideos)
        activeVideoPlayer = videoViewBinding.root.findViewById(R.id.video_player_surface)
        activeVideoPlayer.setOnPlayerListener(this)

        imageViewBinding = binding.imageView
        imageViewBinding.root.setBackgroundColor(backgroundPhotos)
        imagePlayer = imageViewBinding.imagePlayer
        imagePlayer.setOnPlayerListener(this)

        brightnessView = binding.brightnessView
        progressBarView = binding.progressBar
        debugTextView = binding.root.findViewById(R.id.debug_text_view)

        // Setup loading message or hide it
        if (GeneralPrefs.showLoadingText) {
            loadingText.apply {
                textSize = GeneralPrefs.loadingTextSize.toFloat()
                typeface = FontHelper.getTypeface(context, GeneralPrefs.fontTypeface, GeneralPrefs.loadingTextWeight)
            }
        } else {
            loadingText.visibility = View.INVISIBLE
        }

        // Setup overlays and set initial positions
        overlayHelper = OverlayHelper(context, GeneralPrefs)
        val overlayIds = overlayHelper.buildOverlaysAndIds(overlayViewBinding)
        this.bottomLeftIds = overlayIds.bottomLeftIds
        this.bottomRightIds = overlayIds.bottomRightIds
        this.topLeftIds = overlayIds.topLeftIds
        this.topRightIds = overlayIds.topRightIds
        bindOverlayState()
        
        // Setup news overlay rendering
        mainScope.launch {
            overlayStateStore.uiState.collectLatest { state ->
                overlayViewBinding.newsSlide.render(state.news)
            }
        }

        overlayEventBridge.start()

        // Setup progress bar
        if (GeneralPrefs.progressBarLocation != ProgressBarLocation.DISABLED) {
            val gravity = if (GeneralPrefs.progressBarLocation == ProgressBarLocation.TOP) Gravity.TOP else Gravity.BOTTOM
            (progressBarView.layoutParams as FrameLayout.LayoutParams).gravity = gravity

            val alpha = GeneralPrefs.progressBarOpacity.toFloat() / 100
            progressBarView.alpha = alpha
            Timber.i("Progress bar: $alpha, ${GeneralPrefs.progressBarLocation}")

            progressBarView.visibility = View.VISIBLE
        }

        // Setup brightness/dimness
        if (GeneralPrefs.videoBrightness != "100") {
            val view = brightnessView
            view.setBackgroundColor(Color.BLACK)
            view.alpha = abs((GeneralPrefs.videoBrightness.toFloat() - 100) / 100)
            view.visibility = View.VISIBLE
        }

        // Reset animation speed if needed
        if (GeneralPrefs.ignoreAnimationScale) {
            WindowHelper.resetSystemAnimationDuration(context)
        }

        // Gradients - set up backgrounds but visibility will be managed with overlays
        if (GeneralPrefs.showTopGradient) {
            gradientTopView.background = GradientHelper.smoothBackgroundAlt(GradientDrawable.Orientation.TOP_BOTTOM)
            gradientTopView.visibility = View.VISIBLE
        }

        if (GeneralPrefs.showBottomGradient) {
            gradientBottomView.background = GradientHelper.smoothBackgroundAlt(GradientDrawable.Orientation.BOTTOM_TOP)
            gradientBottomView.visibility = View.VISIBLE
        }

        mainScope.launch {
            // Launch if we have permission
            // Used for a) Skip music tracks b) music info widget
            if (PermissionHelper.hasNotificationListenerPermission(context)) {
                nowPlayingService = NowPlayingService(context)
            }

            if (GeneralPrefs.messageApiEnabled) {
                // Only start our own server if the always-on overlay service isn't already
                // running one (it would cause a port bind conflict)
                if (!GeneralPrefs.messageApiAlwaysOnOverlay) {
                    ktorServer =
                        KtorServer(context) { messageEvent ->
                            GlobalBus.post(messageEvent)
                        }.apply {
                            start()
                        }
                }

                // Initialize TTS for #Announce# command
                ttsService = TtsService(context)
                receiver.subscribe<SpeechEvent> { event ->
                    ttsService?.speak(event.text)
                }
            }

            // Build playlist and start screensaver
            playlist = MediaService(context).fetchMedia()
            if (playlist.size > 0) {
                Timber.i("Playlist size: ${playlist.size}")
                loadItem(playlist.nextItem())
                scheduleSleepTimer()
            } else {
                showLoadingError()
            }

            // Setup weather service
            val hasWeatherCurrentOverlay = overlayHelper.findOverlay<WeatherCurrentOverlay>().isNotEmpty()
            val hasForecastOverlay = overlayHelper.findOverlay<WeatherForecastOverlay>().isNotEmpty()
            if (hasWeatherCurrentOverlay || hasForecastOverlay) {
                weatherService =
                    WeatherService(context).apply {
                        startUpdates(
                            fetchCurrentWeather = hasWeatherCurrentOverlay,
                            fetchForecast = hasForecastOverlay,
                        )
                    }
            }

            // Setup calendar service
            if (overlayHelper.findOverlay<CalendarOverlay>().isNotEmpty() || GeneralPrefs.calendarAsSlide) {
                calendarService = CalendarService(context).apply { startUpdates() }
            }

            // Setup news service
            if (GeneralPrefs.newsEnabled) {
                newsService = NewsService(context).apply { startUpdates() }
            }
        }
        // 1. Load playlist
        // 2. load video, setup location/POI, start playback call
        // 3. playback started callback, fade out loading text, fade out loading view
        // 4. when video is almost finished - or skip - fade in loading view
        // 5. goto 2
    }

    private fun scheduleSleepTimer() {
        sleepTimerJob?.cancel()
        val minutes = GeneralPrefs.sleepTimer.toLongOrNull() ?: 0L
        if (minutes <= 0L) {
            Timber.i("Sleep timer disabled")
            return
        }
        Timber.i("Scheduling sleep timer for $minutes minute(s)")
        sleepTimerJob =
            mainScope.launch {
                delay(minutes * 60_000L)
                if (!blackOutMode) {
                    Timber.i("Sleep timer finished - toggling blackout mode")
                    toggleBlackOutMode()
                }
            }
    }

    private fun loadItem(media: AerialMedia) {
        // Reset pause state when loading new item
        isPaused = false
        pauseStartTime = 0
        currentMedia = media
        overlayStateStore.resetForNextMedia()

        if (media.uri.toString().contains("smb://")) {
            val pattern = Regex("(smb://)([^:]+):([^@]+)@([\\d.]+)/")
            val replacement = "$1****:****@****/"
            val url = pattern.replace(media.uri.toString(), replacement)
            Timber.i("Loading: ${media.metadata.shortDescription} - $url (${media.metadata.pointsOfInterest})")
        } else {
            Timber.i("Loading: ${media.metadata.shortDescription} - ${media.uri} (${media.metadata.pointsOfInterest})")
        }

        updateMetadataOverlayData(media)

        if (debugTextView.visibility == View.VISIBLE) {
            updateDebugInfo(media)
        }

        // Set overlay positions
        overlayHelper.assignOverlaysAndIds(
            overlayViewBinding.flowBottomLeft,
            overlayViewBinding.flowBottomRight,
            bottomLeftIds,
            bottomRightIds,
            alternate,
        )

        overlayHelper.assignOverlaysAndIds(
            overlayViewBinding.flowTopLeft,
            overlayViewBinding.flowTopRight,
            topLeftIds,
            topRightIds,
            alternate,
        )

        if (shouldAlternateOverlays) {
            alternate = !alternate
        }

        // Videos
        if (media.type == AerialMediaType.VIDEO) {
            activeVideoPlayer.setVideo(media)
            // Apply any saved rotation for this video
            val savedRotation = VideoRotationStore.getRotation(context, media.uri)
            applyVideoRotation(savedRotation)
            videoViewBinding.root.visibility = View.VISIBLE
            imageViewBinding.root.visibility = View.INVISIBLE
        }

        // Images
        if (media.type == AerialMediaType.IMAGE) {
            isDualLoading = false
            imagePlayer.setImage(media)
            imageViewBinding.root.visibility = View.VISIBLE
            videoViewBinding.root.visibility = View.INVISIBLE
            overlayViewBinding.calendarSlide.visibility = View.GONE
            overlayViewBinding.newsSlide.visibility = View.GONE
        }

        // Calendar Slide
        if (media.type == AerialMediaType.CALENDAR) {
            calendarService?.refreshNow()
            overlayViewBinding.calendarSlide.visibility = View.VISIBLE
            videoViewBinding.root.visibility = View.INVISIBLE
            imageViewBinding.root.visibility = View.INVISIBLE
            
            mainScope.launch {
                fadeInNextItem()
                delay(GeneralPrefs.slideshowSpeed.toLong() * 1000L)
                if (currentMedia?.type == AerialMediaType.CALENDAR) {
                    fadeOutCurrentItem()
                }
            }
        } else if (media.type == AerialMediaType.INFO) {
            newsService?.refreshNow()
            overlayViewBinding.newsSlide.visibility = View.VISIBLE
            videoViewBinding.root.visibility = View.INVISIBLE
            imageViewBinding.root.visibility = View.INVISIBLE
            overlayViewBinding.calendarSlide.visibility = View.GONE
            
            mainScope.launch {
                fadeInNextItem()
                delay(GeneralPrefs.slideshowSpeed.toLong() * 2000L)
                if (currentMedia?.type == AerialMediaType.INFO) {
                    fadeOutCurrentItem()
                }
            }
        } else if (media.type != AerialMediaType.CALENDAR && media.type != AerialMediaType.INFO) {
            overlayViewBinding.calendarSlide.visibility = View.GONE
            overlayViewBinding.newsSlide.visibility = View.GONE
        }

        // Best to rest progress bar (if enabled) before media playback
        if (GeneralPrefs.progressBarLocation != ProgressBarLocation.DISABLED) {
            GlobalBus.post(ProgressBarEvent(ProgressState.RESET))
        }

        if (media.type == AerialMediaType.VIDEO) {
            activeVideoPlayer.start()
        }
    }

    private fun fadeOutLoadingText() {
        // Fade out TextView
        loadingText
            .animate()
            .alpha(0f)
            .setDuration(LOADING_FADE_OUT)
            .withEndAction {
                loadingText.visibility = TextView.GONE
            }.start()
    }

    private fun fadeInNextItem() {
        canShowOverlays = false
        var startDelay: Long = 0
        val overlayDelay = (autoHideOverlayDelay * 1000) + mediaFadeIn

        // If first video (ie. screensaver startup), fade out 'loading...' text
        if (loadingText.isVisible) {
            fadeOutLoadingText()
            startDelay = LOADING_DELAY
        }

        // Reset any overlay animations
        if (autoHideOverlayDelay >= 0) {
            overlayHelper.getOverlaysToFade().forEach { view ->
                view.animate()?.cancel()
                view.clearAnimation()
            }
        }

        // Hide overlays immediately
        if (autoHideOverlayDelay.toInt() == 0) {
            overlayHelper.getOverlaysToFade().forEach { it.alpha = 0f }
            // Also hide gradients immediately if they have fading overlays
            // AND no persistent overlays
            if (GeneralPrefs.showTopGradient && overlayHelper.hasTopOverlaysToFade() && !overlayHelper.hasTopPersistentOverlays()) {
                gradientTopView.alpha = 0f
            }
            if (GeneralPrefs.showBottomGradient && overlayHelper.hasBottomOverlaysToFade() &&
                !overlayHelper.hasBottomPersistentOverlays()
            ) {
                gradientBottomView.alpha = 0f
            }
            canShowOverlays = true
        }

        // Hide overlays after a delay
        if (autoHideOverlayDelay > 0) {
            overlayHelper.getOverlaysToFade().forEach { it.alpha = 1f }
            // Also show gradients initially if they have fading overlays
            if (GeneralPrefs.showTopGradient && overlayHelper.hasTopOverlaysToFade()) {
                gradientTopView.alpha = 1f
            }
            if (GeneralPrefs.showBottomGradient && overlayHelper.hasBottomOverlaysToFade()) {
                gradientBottomView.alpha = 1f
            }
            hideOverlays(overlayDelay)
        }

        // Fade out LoadingView
        // Video should be playing underneath
        loadingView
            .animate()
            .alpha(0f)
            .setStartDelay(startDelay)
            .setDuration(mediaFadeIn)
            .withEndAction {
                loadingView.alpha = 0f
                loadingView.visibility = View.INVISIBLE
                canSkip = true
            }.start()
    }

    private fun fadeOutCurrentItem() {
        if (!canSkip) return
        canSkip = false

        overlayHelper.findOverlay<MetadataOverlay>().forEach {
            it.isFadingOutMedia = true
        }

        if (currentMedia?.type == AerialMediaType.VIDEO) {
            activeVideoPlayer.fadeOutAudio(mediaFadeOut)
        }

        // Fade in LoadView (ie. black screen)
        loadingView
            .animate()
            .alpha(1f)
            .setStartDelay(0)
            .setDuration(mediaFadeOut)
            .withStartAction {
                loadingView.visibility = View.VISIBLE
                loadingView.alpha = 0f
            }.withEndAction {
                // Hide content views after faded out
                videoViewBinding.root.visibility = View.INVISIBLE
                activeVideoPlayer.stop()

                imageViewBinding.root.visibility = View.INVISIBLE
                imageViewBinding.imagePlayer.stop()

                overlayViewBinding.calendarSlide.visibility = View.GONE
                overlayViewBinding.newsSlide.visibility = View.GONE

                // Reset pause state when transitioning between items
                isPaused = false
                pauseStartTime = 0

                // Pick next/previous video
                val media =
                    if (!previousItem) {
                        playlist.nextItem()
                    } else {
                        playlist.previousItem()
                    }
                previousItem = false

                if (!blackOutMode) {
                    loadItem(media)
                }
            }.start()
    }

    private fun showLoadingError() {
        loadingText.text = resources.getString(R.string.loading_error)
    }

    private fun hideOverlays(delay: Long = 0L) {
        val overlaysToFade = overlayHelper.getOverlaysToFade()

        if (overlaysToFade.isEmpty()) {
            canShowOverlays = true
            return
        }

        overlaysToFade.forEachIndexed { index, view ->
            val animator =
                view
                    .animate()
                    .alpha(0f)
                    .setStartDelay(delay)
                    .setDuration(overlayFadeOut)

            // Only set the end action on the last overlay
            if (index == overlaysToFade.lastIndex) {
                animator.withEndAction { canShowOverlays = true }
            }
            animator.start()
        }

        // Fade out gradients if their corresponding region has fading overlays
        // AND no persistent overlays (otherwise gradient should stay visible)
        if (GeneralPrefs.showTopGradient && overlayHelper.hasTopOverlaysToFade() && !overlayHelper.hasTopPersistentOverlays()) {
            gradientTopView
                .animate()
                .alpha(0f)
                .setStartDelay(delay)
                .setDuration(overlayFadeOut)
                .start()
        }
        if (GeneralPrefs.showBottomGradient && overlayHelper.hasBottomOverlaysToFade() && !overlayHelper.hasBottomPersistentOverlays()) {
            gradientBottomView
                .animate()
                .alpha(0f)
                .setStartDelay(delay)
                .setDuration(overlayFadeOut)
                .start()
        }
    }

    fun showOverlays() {
        // Overlay auto hide pref must be enabled
        if (autoHideOverlayDelay < 0) return

        // If blackout mode is on, exit
        if (blackOutMode) return

        // If media fading in/out
        if (!canSkip) return

        // Are overlays already visible
        if (!canShowOverlays) return

        val overlaysToFade = overlayHelper.getOverlaysToFade()

        if (overlaysToFade.isEmpty()) return

        canShowOverlays = false

        overlaysToFade.forEachIndexed { index, view ->
            val animator =
                view
                    .animate()
                    .alpha(1f)
                    .setStartDelay(0)
                    .setDuration(overlayFadeIn)

            // Only set the end action on the last overlay
            if (index == overlaysToFade.lastIndex) {
                animator.withEndAction { hideOverlays(overlayRevealTimeout * 1000) }
            }
            animator.start()
        }

        // Fade in gradients if their corresponding region has fading overlays
        if (GeneralPrefs.showTopGradient && overlayHelper.hasTopOverlaysToFade()) {
            gradientTopView
                .animate()
                .alpha(1f)
                .setStartDelay(0)
                .setDuration(overlayFadeIn)
                .start()
        }
        if (GeneralPrefs.showBottomGradient && overlayHelper.hasBottomOverlaysToFade()) {
            gradientBottomView
                .animate()
                .alpha(1f)
                .setStartDelay(0)
                .setDuration(overlayFadeIn)
                .start()
        }
    }

    fun stop() {
        isScreensaverActive = false
        me.kosert.flowbus.GlobalBus.post(com.neilturner.aerialviews.services.ScreensaverStateEvent(false))
        RefreshRateHelper.restoreOriginalMode(context)
        receiver.unsubscribe()
        overlayEventBridge.stop()
        view.setOnTouchListener(null)
        activeVideoPlayer.release()
        imagePlayer.release()
        ktorServer?.stop()
        ttsService?.stop()
        nowPlayingService?.stop()
        weatherService?.stop()
        calendarService?.stop()
        newsService?.stop()
        sleepTimerJob?.cancel()
        metadataJobs.values.forEach { it.cancel() }
        metadataJobs.clear()
        mainScope.cancel()
    }

    fun skipItem(previous: Boolean = false) {
        previousItem = previous
        fadeOutCurrentItem()
    }

    fun toggleBlackOutMode() {
        if (!this::playlist.isInitialized || playlist.size == 0) {
            return
        }

        if (!blackOutMode) {
            blackOutMode = true
            // Cancel any pending sleep timer as we've already entered blackout
            sleepTimerJob?.cancel()
            fadeOutCurrentItem()
        } else {
            blackOutMode = false
            loadItem(playlist.nextItem())
            // Restart sleep timer if preference still enabled
            scheduleSleepTimer()
        }
    }

    fun nextTrack() {
        nowPlayingService?.nextTrack()
    }

    fun previousTrack() {
        nowPlayingService?.previousTrack()
    }

    fun increaseSpeed() {
        if (blackOutMode) {
            return
        }
        activeVideoPlayer.increaseSpeed()
    }

    fun decreaseSpeed() {
        if (blackOutMode) {
            return
        }
        activeVideoPlayer.decreaseSpeed()
    }

    fun seekForward() {
        if (blackOutMode) {
            return
        }
        activeVideoPlayer.seekForward()
    }

    fun seekBackward() {
        if (blackOutMode) {
            return
        }
        activeVideoPlayer.seekBackward()
    }

    fun togglePause() {
        if (isPaused) {
            resumeMedia()
        } else {
            pauseMedia()
        }
    }

    fun toggleLooping() {
        if (videoViewBinding.root.isVisible) {
            activeVideoPlayer.toggleLooping()
        }
    }

    fun toggleDebugInfo() {
        if (debugTextView.visibility == View.VISIBLE) {
            debugTextView.visibility = View.GONE
        } else {
            debugTextView.visibility = View.VISIBLE
            currentMedia?.let { updateDebugInfo(it) }
        }
    }

    private fun updateDebugInfo(media: AerialMedia) {
        val info = buildString {
            append("URI: ").append(media.uri).append("\n")
            append("Source: ").append(media.source.name).append("\n")
            append("Type: ").append(media.type.name).append("\n")
            append("Exif: ").append(media.metadata.exif).append("\n")
        }
        debugTextView.text = info
    }

    fun increaseBrightness() = changeBrightness(true)

    fun decreaseBrightness() = changeBrightness(false)

    private fun changeBrightness(increase: Boolean) {
        if (blackOutMode) return

        val brightnessValues = resources.getStringArray(R.array.percentage1_values)
        val currentBrightness = GeneralPrefs.videoBrightness
        val currentIndex = brightnessValues.indexOf(currentBrightness)

        if (currentIndex == -1) return

        if (increase && currentIndex == brightnessValues.size - 1) return
        if (!increase && currentIndex == 0) return

        val newIndex = if (increase) currentIndex + 1 else currentIndex - 1
        val newBrightness = brightnessValues[newIndex]

        GeneralPrefs.videoBrightness = newBrightness

        // Update view
        val view = brightnessView
        if (newBrightness == "100") {
            view.visibility = View.GONE
        } else {
            view.setBackgroundColor(Color.BLACK)
            view.alpha = abs((newBrightness.toFloat() - 100) / 100)
            view.visibility = View.VISIBLE
        }

        // Show toast
        mainScope.launch {
            ToastHelper.show(context, "Brightness: $newBrightness%")
        }
    }

    fun toggleMute() {
        activeVideoPlayer.toggleMute()
    }

    fun rotateVideo() {
        val media = currentMedia ?: return
        // Only rotate videos (not images, calendar slides, etc.)
        if (media.type != AerialMediaType.VIDEO) return

        // Cycle: 0 → 90 → 180 → 270 → 0
        val current = VideoRotationStore.getRotation(context, media.uri)
        val next = (current + 90) % 360

        VideoRotationStore.saveRotation(context, media.uri, next)
        applyVideoRotation(next)

        mainScope.launch {
            val label = when (next) {
                0 -> "Rotation: Normal"
                90 -> "Rotation: 90°"
                180 -> "Rotation: 180°"
                270 -> "Rotation: 270°"
                else -> "Rotation: ${next}°"
            }
            ToastHelper.show(context, label)
        }
    }

    private fun applyVideoRotation(degrees: Int) {
        val player = activeVideoPlayer
        player.rotation = degrees.toFloat()
        if (degrees == 90 || degrees == 270) {
            // After a 90/270 rotation the video width and height are swapped visually.
            // Scale uniformly by w/h so it fills the landscape screen.
            player.post {
                val w = player.width.toFloat()
                val h = player.height.toFloat()
                if (w > 0 && h > 0) {
                    val scale = w / h
                    player.scaleX = scale
                    player.scaleY = scale
                }
            }
        } else {
            player.scaleX = 1f
            player.scaleY = 1f
        }
    }

    private fun pauseMedia() {
        if (isPaused) return

        isPaused = true
        pauseStartTime = System.currentTimeMillis()

        // Pause video if currently showing
        if (videoViewBinding.root.isVisible) {
            activeVideoPlayer.pause()
        }

        // Pause image timer if currently showing
        if (imageViewBinding.root.isVisible) {
            imagePlayer.pauseTimer()
        }

        // Pause progress bar
        if (GeneralPrefs.progressBarLocation != ProgressBarLocation.DISABLED) {
            GlobalBus.post(ProgressBarEvent(ProgressState.PAUSE))
        }
    }

    private fun resumeMedia() {
        if (!isPaused) return

        isPaused = false
        val pauseDuration = System.currentTimeMillis() - pauseStartTime

        // Resume video if currently showing
        if (videoViewBinding.root.isVisible) {
            activeVideoPlayer.resume()
        }

        // Resume image timer if currently showing
        if (imageViewBinding.root.isVisible) {
            imagePlayer.resumeTimer(pauseDuration)
        }

        // Resume progress bar
        if (GeneralPrefs.progressBarLocation != ProgressBarLocation.DISABLED) {
            GlobalBus.post(ProgressBarEvent(ProgressState.RESUME))
        }
    }

    private fun handleError() {
        mainScope.launch {
            delay(ERROR_DELAY)
            if (loadingView.isVisible) {
                loadItem(playlist.nextItem())
            } else {
                fadeOutCurrentItem()
            }
        }
    }

    private fun handlePlaybackSpeedChanged() {
        val message = resources.getString(R.string.playlist_playback_speed_changed, GeneralPrefs.playbackSpeed + "x")
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    override fun onVideoPlaybackSpeedChanged() = handlePlaybackSpeedChanged()

    override fun onVideoAlmostFinished() = fadeOutCurrentItem()

    override fun onVideoPrepared() = fadeInNextItem()

    override fun onVideoError() = handleError()

    override fun onImageFinished() = fadeOutCurrentItem()

    override fun onImageError() = handleError()

    private fun updateMetadataOverlayData(media: AerialMedia) {
        val metadataSlots =
            listOf(
                OverlayType.METADATA1,
                OverlayType.METADATA2,
                OverlayType.METADATA3,
                OverlayType.METADATA4,
            )

        metadataSlots.forEach { slot ->
            metadataJobs[slot]?.cancel()
            metadataJobs[slot] =
                mainScope.launch {
                    try {
                        val preferences = getMetadataPreferences(slot)
                        val resolved = metadataResolver.resolve(context, media, preferences)
                        if (currentMedia !== media) return@launch

                        overlayStateStore.setMetadata(
                            slot,
                            resolved.text,
                            resolved.poi,
                            resolved.metadataType,
                        )
                    } catch (e: Exception) {
                        Timber.e(e, "Metadata slot $slot resolver failed")
                        if (currentMedia === media) {
                            overlayStateStore.setMetadata(slot, "", emptyMap(), MetadataType.STATIC)
                        }
                    }
                }
        }
    }

    private fun getMetadataPreferences(slot: OverlayType): MetadataResolver.Preferences =
        when (slot) {
            OverlayType.METADATA1 -> {
                MetadataResolver.Preferences(
                    videoSelection = GeneralPrefs.overlayMetadata1Videos,
                    videoFolderDepth = GeneralPrefs.overlayMetadata1VideosFolderLevel.toIntOrNull() ?: 1,
                    videoLocationType =
                        GeneralPrefs.overlayMetadata1VideosLocationType ?: LocationType.CITY_COUNTRY,
                    photoSelection = GeneralPrefs.overlayMetadata1Photos,
                    photoFolderDepth = GeneralPrefs.overlayMetadata1PhotosFolderLevel.toIntOrNull() ?: 1,
                    photoLocationType =
                        GeneralPrefs.overlayMetadata1PhotosLocationType ?: LocationType.CITY_COUNTRY,
                    photoDateType =
                        GeneralPrefs.overlayMetadata1PhotosDateType ?: DateType.COMPACT,
                    photoDateCustom = GeneralPrefs.overlayMetadata1PhotosDateCustom,
                )
            }

            OverlayType.METADATA2 -> {
                MetadataResolver.Preferences(
                    videoSelection = GeneralPrefs.overlayMetadata2Videos,
                    videoFolderDepth = GeneralPrefs.overlayMetadata2VideosFolderLevel.toIntOrNull() ?: 1,
                    videoLocationType =
                        GeneralPrefs.overlayMetadata2VideosLocationType ?: LocationType.CITY_COUNTRY,
                    photoSelection = GeneralPrefs.overlayMetadata2Photos,
                    photoFolderDepth = GeneralPrefs.overlayMetadata2PhotosFolderLevel.toIntOrNull() ?: 1,
                    photoLocationType =
                        GeneralPrefs.overlayMetadata2PhotosLocationType ?: LocationType.CITY_COUNTRY,
                    photoDateType =
                        GeneralPrefs.overlayMetadata2PhotosDateType ?: DateType.COMPACT,
                    photoDateCustom = GeneralPrefs.overlayMetadata2PhotosDateCustom,
                )
            }

            OverlayType.METADATA3 -> {
                MetadataResolver.Preferences(
                    videoSelection = GeneralPrefs.overlayMetadata3Videos,
                    videoFolderDepth = GeneralPrefs.overlayMetadata3VideosFolderLevel.toIntOrNull() ?: 1,
                    videoLocationType =
                        GeneralPrefs.overlayMetadata3VideosLocationType ?: LocationType.CITY_COUNTRY,
                    photoSelection = GeneralPrefs.overlayMetadata3Photos,
                    photoFolderDepth = GeneralPrefs.overlayMetadata3PhotosFolderLevel.toIntOrNull() ?: 1,
                    photoLocationType =
                        GeneralPrefs.overlayMetadata3PhotosLocationType ?: LocationType.CITY_COUNTRY,
                    photoDateType =
                        GeneralPrefs.overlayMetadata3PhotosDateType ?: DateType.COMPACT,
                    photoDateCustom = GeneralPrefs.overlayMetadata3PhotosDateCustom,
                )
            }

            else -> {
                MetadataResolver.Preferences(
                    videoSelection = GeneralPrefs.overlayMetadata4Videos,
                    videoFolderDepth = GeneralPrefs.overlayMetadata4VideosFolderLevel.toIntOrNull() ?: 1,
                    videoLocationType =
                        GeneralPrefs.overlayMetadata4VideosLocationType ?: LocationType.CITY_COUNTRY,
                    photoSelection = GeneralPrefs.overlayMetadata4Photos,
                    photoFolderDepth = GeneralPrefs.overlayMetadata4PhotosFolderLevel.toIntOrNull() ?: 1,
                    photoLocationType =
                        GeneralPrefs.overlayMetadata4PhotosLocationType ?: LocationType.CITY_COUNTRY,
                    photoDateType =
                        GeneralPrefs.overlayMetadata4PhotosDateType ?: DateType.COMPACT,
                    photoDateCustom = GeneralPrefs.overlayMetadata4PhotosDateCustom,
                )
            }
        }

    override fun onImagePrepared() {
        Timber.d("onImagePrepared")
        currentMedia
            ?.takeIf { it.type == AerialMediaType.IMAGE }
            ?.let { updateMetadataOverlayData(it) }
        fadeInNextItem()
    }

    override fun onImageOrientationDetected(isPortrait: Boolean) {
        if (GeneralPrefs.photoDualPortrait && isPortrait && !isDualLoading) {
            val nextMedia = playlist.peekNextItem()
            if (nextMedia?.type == AerialMediaType.IMAGE) {
                // We don't know if the next one is portrait yet, 
                // but we'll try to load it into the second slot.
                isDualLoading = true
                playlist.nextItem() // Consume it from playlist
                imagePlayer.setDualImages(currentMedia!!, nextMedia)
            }
        }
    }

    private fun bindOverlayState() {
        mainScope.launch {
            overlayStateStore.uiState.collectLatest { state ->
                renderOverlayState(state)
            }
        }
    }

    private fun renderOverlayState(state: OverlayUiState) {
        overlayHelper.findOverlay<MetadataOverlay>().forEach { overlay ->
            val locationState = state.metadata[overlay.type]
            if (locationState != null) {
                overlay.render(locationState, activeVideoPlayer)
            }
        }

        overlayHelper.findOverlay<NowPlayingOverlay>().forEach {
            it.render(state.nowPlaying)
        }

        overlayHelper.findOverlay<WeatherCurrentOverlay>().forEach {
            it.render(state.weather)
        }

        overlayHelper.findOverlay<WeatherForecastOverlay>().forEach {
            it.render(state.forecast)
        }

        val overlays = overlayHelper.findOverlay<MessageOverlay>()
        timber.log.Timber.i("ScreenController: found ${overlays.size} message overlays")
        overlays.forEach { overlay ->
            overlay.render(state.message[overlay.type] ?: MessageOverlayState())
        }
        
        overlayViewBinding.tickerOverlay.apply {
            type = OverlayType.TICKER
            render(state.message[OverlayType.TICKER] ?: MessageOverlayState())
        }

        overlayHelper.findOverlay<CalendarOverlay>().forEach {
            it.isFullSlide = false
            it.render(state.calendar)
        }
        overlayViewBinding.calendarSlide.isFullSlide = true
        overlayViewBinding.calendarSlide.render(state.calendar)

        progressBarView.render(state.progress)
    }

    companion object {
        var isScreensaverActive = false
        const val LOADING_FADE_OUT: Long = 300 // Fade out loading text
        const val LOADING_DELAY: Long = 400 // Delay before fading out loading view
        const val ERROR_DELAY: Long = 2000 // Delay before loading next item, after error
    }
}
