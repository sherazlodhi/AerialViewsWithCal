package com.neilturner.aerialviews.models

import com.neilturner.aerialviews.models.prefs.GeneralPrefs
import com.neilturner.aerialviews.models.videos.AerialMedia
import timber.log.Timber

/**
 * Manages the playback order and current position in the media library.
 * This implementation persists the current position and shuffle order (via seed)
 * across app restarts to ensure a "truly random" experience where all items
 * are shown before repeating.
 */
class MediaPlaylist(
    private val _videos: List<AerialMedia>,
    initialPosition: Int = GeneralPrefs.playlistPosition,
) {
    private var position = initialPosition

    val size: Int = _videos.size

    fun nextItem(): AerialMedia {
        if (_videos.isEmpty()) throw NoSuchElementException("Playlist is empty")
        
        val next = position + 1
        if (next >= _videos.size) {
            // Full cycle completed. Cycle the shuffle seed for the NEXT time the playlist is fetched.
            // In the current session, we wrap back to index 0 of the existing shuffle.
            if (GeneralPrefs.shuffleVideos) {
                GeneralPrefs.playlistShuffleSeed = System.currentTimeMillis()
                Timber.i("Playlist cycle completed. Set new shuffle seed for the next run.")
            }
            position = 0
        } else {
            position = next
        }
        
        GeneralPrefs.playlistPosition = position
        return _videos[position]
    }

    fun peekNextItem(): AerialMedia? {
        if (_videos.isEmpty()) return null
        val nextPos = calculateNext(position + 1)
        return _videos[nextPos]
    }

    fun previousItem(): AerialMedia {
        if (_videos.isEmpty()) throw NoSuchElementException("Playlist is empty")
        
        val prev = position - 1
        if (prev < 0) {
            position = _videos.size - 1
        } else {
            position = prev
        }
        
        GeneralPrefs.playlistPosition = position
        return _videos[position]
    }

    private fun calculateNext(number: Int): Int {
        if (_videos.isEmpty()) return 0
        return if (number < 0) {
            _videos.size + (number % _videos.size)
        } else {
            number % _videos.size
        }
    }
}
