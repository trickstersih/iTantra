package com.tactical.platform.speech

import com.tactical.domain.audio.AudioFrame
import com.tactical.domain.speech.TranscriptionChunk
import com.tactical.platform.api.speech.SpeechToText
import com.tactical.platform.speech.andr2.Andr2SpeechToText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Outgoing STT router for the single bundled andr2 multilingual model.
 *
 * The selected language only conditions the decoder prefix; changing language
 * does not reload the shared ONNX model.
 */
@Singleton
class RoutingSpeechToText @Inject constructor(
    private val andr2SpeechToText: Andr2SpeechToText,
    private val languagePreferences: SpeechLanguagePreferences
) : SpeechToText {

    fun onSelectedLanguageChanged(@Suppress("UNUSED_PARAMETER") languageCode: String) {
        // andr2 is one shared multilingual model. The next inference reads
        // the currently persisted language when it builds the decoder prefix.
        andr2SpeechToText.onSelectedLanguageChanged()
    }

    suspend fun preloadSelectedLanguage() {
        val selectedLanguage = languagePreferences.selectedLanguageCode
        require(selectedLanguage in SUPPORTED_LANGUAGE_CODES) {
            "No andr2 STT backend configured for language " + selectedLanguage
        }
        andr2SpeechToText.preloadSelectedLanguage()
    }

    override fun transcribe(
        audio: Flow<AudioFrame>
    ): Flow<TranscriptionChunk> = flow {
        val selectedLanguage = languagePreferences.selectedLanguageCode
        require(selectedLanguage in SUPPORTED_LANGUAGE_CODES) {
            "No andr2 STT backend configured for language " + selectedLanguage
        }

        emitAll(andr2SpeechToText.transcribe(audio))
    }

    companion object {
        private val SUPPORTED_LANGUAGE_CODES = setOf(
            "hi", "gu", "mr", "kn", "ml",
            "ta", "te", "or", "bn", "en"
        )
    }
}
