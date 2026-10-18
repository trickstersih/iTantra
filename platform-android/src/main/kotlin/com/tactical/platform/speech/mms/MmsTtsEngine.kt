package com.tactical.platform.speech.mms

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.tactical.domain.audio.AudioFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.sqrt
import javax.inject.Singleton

@Singleton
class MmsTtsEngine @Inject constructor(
    private val modelStore: MmsTtsModelStore
) {
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val mutex = Mutex()

    // Manual chat playback owns a separate AudioTrack from incoming transmission
    // playback, so stopping a preview never interrupts live incoming speech.
    private val manualPlaybackLock = Any()
    private var manualPlaybackGeneration = 0L
    private var manualTrack: AudioTrack? = null

    private var loadedLanguage: MmsTtsLanguage? = null
    private var session: OrtSession? = null
    private var tokenizer: MmsVitsTokenizer? = null
    private var itantTokenizer: ItantraVitsTokenizer? = null
    private var sampleRate: Int = DEFAULT_SAMPLE_RATE

    /**
     * Warms the requested TTS language without generating audio.
     *
     * Running this in the background moves model extraction and ONNX session
     * initialization away from the critical message-to-playback path.
     * Only one language session is kept resident.
     */
    suspend fun preload(language: MmsTtsLanguage) =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                val directory = modelStore.ensureBundledModelAvailable(language)
                ensureLoaded(language, directory)
            }
        }

    suspend fun synthesize(
        language: MmsTtsLanguage,
        text: String
    ): Pair<AudioFrame, MmsTtsTestResult> = withContext(Dispatchers.Default) {
        mutex.withLock {
            val directory = modelStore.ensureBundledModelAvailable(language)
            ensureLoaded(language, directory)

            val useItantraModel = usesItantraVitsModel(language)
            val currentSession = checkNotNull(session)
            val legacyTokenized = if (useItantraModel) null else {
                checkNotNull(tokenizer).tokenize(text)
            }
            val itantTokenized = if (useItantraModel) {
                checkNotNull(itantTokenizer).tokenize(text)
            } else {
                null
            }
            val tokenizedIds = legacyTokenized?.ids ?: checkNotNull(itantTokenized).ids
            val normalizedText = legacyTokenized?.normalizedText
                ?: checkNotNull(itantTokenized).normalizedText

            itantTokenized?.droppedCharacters
                ?.takeIf { it.isNotEmpty() }
                ?.let { dropped ->
                    android.util.Log.w(
                        TAG,
                        "TTS ignored unsupported characters for " + language.isoCode +
                            ": " + dropped.joinToString()
                    )
                }

            val startNs = System.nanoTime()
            val inputIdsName = findInputName(
                currentSession,
                if (useItantraModel) "x" else "input_ids"
            ) ?: throw IllegalStateException(
                "TTS token input is missing for " + language.displayName
            )
            val attentionMaskName = if (useItantraModel) {
                null
            } else {
                findInputName(
                    currentSession,
                    "attention_mask",
                    required = false
                )
            }

            val inputs = LinkedHashMap<String, OnnxTensor>()
            try {
                inputs[inputIdsName] = OnnxTensor.createTensor(
                    environment,
                    LongBuffer.wrap(tokenizedIds),
                    longArrayOf(1L, tokenizedIds.size.toLong())
                )

                if (attentionMaskName != null) {
                    val mask = LongArray(tokenizedIds.size) { 1L }
                    inputs[attentionMaskName] = OnnxTensor.createTensor(
                        environment,
                        LongBuffer.wrap(mask),
                        longArrayOf(1L, mask.size.toLong())
                    )
                }

                currentSession.inputInfo.keys
                    .filter { it != inputIdsName && it != attentionMaskName }
                    .forEach { name ->
                        if (name.contains("speaker", ignoreCase = true)) {
                            inputs[name] = OnnxTensor.createTensor(
                                environment,
                                LongBuffer.wrap(longArrayOf(0L)),
                                longArrayOf(1L)
                            )
                        } else {
                            throw IllegalStateException(
                                "Unsupported TTS input '" + name + "'. " +
                                    "Expected x for iTantra VITS, or input_ids/attention_mask " +
                                    "(and optionally speaker_id) for legacy MMS."
                            )
                        }
                    }

                currentSession.run(inputs).use { result ->
                    val output = result[0] as? OnnxTensor
                        ?: throw IllegalStateException("MMS-TTS output[0] is not an ONNX tensor")

                    val buffer = output.floatBuffer.duplicate()
                    buffer.rewind()
                    val samples = FloatArray(buffer.remaining())
                    buffer.get(samples)

                    require(samples.isNotEmpty()) { "MMS-TTS returned empty waveform" }

                    // Keep the new model waveform untouched while isolating
                    // model/export quality. The previous post-filter did not
                    // remove the reported hiss and could soften consonants.
                    // Do not hide model defects with more DSP guesses.
                    val playbackSamples = samples

                    val inferenceMs = (System.nanoTime() - startNs) / 1_000_000L
                    val frame = floatArrayToAudioFrame(
                        samples = playbackSamples,
                        sampleRate = sampleRate,
                        timestamp = System.currentTimeMillis()
                    )

                    // Opt-in diagnostics only: create a WAV from the exact PCM
                    // sent to AudioTrack, plus the exact tokenizer IDs. Enable
                    // locally with a marker file in app-private files/.
                    writeDiagnosticsIfEnabled(
                        language = language,
                        requestedText = text,
                        normalizedText = normalizedText,
                        tokenizedIds = tokenizedIds,
                        samples = samples,
                        frame = frame,
                        sampleRate = sampleRate
                    )

                    frame to MmsTtsTestResult(
                        language = language,
                        requestedText = text,
                        normalizedText = normalizedText,
                        tokenCount = tokenizedIds.size,
                        inferenceMs = inferenceMs,
                        audioDurationMs = frame.durationMs,
                        sampleRate = sampleRate
                    )
                }
            } finally {
                inputs.values.forEach { runCatching { it.close() } }
            }
        }
    }

    suspend fun synthesizeAndPlay(
        language: MmsTtsLanguage,
        text: String
    ): MmsTtsTestResult {
        val (frame, result) = synthesize(language, text)
        play(frame, result.sampleRate)
        return result
    }

    fun close() {
        closeLoadedSession()
    }

    private fun ensureLoaded(language: MmsTtsLanguage, directory: File) {
        val useItantraModel = usesItantraVitsModel(language)
        val tokenizerLoaded = if (useItantraModel) {
            itantTokenizer != null
        } else {
            tokenizer != null
        }
        if (loadedLanguage == language && session != null && tokenizerLoaded) return

        closeLoadedSession()

        val modelFile = File(
            directory,
            if (useItantraModel) "model.int8.mixed.onnx" else "model.int8.onnx"
        )
        require(modelFile.isFile && modelFile.length() > 0L) {
            "TTS ONNX model is missing: " + modelFile.absolutePath
        }

        // Load directly from disk instead of first copying the entire ONNX
        // model into a Kotlin ByteArray. This lowers peak RAM during model
        // initialization on devices with limited memory.
        session = environment.createSession(
            modelFile.absolutePath,
            OrtSession.SessionOptions()
        )

        if (useItantraModel) {
            tokenizer = null
            val customMetadata = checkNotNull(session).metadata.customMetadata
            android.util.Log.i(
                TAG,
                "iTantra VITS metadata for " + language.isoCode + ": " + customMetadata
            )
            itantTokenizer = ItantraVitsTokenizer(
                directory,
                language.isoCode,
                customMetadata
            )
        } else {
            tokenizer = MmsVitsTokenizer(directory)
            itantTokenizer = null
        }

        val config = JSONObject(
            File(directory, "config.json").readText(Charsets.UTF_8)
        )
        sampleRate = if (useItantraModel) {
            config.optInt("sample_rate", DEFAULT_SAMPLE_RATE)
        } else {
            config.optInt(
                "sampling_rate",
                config.optInt("sample_rate", DEFAULT_SAMPLE_RATE)
            )
        }
        loadedLanguage = language
    }

    private fun usesItantraVitsModel(language: MmsTtsLanguage): Boolean =
        language.modelCode !in LEGACY_MMS_CODES

    private fun findInputName(
        currentSession: OrtSession,
        expected: String,
        required: Boolean = true
    ): String? {
        val exact = currentSession.inputInfo.keys.firstOrNull { it == expected }
        if (exact != null) return exact

        val fuzzy = currentSession.inputInfo.keys.firstOrNull {
            it.replace("_", "").equals(
                expected.replace("_", ""),
                ignoreCase = true
            )
        }
        if (fuzzy != null) return fuzzy

        if (required) {
            throw IllegalStateException(
                "MMS-TTS ONNX model does not expose '" + expected +
                    "'. Inputs: " + currentSession.inputInfo.keys
            )
        }
        return null
    }

    /**
     * Invalidates an active manual preview and stops its AudioTrack immediately.
     * Incoming voice playback uses play(), so its tracks are never affected.
     */
    fun beginManualPlayback(): Long {
        val (generation, previousTrack) = synchronized(manualPlaybackLock) {
            manualPlaybackGeneration += 1L
            val newGeneration = manualPlaybackGeneration
            val activeTrack = manualTrack
            manualTrack = null
            newGeneration to activeTrack
        }
        stopTrack(previousTrack)
        return generation
    }

    fun stopManualPlayback() {
        val track = synchronized(manualPlaybackLock) {
            manualPlaybackGeneration += 1L
            manualTrack.also { manualTrack = null }
        }
        stopTrack(track)
    }

    suspend fun play(frame: AudioFrame, sampleRate: Int) =
        playInternal(frame, sampleRate, manualGeneration = null)

    suspend fun playManual(
        frame: AudioFrame,
        sampleRate: Int,
        manualGeneration: Long
    ) = playInternal(frame, sampleRate, manualGeneration)

    private suspend fun playInternal(
        frame: AudioFrame,
        sampleRate: Int,
        manualGeneration: Long?
    ) = withContext(Dispatchers.IO) {
        val channelMask = AudioFormat.CHANNEL_OUT_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            channelMask,
            encoding
        )
        require(minBuffer > 0) {
            "AudioTrack buffer size unavailable for " + sampleRate + " Hz"
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .setEncoding(encoding)
                    .build()
            )
            .setBufferSizeInBytes(
                maxOf(
                    minBuffer,
                    frame.data.size.coerceAtMost(minBuffer * 4)
                )
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        try {
            if (manualGeneration == null) {
                track.play()
            } else {
                // Register and start atomically with respect to stop/begin.
                // A stale cancelled preview cannot start after a newer tap.
                val started = synchronized(manualPlaybackLock) {
                    if (manualGeneration != manualPlaybackGeneration) {
                        false
                    } else {
                        manualTrack = track
                        track.play()
                        true
                    }
                }
                if (!started) return@withContext
            }

            var offset = 0
            while (offset < frame.data.size) {
                val written = track.write(
                    frame.data,
                    offset,
                    frame.data.size - offset
                )
                require(written > 0) {
                    "AudioTrack.write failed: " + written
                }
                offset += written
            }

            // write() only guarantees that samples were accepted by
            // AudioTrack; stopping immediately can cut off the buffered tail.
            val totalFrames = frame.data.size / 2
            while (
                track.playState == AudioTrack.PLAYSTATE_PLAYING &&
                track.playbackHeadPosition < totalFrames
            ) {
                delay(10L)
            }

            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.stop()
            }
        } finally {
            if (manualGeneration != null) {
                synchronized(manualPlaybackLock) {
                    if (manualTrack === track) manualTrack = null
                }
            }
            runCatching { track.release() }
        }
    }

    private fun stopTrack(track: AudioTrack?) {
        if (track == null) return
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.stop() }
    }

    private fun floatArrayToAudioFrame(
        samples: FloatArray,
        sampleRate: Int,
        timestamp: Long
    ): AudioFrame {
        val pcm = ByteArray(samples.size * 2)
        val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)

        samples.forEach { sample ->
            val value = (
                sample.coerceIn(-1f, 1f) * 32767f
            ).toInt().toShort()
            buffer.putShort(value)
        }

        val durationMs =
            (samples.size * 1000L) / sampleRate.coerceAtLeast(1)

        return AudioFrame(
            data = pcm,
            timestamp = timestamp,
            durationMs = durationMs.coerceAtLeast(1L)
        )
    }

    /**
     * Captures the precise Android tokenizer output and pre-playback PCM only
     * when app-private files/tts_debug_enabled exists. This avoids recording
     * ordinary incoming messages unless a developer explicitly opts in.
     */
    private suspend fun writeDiagnosticsIfEnabled(
        language: MmsTtsLanguage,
        requestedText: String,
        normalizedText: String,
        tokenizedIds: LongArray,
        samples: FloatArray,
        frame: AudioFrame,
        sampleRate: Int
    ) {
        val filesDirectory = modelStore.rootDirectory.parentFile ?: return
        if (!File(filesDirectory, DEBUG_MARKER_FILE).isFile) return

        withContext(Dispatchers.IO) {
            val debugDirectory = File(filesDirectory, DEBUG_DIRECTORY)
            if (!debugDirectory.exists() && !debugDirectory.mkdirs()) {
                android.util.Log.w(TAG, "Could not create TTS diagnostic directory")
                return@withContext
            }

            val safeSamples = samples.filter { it.isFinite() }
            val minSample = safeSamples.minOrNull() ?: 0f
            val maxSample = safeSamples.maxOrNull() ?: 0f
            val sumSquares = safeSamples.sumOf { it.toDouble() * it.toDouble() }
            val rms = if (safeSamples.isEmpty()) 0.0 else sqrt(sumSquares / safeSamples.size)
            val nonFiniteCount = samples.count { !it.isFinite() }
            val nearFullScalePercent = if (samples.isEmpty()) 0.0 else {
                100.0 * samples.count { it.isFinite() && abs(it) >= 0.999f } / samples.size
            }

            val baseName = "${language.isoCode}_latest"
            val wavFile = File(debugDirectory, "${baseName}.wav")
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(36 + frame.data.size)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16)
            header.putShort(1.toShort()) // PCM
            header.putShort(1.toShort()) // mono
            header.putInt(sampleRate)
            header.putInt(sampleRate * 2)
            header.putShort(2.toShort()) // block align
            header.putShort(16.toShort()) // bits per sample
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(frame.data.size)
            FileOutputStream(wavFile).use { output ->
                output.write(header.array())
                output.write(frame.data)
            }

            val reportFile = File(debugDirectory, "${baseName}.txt")
            reportFile.writeText(
                buildString {
                    appendLine("language=${language.isoCode}")
                    appendLine("requestedText=${requestedText.replace("\\n", "\\\\n").replace("\\r", "\\\\r")}")
                    appendLine("normalizedText=${normalizedText}")
                    appendLine("sampleRate=${sampleRate}")
                    appendLine("sampleCount=${samples.size}")
                    appendLine("durationMs=${(samples.size * 1000L) / sampleRate.coerceAtLeast(1)}")
                    appendLine("min=${minSample}")
                    appendLine("max=${maxSample}")
                    appendLine("rms=${rms}")
                    appendLine("nonFiniteCount=${nonFiniteCount}")
                    appendLine("nearFullScalePercent=${nearFullScalePercent}")
                    appendLine("tokenCount=${tokenizedIds.size}")
                    appendLine("tokenIds=${tokenizedIds.joinToString(",")}")
                },
                Charsets.UTF_8
            )

            android.util.Log.i(
                TAG,
                "TTS diagnostic capture: ${wavFile.absolutePath}; " +
                    "ids=${tokenizedIds.size}, sr=${sampleRate}, samples=${samples.size}, " +
                    "min=${minSample}, max=${maxSample}, rms=${rms}, " +
                    "nonFinite=${nonFiniteCount}, nearFullScalePct=${nearFullScalePercent}"
            )
        }
    }

    private fun closeLoadedSession() {
        runCatching { session?.close() }
        session = null
        tokenizer = null
        itantTokenizer = null
        loadedLanguage = null
    }

    companion object {
        private const val DEFAULT_SAMPLE_RATE = 16_000
        private const val TAG = "MmsTtsEngine"
        private const val DEBUG_MARKER_FILE = "tts_debug_enabled"
        private const val DEBUG_DIRECTORY = "tts_debug"
        private val LEGACY_MMS_CODES = setOf("eng", "hin")
    }
}
