package com.neilturner.aerialviews.ui.core

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import coil3.ImageLoader
import coil3.asDrawable
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import com.neilturner.aerialviews.models.enums.AerialMediaSource
import com.neilturner.aerialviews.models.enums.AspectRatio
import com.neilturner.aerialviews.models.enums.PhotoScale
import com.neilturner.aerialviews.models.enums.ProgressBarLocation
import com.neilturner.aerialviews.models.enums.ProgressBarType
import com.neilturner.aerialviews.models.prefs.GeneralPrefs
import com.neilturner.aerialviews.models.videos.AerialMedia
import com.neilturner.aerialviews.services.InputStreamFetcher
import com.neilturner.aerialviews.ui.core.ImagePlayerHelper.buildGifDecoder
import com.neilturner.aerialviews.ui.core.ImagePlayerHelper.buildOkHttpClient
import com.neilturner.aerialviews.ui.overlays.ProgressBarEvent
import com.neilturner.aerialviews.ui.overlays.ProgressState
import com.neilturner.aerialviews.utils.BitmapHelper
import com.neilturner.aerialviews.utils.FirebaseHelper
import com.neilturner.aerialviews.utils.ToastHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.kosert.flowbus.GlobalBus
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.PushbackInputStream
import kotlin.time.Duration.Companion.milliseconds

class ImagePlayerView : FrameLayout {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    private var listener: OnImagePlayerEventListener? = null
    private var finishedRunnable = Runnable { listener?.onImageFinished() }
    private var errorRunnable = Runnable { listener?.onImageError() }

    private val ioJob = SupervisorJob()
    private val mainJob = SupervisorJob()
    private val ioScope = CoroutineScope(Dispatchers.IO + ioJob)
    private val mainScope = CoroutineScope(Dispatchers.Main + mainJob)

    private var pausedTimestamp: Long = 0
    private var totalDuration: Long = 0
    private var remainingDuration: Long = 0

    /**
     * Two-token sync gate: ensures [runSetupFinishedRunnable] fires only after *both*
     * the foreground image load (Coil onSuccess) and the background blur (potentially
     * async on pre-S devices) have completed for the same image request.
     *
     * - [backgroundReadyToken] is set by [markBackgroundReady] when [BackgroundBlurHelper]
     *   reports its background job is done.
     * - [pendingSetupToken] is set by [setupFinishedRunnable] when the foreground load
     *   finishes but the background isn't ready yet.
     * - [runSetupFinishedRunnable] fires when both tokens agree on the same value.
     */
    private var backgroundReadyToken: Long = -1
    private var pendingSetupToken: Long = -1

    private var isDualPortraitMode = false
    private val foregroundImageView =
        AppCompatImageView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
    private val secondImageView =
        AppCompatImageView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            visibility = GONE
        }
    private val backgroundImageView =
        AppCompatImageView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = GONE
        }
    private val progressBar =
        GeneralPrefs.progressBarLocation != ProgressBarLocation.DISABLED &&
            GeneralPrefs.progressBarType != ProgressBarType.VIDEOS

    private val blurHelper =
        BackgroundBlurHelper(
            backgroundImageView = backgroundImageView,
            ioScope = ioScope,
            mainScope = mainScope,
            resolveTargetSize = ::resolveTargetSize,
            onReady = ::markBackgroundReady,
        )

    init {
        addView(backgroundImageView)
        addView(foregroundImageView)
        addView(secondImageView)
    }

    fun release() {
        foregroundImageView.setImageDrawable(null)
        secondImageView.setImageDrawable(null)
        backgroundImageView.setImageDrawable(null)
        blurHelper.cancel()

        ioJob.cancel()
        mainJob.cancel()
        removeCallbacks(finishedRunnable)
        removeCallbacks(errorRunnable)
        listener = null
    }

    private val imageLoader =
        ImageLoader
            .Builder(context)
            .memoryCache(null)
            // .logger(logger)
            .components {
                add(OkHttpNetworkFetcherFactory(buildOkHttpClient()))
                add(InputStreamFetcher.Factory())
                add(buildGifDecoder())
            }.build()

    fun setImage(media: AerialMedia) {
        ioScope.launch {
            val baseStream = ImagePlayerHelper.streamFromMedia(context, media)
            if (baseStream == null) {
                loadImage(media.uri) // Pointless?
                return@launch
            }

            if (media.source == AerialMediaSource.IMMICH) {
                loadImage(baseStream)
                return@launch
            }

            val totalStartTime = System.currentTimeMillis()
            val stream =
                PushbackInputStream(
                    BufferedInputStream(baseStream, STREAM_BUFFER_SIZE),
                    BitmapHelper.HEADER_BUFFER_SIZE,
                )

            val headerBytes = ByteArray(BitmapHelper.HEADER_BUFFER_SIZE)
            val headerLength = readUpTo(stream, headerBytes, headerBytes.size)
            if (headerLength <= 0) {
                stream.close()
                loadImage(media.uri)
                return@launch
            }
            stream.unread(headerBytes, 0, headerLength)

            val exifMetadata = BitmapHelper.extractExifMetadataFromHeader(headerBytes, headerLength)
            val isPortrait = exifMetadata.orientation == 6 || exifMetadata.orientation == 8
            media.isPortrait = isPortrait

            Timber.d(
                "ImagePlayerView: Extracted EXIF in ${System.currentTimeMillis() - totalStartTime}ms. source=${media.source} isPortrait=$isPortrait orientation=${exifMetadata.orientation}",
            )

            mainScope.launch {
                listener?.onImageOrientationDetected(isPortrait)
            }

            loadImage(stream, isSecondary = false)
        }
    }

    fun setDualImages(media1: AerialMedia, media2: AerialMedia) {
        isDualPortraitMode = true
        setImage(media1) // This will load first one into foreground
        // We'll need another way to load the second one
        loadSecondImage(media2)
    }

    private fun loadSecondImage(media: AerialMedia) {
        ioScope.launch {
            val stream = ImagePlayerHelper.streamFromMedia(context, media)
            if (stream != null) {
                loadImage(stream, isSecondary = true)
            }
        }
    }

    private fun loadImage(data: Any?, isSecondary: Boolean = false) {
        // Errors from the actual async network/disk load are delivered via onError, not here.
        try {
            val (targetWidth, targetHeight) = resolveTargetSize()
            val request =
                ImageRequest
                    .Builder(context)
                    .data(data)
                    .size(targetWidth, targetHeight)
                    .target(
                        onStart = {
                            // resetImageTransforms()
                        },
                        onSuccess = { image ->
                            val drawable = image.asDrawable(resources)
                            if (isSecondary) {
                                secondImageView.setImageDrawable(drawable)
                                secondImageView.visibility = VISIBLE
                                updateDualLayout()
                            } else {
                                // Check for aspect ratio mismatch to determine if we should force blur background
                                val photoAR = if (image.height > 0) image.width.toFloat() / image.height.toFloat() else 1f
                                val screenAR = resolveScreenAspectRatio()
                                val isMismatch = kotlin.math.abs(photoAR - screenAR) > 0.1f
                                
                                blurHelper.update(drawable, forceShow = isMismatch)
                                foregroundImageView.setImageDrawable(drawable)
                                if (isDualPortraitMode) updateDualLayout()
                            }
                        },
                    ).listener(
                        onSuccess = { _, result ->
                            if (!isSecondary) {
                                setScaleMode(result.image.width, result.image.height)
                                setupFinishedRunnable()
                            }
                        },
                        onError = { _, result ->
                            handleImageError(result.throwable)
                        },
                    ).build()
            imageLoader.enqueue(request)
        } catch (ex: Exception) {
            Timber.e(ex, "Exception while trying to load image: ${ex.message}")

            if (data is InputStream) {
                try {
                    data.close()
                } catch (_: Exception) {
                    // ignore
                }
            }

            // Show toast if preference is enabled
            if (GeneralPrefs.showMediaErrorToasts) {
                mainScope.launch {
                    val errorMessage = ex.localizedMessage ?: "Photo loading error occurred"
                    ToastHelper.show(context, errorMessage)
                }
            }

            listener?.onImageError()
        }
    }

    private fun readUpTo(
        stream: InputStream,
        buffer: ByteArray,
        maxBytes: Int,
    ): Int {
        var total = 0
        val limit = minOf(maxBytes, buffer.size)
        while (total < limit) {
            val read = stream.read(buffer, total, limit - total)
            if (read <= 0) break
            total += read
        }
        return total
    }

    companion object {
        private const val STREAM_BUFFER_SIZE = 64 * 1024 // 64KB - helps reduce network round-trips
    }

    private fun resolveTargetSize(): Pair<Int, Int> {
        val width = if (this.width > 0) this.width else resources.displayMetrics.widthPixels
        val height = if (this.height > 0) this.height else resources.displayMetrics.heightPixels
        return Pair(width, height)
    }

    private fun resolveScreenAspectRatio(): Float {
        val (w, h) = resolveTargetSize()
        return if (h > 0) w.toFloat() / h.toFloat() else 1.77f
    }

    private fun handleImageError(throwable: Throwable) {
        Timber.e(throwable, "Exception while loading image: ${throwable.message}")
        FirebaseHelper.crashlyticsException(throwable)

        if (GeneralPrefs.showMediaErrorToasts) {
            mainScope.launch {
                val errorMessage = throwable.localizedMessage ?: "Photo loading error occurred"
                ToastHelper.show(context, errorMessage)
            }
        }

        onPlayerError()
    }

    private fun updateDualLayout() {
        if (!isDualPortraitMode) {
            foregroundImageView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            secondImageView.visibility = GONE
            return
        }

        val width = resolveTargetSize().first
        val halfWidth = width / 2
        
        foregroundImageView.layoutParams = LayoutParams(halfWidth, LayoutParams.MATCH_PARENT).apply {
            gravity = android.view.Gravity.START
        }
        foregroundImageView.scaleType = ImageView.ScaleType.FIT_CENTER
        
        secondImageView.layoutParams = LayoutParams(halfWidth, LayoutParams.MATCH_PARENT).apply {
            gravity = android.view.Gravity.END
        }
        secondImageView.scaleType = ImageView.ScaleType.FIT_CENTER
    }

    fun stop() {
        removeCallbacks(finishedRunnable)
        foregroundImageView.setImageBitmap(null)
        secondImageView.setImageBitmap(null)
        secondImageView.visibility = GONE
        isDualPortraitMode = false
        pausedTimestamp = 0
        remainingDuration = 0
        blurHelper.cancel()
    }

    fun pauseTimer() {
        pausedTimestamp = System.currentTimeMillis()
        removeCallbacks(finishedRunnable)
    }

    fun resumeTimer(pauseDuration: Long) {
        if (pausedTimestamp > 0) {
            remainingDuration = maxOf(0, remainingDuration - pauseDuration)
            if (remainingDuration > 0) {
                postDelayed(finishedRunnable, remainingDuration)
            } else {
                // If time has expired, finish immediately
                listener?.onImageFinished()
            }
            pausedTimestamp = 0
        }
    }

    private fun setScaleMode(
        width: Int,
        height: Int,
    ) {
        val photoAR = if (height > 0) width.toFloat() / height.toFloat() else 1f
        val screenAR = resolveScreenAspectRatio()
        val isMismatch = kotlin.math.abs(photoAR - screenAR) > 0.1f

        if (isMismatch) {
            Timber.i("Aspect ratio mismatch (Photo: $photoAR, Screen: $screenAR), forcing FIT_CENTER")
            foregroundImageView.scaleType = ImageView.ScaleType.FIT_CENTER
        } else {
            val aspect = AspectRatio.fromDimensions(width, height)
            Timber.i("Aspect ratio matches (Photo: $photoAR, Screen: $screenAR): $aspect")
            foregroundImageView.scaleType =
                when (aspect) {
                    AspectRatio.SQUARE, AspectRatio.PORTRAIT -> getScaleType(GeneralPrefs.photoScalePortrait)
                    AspectRatio.LANDSCAPE -> getScaleType(GeneralPrefs.photoScaleLandscape)
                }
        }
    }

    private fun getScaleType(scale: PhotoScale?): ImageView.ScaleType =
        try {
            ImageView.ScaleType.valueOf(scale.toString())
        } catch (e: Exception) {
            Timber.e(e)
            ImageView.ScaleType.valueOf(PhotoScale.CENTER_CROP.toString())
        }

    private fun setupFinishedRunnable() {
        removeCallbacks(finishedRunnable)
        val token = blurHelper.currentToken
        if (backgroundReadyToken == token) {
            runSetupFinishedRunnable()
        } else {
            pendingSetupToken = token
        }
    }

    private fun runSetupFinishedRunnable() {
        listener?.onImagePrepared()

        val duration = GeneralPrefs.slideshowSpeed.toLong() * 1000
        val fadeDuration = GeneralPrefs.mediaFadeOutDuration.toLong()
        val durationMinusFade = duration - fadeDuration

        totalDuration = duration
        remainingDuration = durationMinusFade

        Timber.i("Delay: ${durationMinusFade.milliseconds}")
        if (progressBar) GlobalBus.post(ProgressBarEvent(ProgressState.START, 0, duration))
        postDelayed(finishedRunnable, durationMinusFade)
    }

    private fun markBackgroundReady(token: Long) {
        backgroundReadyToken = token
        if (pendingSetupToken == token) {
            pendingSetupToken = -1
            runSetupFinishedRunnable()
        }
    }

    private fun onPlayerError() {
        removeCallbacks(finishedRunnable)
        postDelayed(errorRunnable, ScreenController.ERROR_DELAY)
    }

    fun setOnPlayerListener(listener: ScreenController) {
        this.listener = listener
    }

    interface OnImagePlayerEventListener {
        fun onImageFinished()

        fun onImageError()

        fun onImagePrepared()

        fun onImageOrientationDetected(isPortrait: Boolean)
    }
}
