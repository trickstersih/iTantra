package com.tactical.ptt.controller

import com.tactical.domain.audio.AudioConfig
import com.tactical.domain.identity.DeviceId
import com.tactical.domain.result.TacticalResult
import com.tactical.platform.api.audio.AudioRecorder
import com.tactical.platform.api.speech.SpeechToText
import com.tactical.domain.speech.TranscriptionChunk
import com.tactical.ptt.feedback.PttHapticFeedback
import com.tactical.ptt.relay.PttMeshDispatcher
import com.tactical.ptt.relay.PttPacketBuilder
import com.tactical.ptt.session.PttSession
import com.tactical.ptt.session.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

class DefaultPttController(
    private val deviceId: DeviceId,
    private val audioRecorder: AudioRecorder,
    private val speechToText: SpeechToText,
    private val packetBuilder: PttPacketBuilder,
    private val meshDispatcher: PttMeshDispatcher,
    private val hapticFeedback: PttHapticFeedback,
    private val scope: CoroutineScope,
    private val audioConfig: AudioConfig = AudioConfig(),
    private val releaseGraceMs: Long = 5000L
) : PttController {

    private val _state = MutableStateFlow(PttState())
    override fun state(): StateFlow<PttState> = _state.asStateFlow()

    private var sessionJob: Job? = null
    private var transmissionJob: Job? = null
    private var currentSession: PttSession? = null

    override suspend fun press(isVox: Boolean) {
        if (_state.value.sessionState != SessionState.IDLE) return

        val session = PttSession(deviceId = deviceId, isVox = isVox)
        currentSession = session

        _state.update {
            it.copy(
                sessionState = SessionState.ARMED,
                sessionId = session.sessionId,
                lastTranscription = null,
                lastResult = null,
                continuousSession = false,
                transmissions = emptyList()
            )
        }
        hapticFeedback.onPress()

        _state.update { it.copy(sessionState = SessionState.RECORDING) }
        val frames = audioRecorder.start(audioConfig)

        sessionJob = scope.launch {
            try {
                var accumulatedText = ""
                var latestPartial = ""
                var languageCode = session.languageTag.isoCode

                speechToText.transcribe(frames).collect { chunk ->
                    if (chunk.languageCode.isNotBlank()) {
                        languageCode = chunk.languageCode
                    }

                    if (chunk.isFinal) {
                        val finalText = chunk.text.trim()
                        if (finalText.isNotBlank()) {
                            // STT backends may finalize a segment whenever the
                            // speaker pauses. PTT must keep recording until
                            // release, so accumulate finalized segments instead
                            // of transmitting the first one immediately.
                            accumulatedText = appendTranscript(
                                accumulatedText,
                                finalText
                            )
                        }
                        latestPartial = ""
                    } else {
                        latestPartial = chunk.text.trim()
                    }

                    val liveText = appendTranscript(
                        accumulatedText,
                        latestPartial
                    )
                    if (liveText.isNotBlank()) {
                        _state.update {
                            it.copy(lastTranscription = liveText)
                        }
                    }
                }

                // AudioRecorder.stop() closes the audio stream on PTT release.
                // Only now is the complete utterance transmitted.
                val completeText = accumulatedText.trim()
                if (completeText.isNotBlank()) {
                    val finalChunk = TranscriptionChunk(
                        text = completeText,
                        isFinal = true,
                        languageCode = languageCode
                    )
                    _state.update {
                        it.copy(
                            lastTranscription = completeText,
                            sessionState = SessionState.TRANSMITTING
                        )
                    }

                    // Do not keep BLE transmission inside the transcription
                    // job. release() is allowed to stop/cancel the recording
                    // job without cancelling a large fragmented transmission.
                    transmissionJob?.cancel()
                    transmissionJob = scope.launch {
                        try {
                            transmit(session, finalChunk)
                        } catch (t: Throwable) {
                            if (t is CancellationException) throw t
                            _state.update {
                                it.copy(
                                    lastResult = TacticalResult.Failure(
                                        t.message ?: t.javaClass.simpleName
                                    )
                                )
                            }
                            hapticFeedback.onTransmitFailed()
                        } finally {
                            _state.update { it.copy(sessionState = SessionState.IDLE) }
                        }
                    }
                } else {
                    println(
                        "[DefaultPttController] PTT ended with no transcription; no packet will be sent"
                    )
                    _state.update { it.copy(sessionState = SessionState.IDLE) }
                }
                currentSession = null
            } catch (t: Throwable) {
                println(
                    "[DefaultPttController] PTT transcription pipeline failed: " +
                        (t.message ?: t.javaClass.simpleName)
                )
                t.printStackTrace()
                _state.update {
                    it.copy(
                        sessionState = SessionState.IDLE,
                        lastResult = TacticalResult.Failure(
                            error = t.message ?: t.javaClass.simpleName
                        )
                    )
                }
                currentSession = null
            }
        }
    }

    override suspend fun startContinuous() {
        if (_state.value.sessionState != SessionState.IDLE) return

        val session = PttSession(
            deviceId = deviceId,
            isVox = true,
            isCallMode = true
        )
        currentSession = session

        _state.update {
            it.copy(
                sessionState = SessionState.RECORDING,
                sessionId = session.sessionId,
                lastTranscription = null,
                lastResult = null,
                continuousSession = true,
                transmissions = emptyList()
            )
        }
        hapticFeedback.onPress()

        // Call Mode uses Android's voice-communication capture profile so
        // incoming TTS is less likely to feed back into the microphone.
        val frames = audioRecorder.start(
            audioConfig.copy(voiceCommunication = true)
        )

        sessionJob = scope.launch {
            val sendQueue = Channel<PttTransmission>(Channel.UNLIMITED)

            val senderJob = launch {
                for (transmission in sendQueue) {
                    val packet = packetBuilder.build(
                        session,
                        TranscriptionChunk(
                            text = transmission.text,
                            isFinal = true,
                            languageCode = transmission.languageCode
                        )
                    )

                    val result = runCatching {
                        meshDispatcher.dispatch(packet)
                    }.getOrElse {
                        TacticalResult.Failure(
                            error = it.message ?: it.javaClass.simpleName
                        )
                    }

                    val status = when (result) {
                        is TacticalResult.Success -> PttTransmissionStatus.SENT
                        is TacticalResult.Failure -> PttTransmissionStatus.QUEUED
                    }

                    _state.update {
                        it.copy(
                            lastResult = result,
                            transmissions = it.transmissions.map { item ->
                                if (item.id == transmission.id) {
                                    item.copy(status = status)
                                } else {
                                    item
                                }
                            }
                        )
                    }
                }
            }

            try {
                speechToText.transcribe(frames).collect { chunk ->
                    val partial = chunk.text.trim()

                    if (chunk.isFinal) {
                        if (partial.isNotBlank()) {
                            val transmission = PttTransmission(
                                id = UUID.randomUUID().toString(),
                                text = partial,
                                languageCode = chunk.languageCode.ifBlank {
                                    session.languageTag.isoCode
                                },
                                timestampEpochMs = System.currentTimeMillis()
                            )

                            _state.update {
                                it.copy(
                                    lastTranscription = partial,
                                    transmissions = (
                                        it.transmissions + transmission
                                    ).takeLast(MAX_TRANSMISSION_HISTORY)
                                )
                            }

                            sendQueue.send(transmission)
                        }
                    } else if (partial.isNotBlank()) {
                        _state.update {
                            it.copy(lastTranscription = partial)
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t

                _state.update {
                    it.copy(
                        sessionState = SessionState.IDLE,
                        lastResult = TacticalResult.Failure(
                            error = t.message ?: t.javaClass.simpleName
                        )
                    )
                }
            } finally {
                sendQueue.close()
                runCatching { senderJob.join() }
            }

            _state.update {
                it.copy(sessionState = SessionState.IDLE)
            }
            currentSession = null
        }
    }

    override suspend fun stopContinuous() {
        if (!_state.value.continuousSession &&
            _state.value.sessionState == SessionState.IDLE
        ) {
            return
        }

        hapticFeedback.onRelease()
        audioRecorder.stop()

        val job = sessionJob
        if (job != null) {
            withTimeoutOrNull(releaseGraceMs) {
                job.join()
            }
            job.cancel()
            sessionJob = null
        }

        currentSession = null
        _state.update {
            it.copy(
                sessionState = SessionState.IDLE,
                continuousSession = true
            )
        }
    }

    override suspend fun cancel() {
        hapticFeedback.onRelease()
        audioRecorder.stop()
        sessionJob?.cancel()
        sessionJob = null
        transmissionJob?.cancel()
        transmissionJob = null
        currentSession = null
        _state.update {
            it.copy(
                sessionState = SessionState.IDLE,
                sessionId = null,
                lastTranscription = null,
                lastResult = null,
                continuousSession = false
            )
        }
    }

    override suspend fun release() {
        hapticFeedback.onRelease()
        audioRecorder.stop()

        val job = sessionJob
        if (job != null) {
            // Only the recording/transcription coroutine is subject to the
            // release grace period. A transmission already handed to
            // transmissionJob is intentionally allowed to finish.
            withTimeoutOrNull(releaseGraceMs) { job.join() }
            if (job.isActive) {
                // English Conformer can take longer than the old 5s grace
                // period for its final full-model inference. Give it one
                // bounded extension instead of canceling a valid utterance.
                withTimeoutOrNull(10_000L) { job.join() }
            }
            if (job.isActive) {
                job.cancel()
            }
            sessionJob = null
        }

        if (_state.value.sessionState != SessionState.TRANSMITTING) {
            _state.update { it.copy(sessionState = SessionState.IDLE) }
        }
    }

    private fun appendTranscript(existing: String, next: String): String {
        if (next.isBlank()) return existing
        if (existing.isBlank()) return next.trim()
        if (existing == next.trim()) return existing
        return existing.trim() + " " + next.trim()
    }

    private suspend fun transmit(session: PttSession, chunk: TranscriptionChunk) {
        val packet = packetBuilder.build(session, chunk)

        // Show the exact framed text the user is sending, not only the raw
        // transcription. The internal pause marker stays hidden from the UI.
        _state.update {
            it.copy(
                lastTranscription = PttPacketBuilder.toDisplayText(packet.text)
            )
        }

        val result = meshDispatcher.dispatch(packet)
        _state.update { it.copy(lastResult = result) }

        when (result) {
            is TacticalResult.Success -> hapticFeedback.onTransmitComplete()
            is TacticalResult.Failure -> hapticFeedback.onTransmitFailed()
        }
    }

    companion object {
        private const val MAX_TRANSMISSION_HISTORY = 20
    }
}
