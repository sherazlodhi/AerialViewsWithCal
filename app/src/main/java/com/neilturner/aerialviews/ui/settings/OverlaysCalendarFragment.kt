package com.neilturner.aerialviews.ui.settings

import android.Manifest
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreference
import com.neilturner.aerialviews.R
import com.neilturner.aerialviews.utils.FirebaseHelper
import com.neilturner.aerialviews.utils.MenuStateFragment
import com.neilturner.aerialviews.utils.PermissionHelper

class OverlaysCalendarFragment :
    MenuStateFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var requestPermissionLauncher: ActivityResultLauncher<String>

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        setPreferencesFromResource(R.xml.settings_overlays_calendar, rootKey)
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)

        requestPermissionLauncher =
            registerForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { isGranted: Boolean ->
                if (!isGranted) {
                    disableCalendarPreference()
                }
            }
    }

    override fun onDestroy() {
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(
        sharedPreferences: SharedPreferences,
        key: String?,
    ) {
        if (key == "calendar_enabled" && sharedPreferences.getBoolean(key, false)) {
            checkForCalendarPermission()
        }
    }

    private fun checkForCalendarPermission() {
        if (PermissionHelper.hasCalendarPermission(requireContext())) {
            return
        }
        requestPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
    }

    private fun disableCalendarPreference() {
        val pref = findPreference<SwitchPreference>("calendar_enabled")
        pref?.isChecked = false
    }

    override fun onResume() {
        super.onResume()
        FirebaseHelper.analyticsScreenView("CalendarOverlay", this)
    }
}
