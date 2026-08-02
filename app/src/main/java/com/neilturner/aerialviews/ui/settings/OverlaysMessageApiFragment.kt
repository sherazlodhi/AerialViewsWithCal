package com.neilturner.aerialviews.ui.settings

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreference
import com.neilturner.aerialviews.R
import com.neilturner.aerialviews.models.prefs.GeneralPrefs
import com.neilturner.aerialviews.services.MessageOverlayService
import com.neilturner.aerialviews.utils.DeviceIPHelper
import com.neilturner.aerialviews.utils.FirebaseHelper
import com.neilturner.aerialviews.utils.MenuStateFragment

class OverlaysMessageApiFragment : MenuStateFragment() {
    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        setPreferencesFromResource(R.xml.settings_overlays_message_api, rootKey)
    }

    override fun onResume() {
        super.onResume()
        FirebaseHelper.analyticsScreenView("Message API", this)

        updateIPAddressDisplay()
        limitTextInput()
        setupAlwaysOnOverlayToggle()

        // If already enabled, make sure the service is running
        if (GeneralPrefs.messageApiEnabled && GeneralPrefs.messageApiAlwaysOnOverlay) {
            MessageOverlayService.startIfEnabled(requireContext())
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference.key.isNullOrEmpty()) {
            return super.onPreferenceTreeClick(preference)
        }
        return super.onPreferenceTreeClick(preference)
    }

    private fun setupAlwaysOnOverlayToggle() {
        val alwaysOnPref = findPreference<SwitchPreference>("message_api_always_on_overlay") ?: return

        alwaysOnPref.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            if (enabled) {
                MessageOverlayService.startIfEnabled(requireContext())
            } else {
                MessageOverlayService.stop(requireContext())
            }
            true
        }
    }

    private fun updateIPAddressDisplay() {
        val ipPreference = findPreference<Preference>("message_api_current_ip")

        if (ipPreference != null) {
            val ipAddress = DeviceIPHelper.getIPAddress(requireContext())
            val port = GeneralPrefs.messageApiPort

            // ipPreference.summary = "Device IP: $ipAddress\nAPI URL: http://$ipAddress:$port"
            ipPreference.summary = "API URL: http://$ipAddress:$port"
        }
    }

    private fun limitTextInput() {
        preferenceScreen.findPreference<EditTextPreference>("message_api_port")?.setOnBindEditTextListener { editText ->
            editText.setSingleLine()
            editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
    }
}
