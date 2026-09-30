package com.tactical.platform.speech

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the language selected for outgoing push-to-talk speech.
 *
 * Receiving is intentionally independent of this preference: incoming
 * TextPacket.languageCode determines the language used for received speech.
 */
@Singleton
class SpeechLanguagePreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    val selectedLanguageCode: String
        get() = preferences.getString(KEY_LANGUAGE_CODE, DEFAULT_LANGUAGE_CODE)
            ?: DEFAULT_LANGUAGE_CODE

    fun setSelectedLanguageCode(languageCode: String) {
        require(languageCode in SUPPORTED_LANGUAGE_CODES) {
            "Unsupported outgoing speech language: $languageCode"
        }
        preferences.edit()
            .putString(KEY_LANGUAGE_CODE, languageCode)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "speech_preferences"
        private const val KEY_LANGUAGE_CODE = "outgoing_language_code"
        private const val DEFAULT_LANGUAGE_CODE = "hi"

        private val SUPPORTED_LANGUAGE_CODES = setOf(
            "hi",
            "gu",
            "mr",
            "kn",
            "ml",
            "ta",
            "te",
            "or",
            "bn",
            "en"
        )
    }
}
