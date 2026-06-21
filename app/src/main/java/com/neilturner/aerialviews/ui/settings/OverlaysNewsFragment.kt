package com.neilturner.aerialviews.ui.settings

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import com.neilturner.aerialviews.R
import com.neilturner.aerialviews.models.prefs.GeneralPrefs
import com.neilturner.aerialviews.utils.DialogHelper
import com.neilturner.aerialviews.utils.FirebaseHelper
import com.neilturner.aerialviews.utils.MenuStateFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OverlaysNewsFragment : MenuStateFragment() {

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        setPreferencesFromResource(R.xml.settings_overlays_news, rootKey)
    }

    override fun onResume() {
        super.onResume()
        FirebaseHelper.analyticsScreenView("NewsOverlay", this)
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference.key == "home_assistant_test_connection") {
            lifecycleScope.launch {
                testHomeAssistantConnection()
            }
            return true
        }
        return super.onPreferenceTreeClick(preference)
    }

    private suspend fun testHomeAssistantConnection() = withContext(Dispatchers.IO) {
        val rawUrl = GeneralPrefs.homeAssistantUrl.trim().removeSuffix("/")
        val token = GeneralPrefs.homeAssistantToken.trim()

        if (rawUrl.isEmpty()) {
            DialogHelper.showOnMain(requireContext(), getString(R.string.home_assistant_test_results_title), "Error: Home Assistant Server URL is empty.")
            return@withContext
        }
        if (token.isEmpty()) {
            DialogHelper.showOnMain(requireContext(), getString(R.string.home_assistant_test_results_title), "Error: Home Assistant Access Token is empty.")
            return@withContext
        }

        // Show progress dialog on main thread
        val progressDialog = withContext(Dispatchers.Main) {
            val dialog = DialogHelper.progressDialog(requireContext(), "Connecting to $rawUrl...")
            dialog.show()
            dialog
        }

        try {
            val client = okhttp3.OkHttpClient()
            val request = okhttp3.Request.Builder()
                .url("$rawUrl/api/states")
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                // Dismiss progress dialog
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                }

                if (!response.isSuccessful) {
                    DialogHelper.showOnMain(
                        requireContext(),
                        getString(R.string.home_assistant_test_results_title),
                        "Failed to connect. HTTP Status Code: ${response.code}\n\nPlease check your URL and Access Token."
                    )
                    return@withContext
                }

                val body = response.body.string()
                val states = com.neilturner.aerialviews.utils.JsonHelper.json.decodeFromString<List<com.neilturner.aerialviews.services.HaState>>(body)

                val nextPrayerEntity = states.firstOrNull { it.entity_id.lowercase().contains("next_prayer") }
                val nextPrayerName = nextPrayerEntity?.state?.lowercase()?.trim()

                val prayerKeys = listOf("fajr", "dhuhr", "asr", "maghrib", "isha")

                // Local fallback next prayer calculation if HASS next_prayer sensor is unavailable/unknown
                var calculatedNextKey: String? = null
                val nextIsFallback = nextPrayerName == null || nextPrayerName == "unavailable" || nextPrayerName == "unknown"
                if (nextIsFallback) {
                    val now = System.currentTimeMillis()
                    var minDiff = Long.MAX_VALUE
                    for (key in prayerKeys) {
                        val entity = states.firstOrNull { 
                            val id = it.entity_id.lowercase()
                            id.startsWith("sensor.islamic_prayer_times_") && id.endsWith("_prayer") && id.contains(key)
                        }
                        if (entity != null) {
                            try {
                                val timeMs = com.neilturner.aerialviews.services.NewsService.parseIsoToMillis(entity.state)
                                if (timeMs > now) {
                                    val diff = timeMs - now
                                    if (diff < minDiff) {
                                        minDiff = diff
                                        calculatedNextKey = key
                                    }
                                }
                            } catch (e: Exception) {
                                // ignore
                            }
                        }
                    }
                    if (calculatedNextKey == null) {
                        calculatedNextKey = "fajr" // Next prayer tomorrow
                    }
                }

                val foundPrayers = mutableListOf<String>()
                for (key in prayerKeys) {
                    val entity = states.firstOrNull { 
                        val id = it.entity_id.lowercase()
                        id.startsWith("sensor.islamic_prayer_times_") && id.endsWith("_prayer") && id.contains(key)
                    }
                    if (entity != null) {
                        val isNext = if (!nextIsFallback) {
                            nextPrayerName?.contains(key) == true
                        } else {
                            key == calculatedNextKey
                        }
                        val isNextMark = if (isNext) " (Next)" else ""
                        val localTimeFormatted = com.neilturner.aerialviews.services.NewsService.formatPrayerTime(entity.state)
                        foundPrayers.add("• ${key.uppercase()}: $localTimeFormatted$isNextMark")
                    }
                }

                // Hijri Date
                val hijriEntity = states.firstOrNull { it.entity_id.lowercase() == "sensor.kt_hijri_date" }
                    ?: states.firstOrNull { it.entity_id.lowercase() == "sensor.hijri_date" }
                val hijriStr = if (hijriEntity != null) {
                    "Hijri Date: ${hijriEntity.state}"
                } else {
                    "Hijri Date: Not Found"
                }

                val nextDisplay = if (nextIsFallback) {
                    "${calculatedNextKey?.uppercase()} (Calculated locally)"
                } else {
                    nextPrayerEntity?.state ?: "Not Found"
                }

                val resultsMessage = if (foundPrayers.isNotEmpty()) {
                    """
                        Connection Successful!
                        
                        $hijriStr
                        
                        Prayer Times Found (Localized):
                        ${foundPrayers.joinToString("\n")}
                        
                        Next/Upcoming Prayer: $nextDisplay
                    """.trimIndent()
                } else {
                    """
                        Connection Successful!
                        
                        $hijriStr
                        
                        No prayer sensors found in your Home Assistant.
                        Please verify that you have configured the Islamic Prayer Times integration.
                    """.trimIndent()
                }

                DialogHelper.showOnMain(requireContext(), getString(R.string.home_assistant_test_results_title), resultsMessage)
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
            }
            DialogHelper.showOnMain(
                requireContext(),
                getString(R.string.home_assistant_test_results_title),
                "Error connecting to Home Assistant:\n\n${e.localizedMessage ?: e.message ?: "Unknown error"}\n\nPlease verify that your device has network access to the Home Assistant URL."
            )
        }
    }
}
