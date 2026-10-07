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
            "$name in — $originalText — $name out"
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
}
