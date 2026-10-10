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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MmsTtsEngine @Inject constructor(
    private val modelStore: MmsTtsModelStore
) {
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val mutex = Mutex()

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

                    val playbackSamples = if (useItantraModel) {
                        val processed = ItantraVitsAudioPostProcessor.process(
                            input = samples,
                            sampleRate = sampleRate,
                            reduceHiss = language.isoCode != "gu"
                        )
                        android.util.Log.i(
                            TAG,
                            "iTantra VITS waveform " + language.isoCode + ": " +
                                processed.stats.toString()
                        )
                        processed.samples
                    } else {
                        samples
                    }

                    val inferenceMs = (System.nanoTime() - startNs) / 1_000_000L
                    val frame = floatArrayToAudioFrame(
                        samples = playbackSamples,
                        sampleRate = sampleRate,
                        timestamp = System.currentTimeMillis()
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

    suspend fun play(frame: AudioFrame, sampleRate: Int) =
        withContext(Dispatchers.IO) {
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
                track.play()
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
                // AudioTrack; stopping immediately can cut off the buffered
                // tail. This became especially noticeable when a framed PTT
                // message was synthesized as three separate audio segments.
                val totalFrames = frame.data.size / 2
                while (
                    track.playState == AudioTrack.PLAYSTATE_PLAYING &&
                    track.playbackHeadPosition < totalFrames
                ) {
                    delay(10L)
                }

                track.stop()
            } finally {
                track.release()
            }
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
        private val LEGACY_MMS_CODES = setOf("eng", "hin")
    }
}
