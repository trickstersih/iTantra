package com.tactical.app.di

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores the optional spoken PTT identity framing.
 *
 * This is separate from the network callsign because the spoken name may be
 * a first name while the callsign remains the device/network identity.
 */
@Singleton
class VoiceIdentityPreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    val spokenName: String
        get() = preferences.getString(KEY_SPOKEN_NAME, "").orEmpty()

    val isEnabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, false)

    fun setSpokenName(value: String) {
        preferences.edit()
            .putString(KEY_SPOKEN_NAME, value.trim())
            .apply()
    }

    fun setEnabled(enabled: Boolean) {
        preferences.edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "voice_identity_preferences"
        private const val KEY_SPOKEN_NAME = "spoken_name"
        private const val KEY_ENABLED = "spoken_name_enabled"
    }
}
