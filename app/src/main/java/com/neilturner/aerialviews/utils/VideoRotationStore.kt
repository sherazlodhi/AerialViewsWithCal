package com.neilturner.aerialviews.utils

import android.content.Context
import android.net.Uri

/**
 * Stores and retrieves manual video rotation overrides for specific video URIs.
 * Keyed by URI string. Values are 0, 90, 180, or 270.
 */
object VideoRotationStore {
    private const val PREFS_NAME = "video_rotation_prefs"

    fun getRotation(context: Context, uri: Uri): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(uri.toString(), -1)
    }

    fun saveRotation(context: Context, uri: Uri, degrees: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(uri.toString(), degrees).apply()
    }

    fun clearRotation(context: Context, uri: Uri) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(uri.toString()).apply()
    }
}
