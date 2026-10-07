package com.tactical.app.di

import android.icu.text.Transliterator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Converts a user-entered Latin first name into the target speech script so
 * the language-specific TTS tokenizer can actually pronounce the name.
 *
 * English stays unchanged. If ICU does not support a requested transliteration
 * on a device, the original name is returned rather than breaking PTT.
 */
@Singleton
class VoiceNameLocalizer @Inject constructor() {

    fun toSpeechName(name: String, languageCode: String): String {
        val cleaned = name.trim()
        if (cleaned.isBlank()) return cleaned

        val transliteratorId = when (languageCode.lowercase()) {
            "hi", "mr" -> "Latin-Devanagari"
            "gu" -> "Latin-Gujarati"
            "kn" -> "Latin-Kannada"
            "ml" -> "Latin-Malayalam"
            "ta" -> "Latin-Tamil"
            "te" -> "Latin-Telugu"
            "or" -> "Latin-Odia"
            "bn" -> "Latin-Bengali"
            else -> return cleaned
        }

        return runCatching {
            Transliterator.createInstance(transliteratorId)
                .transliterate(cleaned)
                .trim()
                .ifBlank { cleaned }
        }.getOrElse {
            cleaned
        }
    }
}
