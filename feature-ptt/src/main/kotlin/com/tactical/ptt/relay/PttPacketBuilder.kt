package com.tactical.ptt.relay

import com.tactical.domain.packet.TextPacket
import com.tactical.domain.speech.TranscriptionChunk
import com.tactical.ptt.session.PttSession

private const val PTT_PAUSE_MARKER = "\u2063"

/**
 * Constructs a TextPacket from session metadata and a finalized
 * transcription chunk.
 *
 * Optional spoken identity framing is applied only to manual PTT sessions.
 * Call Mode is passed through unchanged because its session is marked
 * isCallMode=true.
 */
class PttPacketBuilder(
    private val spokenNameProvider: (languageCode: String) -> String = { "" },
    private val spokenNameEnabledProvider: () -> Boolean = { false }
) {
    fun build(session: PttSession, chunk: TranscriptionChunk): TextPacket {
        val originalText = chunk.text.trim()
        val text = if (
            !session.isCallMode &&
            spokenNameEnabledProvider() &&
            spokenNameProvider(chunk.languageCode).isNotBlank()
        ) {
            val name = spokenNameProvider(chunk.languageCode).trim()
            val (inWord, outWord) = radioProcedureWords(chunk.languageCode)

            // This invisible separator is consumed by the receiving TTS
            // playback coordinator and becomes a real audio gap. It is
            // intentionally invisible in the chat/notification UI.
            "$name, $inWord$PTT_PAUSE_MARKER$originalText$PTT_PAUSE_MARKER$name, $outWord."
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

    companion object {
        const val PTT_PAUSE_MARKER = "\u2063"

        /**
         * Removes the internal pause marker for UI/storage text while keeping
         * a normal word boundary where the marker was placed.
         */
        fun toDisplayText(text: String): String =
            text.replace(PTT_PAUSE_MARKER, " ")
                .replace(Regex("\\s+"), " ")
                .trim()
    }
}
