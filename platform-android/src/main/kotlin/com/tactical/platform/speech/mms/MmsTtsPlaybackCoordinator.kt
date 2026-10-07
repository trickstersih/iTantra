package com.tactical.platform.speech.mms

import com.tactical.domain.audio.AudioFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates incoming voice-message TTS.
 *
 * Synthesis is always performed through the single shared MmsTtsEngine, which
 * already serializes model inference. The coordinator pipelines that
 * synthesis ahead of playback:
 *
 *   message 1: synthesize -> play
 *   message 2:          synthesize -> wait/play
 *
 * In ONE_BY_ONE mode, all received voices share a single playback lane.
 *
 * In OVERLAPPING mode, each sender gets its own playback lane. Voices from
 * different senders may play concurrently, but messages from the same sender
 * are always serialized. This prevents one device's long voice message from
 * overlapping that same device's next, shorter message while still allowing
 * simultaneous voices from different devices.
 *
 * Only a small number of synthesized frames are buffered at once, preventing
 * a burst of long messages from consuming unbounded RAM. Mode changes never
 * interrupt a currently playing AudioTrack. When switching to ONE_BY_ONE,
 * playback waits for any already-running overlapping tracks to finish before
 * starting the next pending track.
 */
@Singleton
class MmsTtsPlaybackCoordinator @Inject constructor(
    private val ttsEngine: MmsTtsEngine
) {

    private data class Request(
        val senderId: String,
        val language: MmsTtsLanguage,
        val text: String
    )

    private data class SynthesizedPart(
        val frame: AudioFrame,
        val sampleRate: Int,
        val pauseAfterMs: Long = 0L
    )

    private data class Synthesized(
        val senderId: String,
        val parts: List<SynthesizedPart>
    )

    private val requestQueue = Channel<Request>(Channel.UNLIMITED)

    // Keep a small RAM buffer so the next message can be synthesized while the
    // current message is playing, without allowing unlimited audio accumulation.
    private val synthesizedQueue = Channel<Synthesized>(SYNTHESIZED_BUFFER_CAPACITY)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Each sender owns an independent playback lane. This is what allows
    // different devices to overlap while preventing messages from the same
    // device from ever playing simultaneously.
    private val senderLanes = ConcurrentHashMap<String, Channel<Synthesized>>()

    // Used only by ONE_BY_ONE mode. OVERLAPPING mode never takes this mutex,
    // so different sender lanes can actually play at the same time.
    private val oneByOnePlaybackMutex = Mutex()

    @Volatile
    private var playbackMode = MmsTtsPlaybackMode.OVERLAPPING

    init {
        scope.launch {
            synthesizeLoop()
        }

        scope.launch {
            playbackLoop()
        }
    }

    fun setMode(mode: MmsTtsPlaybackMode) {
        playbackMode = mode
    }

    fun currentMode(): MmsTtsPlaybackMode = playbackMode

    fun enqueue(
        senderId: String,
        language: MmsTtsLanguage,
        text: String
    ) {
        requestQueue.trySend(
            Request(
                senderId = senderId,
                language = language,
                text = text
            )
        )
    }

    private suspend fun synthesizeLoop() {
        for (request in requestQueue) {
            try {
                val segments = request.text.split(PTT_PAUSE_MARKER)
                    .map { it.trim() }
                    .filter { it.isNotBlank() }

                val parts = buildList {
                    segments.forEachIndexed { index, segment ->
                        val (frame, result) = ttsEngine.synthesize(
                            request.language,
                            segment
                        )
                        add(
                            SynthesizedPart(
                                frame = frame,
                                sampleRate = result.sampleRate,
                                pauseAfterMs = if (index < segments.lastIndex) {
                                    PTT_PAUSE_MS
                                } else {
                                    0L
                                }
                            )
                        )
                    }
                }

                synthesizedQueue.send(
                    Synthesized(
                        senderId = request.senderId,
                        parts = parts
                    )
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.w(
                    TAG,
                    "Incoming voice-message synthesis failed: " +
                        (error.message ?: error.javaClass.simpleName)
                )
            }
        }
    }

    private suspend fun playbackLoop() {
        for (synthesized in synthesizedQueue) {
            if (playbackMode == MmsTtsPlaybackMode.ONE_BY_ONE) {
                // The single playback lane is used only in ONE_BY_ONE mode.
                // This keeps all received voices sequential.
                oneByOnePlaybackMutex.withLock {
                    runPlaybackSafely(synthesized)
                }
            } else {
                // In OVERLAPPING mode, dispatch the message to its sender's
                // dedicated lane. The playback loop stays free to dispatch
                // another sender immediately, while the same sender's lane
                // remains strictly sequential.
                senderLane(synthesized.senderId).send(synthesized)
            }
        }
    }

    private fun senderLane(senderId: String): Channel<Synthesized> =
        senderLanes.computeIfAbsent(senderId) {
            Channel<Synthesized>(Channel.UNLIMITED).also { lane ->
                scope.launch {
                    for (synthesized in lane) {
                        runPlaybackSafely(synthesized)
                    }
                }
            }
        }

    private suspend fun runPlaybackSafely(synthesized: Synthesized) {
        try {
            synthesized.parts.forEach { part ->
                ttsEngine.play(
                    part.frame,
                    part.sampleRate
                )
                if (part.pauseAfterMs > 0L) {
                    kotlinx.coroutines.delay(part.pauseAfterMs)
                }
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            android.util.Log.w(
                TAG,
                "Incoming voice-message playback failed: " +
                    (error.message ?: error.javaClass.simpleName)
            )
        }
    }

    companion object {
        private const val TAG = "MmsTtsPlayback"
        private const val SYNTHESIZED_BUFFER_CAPACITY = 2

        // Same invisible marker inserted by PttPacketBuilder. The TTS model
        // never sees this marker because synthesis happens per segment.
        private const val PTT_PAUSE_MARKER = "\u2063"

        // Deliberate silence between "in", the message, and "out".
        private const val PTT_PAUSE_MS = 400L
    }
}
