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
            "$name $inWord — $originalText — $name $outWord"
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
            "hi" -> "अंदर" to "बाहर"
            "gu" -> "અંદર" to "બહાર"
            "mr" -> "आत" to "बाहेर"
            "kn" -> "ಒಳಗೆ" to "ಹೊರಗೆ"
            "ml" -> "അകത്ത്" to "പുറത്ത്"
            "ta" -> "உள்ளே" to "வெளியே"
            "te" -> "లోపల" to "బయట"
            "or" -> "ଭିତରେ" to "ବାହାରେ"
            "bn" -> "ভিতরে" to "বাইরে"
            else -> "in" to "out"
        }
}
