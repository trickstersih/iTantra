package com.tactical.platform.speech

import com.tactical.domain.audio.AudioFrame
import com.tactical.domain.speech.TranscriptionChunk
import com.tactical.platform.api.speech.SpeechToText
import com.tactical.platform.speech.andr2.Andr2SpeechToText
import com.tactical.platform.speech.english.EnglishConformerCtcSpeechToText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Outgoing STT router.
 *
 * English uses the dedicated NVIDIA NeMo Conformer CTC backend. All other
 * supported languages use the bundled andr2 multilingual backend.
 */

@Singleton
class RoutingSpeechToText @Inject constructor(
    private val andr2SpeechToText: Andr2SpeechToText,
    private val englishSpeechToText: EnglishConformerCtcSpeechToText,
    private val languagePreferences: SpeechLanguagePreferences
) : SpeechToText {

    fun onSelectedLanguageChanged(languageCode: String) {
        // Only keep the selected STT backend resident. This matters because
        // English and andr2 have independent ONNX sessions and can otherwise
        // unnecessarily double the native model memory footprint.
        if (languageCode == ENGLISH_LANGUAGE_CODE) {
            andr2SpeechToText.close()
        } else {
            englishSpeechToText.close()
            andr2SpeechToText.onSelectedLanguageChanged()
        }
    }

    suspend fun preloadSelectedLanguage() {
        val selectedLanguage = languagePreferences.selectedLanguageCode
        require(selectedLanguage in SUPPORTED_LANGUAGE_CODES) {
            "No STT backend configured for language " + selectedLanguage
        }

        if (selectedLanguage == ENGLISH_LANGUAGE_CODE) {
            englishSpeechToText.preload()
        } else {
            andr2SpeechToText.preloadSelectedLanguage()
        }
    }

    override fun transcribe(
        audio: Flow<AudioFrame>
    ): Flow<TranscriptionChunk> = flow {
        val selectedLanguage = languagePreferences.selectedLanguageCode
        require(selectedLanguage in SUPPORTED_LANGUAGE_CODES) {
            "No STT backend configured for language " + selectedLanguage
        }

        if (selectedLanguage == ENGLISH_LANGUAGE_CODE) {
            emitAll(englishSpeechToText.transcribe(audio))
        } else {
            emitAll(andr2SpeechToText.transcribe(audio))
        }
    }

    companion object {
        private const val ENGLISH_LANGUAGE_CODE = "en"

        private val SUPPORTED_LANGUAGE_CODES = setOf(
            "hi", "gu", "mr", "kn", "ml",
            "ta", "te", "or", "bn", "en"
        )
    }
}
