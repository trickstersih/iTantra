package com.tactical.ptt.relay

import com.tactical.domain.packet.TextPacket
import com.tactical.domain.speech.TranscriptionChunk
import com.tactical.ptt.session.PttSession

/**
 * Constructs a TextPacket from session metadata and a finalized
 * transcription chunk.
 *
 * Optional spoken identity framing is applied only to manual PTT sessions.
 * Call Mode is passed through unchanged because its session is marked
 * isCallMode=true.
 */
class PttPacketBuilder(
    private val spokenNameProvider: () -> String = { "" },
    private val spokenNameEnabledProvider: () -> Boolean = { false }
) {
    fun build(session: PttSession, chunk: TranscriptionChunk): TextPacket {
        val originalText = chunk.text.trim()
        val text = if (
            !session.isCallMode &&
            spokenNameEnabledProvider() &&
            spokenNameProvider().isNotBlank()
        ) {
            val name = spokenNameProvider().trim()
            val (inWord, outWord) = radioProcedureWords(chunk.languageCode)
            // Use punctuation as an intentional TTS pause. The framing
            // words themselves are transliterated into the selected script
            // so the receiving language-specific TTS model pronounces them
            // as the radio words "in" and "out".
            "$name, $inWord... $originalText ... $name, $outWord."
        } else {
            chunk.text
        }

        return TextPacket(
            sender = session.deviceId,
            text = text,
            languageCode = chunk.languageCode,
            timestamp = System.currentTimeMillis(),
            isCallMode = session.isCallMode
        )
    }

    /**
     * Uses the selected/transmitted speech language for the radio procedure
     * words so a language-specific STT backend can recognize them as part of
     * the same utterance.
     */
    private fun radioProcedureWords(languageCode: String): Pair<String, String> =
        when (languageCode.lowercase()) {
            "hi" -> "इन" to "आउट"
            "gu" -> "ઇન" to "આઉટ"
            "mr" -> "इन" to "आउट"
            "kn" -> "ಇನ್" to "ಔಟ್"
            "ml" -> "ഇൻ" to "ഔട്ട്"
            "ta" -> "இன்" to "அவுட்"
            "te" -> "ఇన్" to "అవుట్"
            "or" -> "ଇନ୍" to "ଆଉଟ୍"
            "bn" -> "ইন" to "আউট"
            else -> "in" to "out"
        }
}
