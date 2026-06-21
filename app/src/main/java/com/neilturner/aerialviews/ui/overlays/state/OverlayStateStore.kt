package com.neilturner.aerialviews.ui.overlays.state

import com.neilturner.aerialviews.models.enums.MetadataType
import com.neilturner.aerialviews.models.enums.OverlayType
import com.neilturner.aerialviews.services.MusicEvent
import com.neilturner.aerialviews.services.CalendarEvent
import com.neilturner.aerialviews.services.NewsItem
import com.neilturner.aerialviews.services.StockItem
import com.neilturner.aerialviews.services.HomeStatusItem
import com.neilturner.aerialviews.services.weather.ForecastEvent
import com.neilturner.aerialviews.services.weather.WeatherEvent
import com.neilturner.aerialviews.ui.overlays.ProgressState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class OverlayStateStore {
    private val _uiState = MutableStateFlow(OverlayUiState())
    val uiState: StateFlow<OverlayUiState> = _uiState.asStateFlow()

    fun setMetadata(
        type: OverlayType,
        text: String,
        poi: Map<Int, String>,
        metadataType: MetadataType,
    ) {
        _uiState.update {
            it.copy(
                metadata =
                    it.metadata.toMutableMap().apply {
                        put(
                            type,
                            MetadataOverlayState(
                                text = text,
                                poi = poi,
                                metadataType = metadataType,
                            ),
                        )
                    },
            )
        }
    }

    fun setMessage(
        type: OverlayType,
        state: MessageOverlayState,
    ) {
        val rawText = state.text ?: ""
        val shouldAnnounce = rawText.contains("#Announce#", ignoreCase = true)

        // Command: #AllClear# - Purge all messages for this type
        if (rawText.contains("#AllClear#", ignoreCase = true)) {
            _uiState.update {
                it.copy(message = it.message.toMutableMap().apply { remove(type) })
            }
            return
        }

        // Clean up text
        val processedText = rawText.replace("#Announce#", "", ignoreCase = true).trim()

        _uiState.update {
            val existing = it.message[type]
            val newState = if (state.append && existing != null) {
                // If appending, combine text with a bullet separator
                state.copy(text = existing.text + " • " + processedText)
            } else {
                state.copy(text = processedText)
            }

            // High-priority: Verbalize the ENTIRE current ticker content
            if (shouldAnnounce) {
                val fullSpeechText = newState.text.replace("•", " ").trim()
                if (fullSpeechText.isNotEmpty()) {
                    me.kosert.flowbus.GlobalBus.post(com.neilturner.aerialviews.services.SpeechEvent(fullSpeechText))
                }
            }

            it.copy(message = it.message.toMutableMap().apply { put(type, newState) })
        }
    }

    fun setNowPlaying(event: MusicEvent) {
        _uiState.update { it.copy(nowPlaying = NowPlayingOverlayState(event)) }
    }

    fun setWeather(event: WeatherEvent) {
        _uiState.update { it.copy(weather = WeatherOverlayState(event)) }
    }

    fun setForecast(event: ForecastEvent) {
        _uiState.update { it.copy(forecast = ForecastOverlayState(event)) }
    }

    fun setCalendar(events: List<CalendarEvent>) {
        _uiState.update { it.copy(calendar = CalendarOverlayState(events)) }
    }

    fun setNews(news: List<NewsItem>, stocks: List<StockItem>, homeStatus: List<HomeStatusItem> = emptyList()) {
        _uiState.update { it.copy(news = NewsOverlayState(news, stocks, homeStatus)) }
    }

    fun setProgress(
        state: ProgressState,
        position: Long = 0,
        duration: Long = 0,
    ) {
        _uiState.update {
            it.copy(
                progress =
                    ProgressOverlayState(
                        state = state,
                        position = position,
                        duration = duration,
                    ),
            )
        }
    }

    fun resetForNextMedia() {
        _uiState.update {
            it.copy(
                metadata = emptyMap(),
                progress = ProgressOverlayState(),
            )
        }
    }
}
