package com.tactical.app.di

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UiLanguagePreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    val selectedLanguageCode: String
        get() = preferences.getString(KEY_LANGUAGE_CODE, DEFAULT_LANGUAGE_CODE)
            ?.takeIf { it in SUPPORTED_LANGUAGE_CODES }
            ?: DEFAULT_LANGUAGE_CODE

    private val preferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun setSelectedLanguageCode(languageCode: String) {
        require(languageCode in SUPPORTED_LANGUAGE_CODES)
        preferences.edit()
            .putString(KEY_LANGUAGE_CODE, languageCode)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "ui_language_preferences"
        private const val KEY_LANGUAGE_CODE = "ui_language_code"
        private const val DEFAULT_LANGUAGE_CODE = "en"
        private val SUPPORTED_LANGUAGE_CODES = setOf(
            "en", "hi", "gu", "mr", "kn", "ml",
            "ta", "te", "or", "bn"
        )
    }
}
