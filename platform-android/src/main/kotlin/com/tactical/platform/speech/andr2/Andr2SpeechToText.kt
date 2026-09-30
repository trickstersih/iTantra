package com.tactical.platform.speech.andr2

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.tactical.domain.audio.AudioFrame
import com.tactical.domain.speech.TranscriptionChunk
import com.tactical.platform.api.speech.SpeechToText
import com.tactical.platform.speech.SpeechLanguagePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * Android runtime for the bundled andr2 multilingual STT model.
 *
 * andr2 expects a fixed 10-second, 16 kHz, 80-mel input. Sentence-level
 * buffering is retained from the existing PTT pipeline: silence closes the
 * current sentence, while periodic inference produces live partial text.
 *
 * The model is language-conditioned by the selected language prefix from
 * preprocess.json; it does not use Moonshine or Vosk.
 */
class Andr2SpeechToText(
    private val modelStore: Andr2SttModelStore,
    private val languagePreferences: SpeechLanguagePreferences
) : SpeechToText {

    private val environment = OrtEnvironment.getEnvironment()
    private val loadMutex = Mutex()
    private val inferenceMutex = Mutex()

    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var preprocessor: Andr2SttPreprocessor? = null
    private var decoder: Andr2SttDecoder? = null
    private var metadata: Andr2SttMetadata? = null

    override fun transcribe(audio: Flow<AudioFrame>): Flow<TranscriptionChunk> = flow {
        val selectedLanguage = currentLanguageCode()
        val runtime = loadRuntime()

        val pcmBuffer = ByteArrayOutputStream()
        var accumulatedDurationMs = 0L
        var silenceDurationMs = 0L
        var lastPartialAtMs = 0L

        suspend fun infer(bytes: ByteArray): String =
            inferenceMutex.withLock {
                runtime.transcribe(bytes, selectedLanguage)
            }

        audio.collect { frame ->
            pcmBuffer.write(frame.data)
            accumulatedDurationMs += frame.durationMs

            val silent = rmsAmplitude(frame.data) < SILENCE_RMS_THRESHOLD
            silenceDurationMs = if (silent) {
                silenceDurationMs + frame.durationMs
            } else {
                0L
            }

            if (
                !silent &&
                accumulatedDurationMs - lastPartialAtMs >= PARTIAL_INTERVAL_MS
            ) {
                val partial = infer(pcmBuffer.toByteArray())
                if (partial.isNotBlank()) {
                    emit(
                        TranscriptionChunk(
                            text = partial,
                            isFinal = false,
                            languageCode = selectedLanguage
                        )
                    )
                }
                lastPartialAtMs = accumulatedDurationMs
            }

            if (silenceDurationMs >= SILENCE_THRESHOLD_MS && pcmBuffer.size() > 0) {
                val finalText = infer(pcmBuffer.toByteArray())
                if (finalText.isNotBlank()) {
                    emit(
                        TranscriptionChunk(
                            text = finalText,
                            isFinal = true,
                            languageCode = selectedLanguage
                        )
                    )
                }

                pcmBuffer.reset()
                accumulatedDurationMs = 0L
                silenceDurationMs = 0L
                lastPartialAtMs = 0L
            }

            // Avoid silently dropping a long continuous utterance. andr2's
            // encoder is fixed at 10 seconds, so flush at 9 seconds when no
            // pause has occurred.
            if (accumulatedDurationMs >= MAX_BUFFER_MS) {
                val finalText = infer(pcmBuffer.toByteArray())
                if (finalText.isNotBlank()) {
                    emit(
                        TranscriptionChunk(
                            text = finalText,
                            isFinal = true,
                            languageCode = selectedLanguage
                        )
                    )
                }

                pcmBuffer.reset()
                accumulatedDurationMs = 0L
                silenceDurationMs = 0L
                lastPartialAtMs = 0L
            }
        }

        if (pcmBuffer.size() > 0) {
            val finalText = infer(pcmBuffer.toByteArray())
            if (finalText.isNotBlank()) {
                emit(
                    TranscriptionChunk(
                        text = finalText,
                        isFinal = true,
                        languageCode = selectedLanguage
                    )
                )
            }
        }
    }.flowOn(Dispatchers.Default)

    suspend fun preloadSelectedLanguage() {
        loadRuntime()
    }

    fun onSelectedLanguageChanged() {
        // andr2 is one shared multilingual model. Switching language only
        // changes the prefix supplied to the decoder, so the ONNX sessions
        // stay loaded and are not needlessly re-created.
    }

    fun close() {
        loadMutex.tryLock().let { locked ->
            if (!locked) return
            try {
                closeRuntime()
            } finally {
                loadMutex.unlock()
            }
        }
    }

    private data class Runtime(
        val encoder: OrtSession,
        val decoder: OrtSession,
        val preprocessor: Andr2SttPreprocessor,
        val decoderLogic: Andr2SttDecoder,
        val metadata: Andr2SttMetadata
    ) {
        fun transcribe(bytes: ByteArray, language: String): String {
            val prefix = metadata.prefixNewIds[language]
                ?: throw IllegalArgumentException(
                    "andr2 does not contain language prefix for " + language
                )

            val features = preprocessor.pcm16ToFeatures(bytes)

            OnnxTensor.createTensor(
                ENVIRONMENT,
                FloatBuffer.wrap(features),
                longArrayOf(1L, 80L, 1000L)
            ).use { featureTensor ->
                val encoderResult = encoder.run(
                    mapOf("input_features" to featureTensor)
                )

                try {
                    val crossK = encoderResult[0] as? OnnxTensor
                        ?: error("andr2 encoder cross_k output is not an ONNX tensor")
                    val crossV = encoderResult[1] as? OnnxTensor
                        ?: error("andr2 encoder cross_v output is not an ONNX tensor")

                    return decode(
                        decoderSession = decoder,
                        crossK = crossK,
                        crossV = crossV,
                        prefix = prefix,
                        eotId = metadata.eotNewId,
                        suppressIds = metadata.suppressNewIds,
                        tokenDecoder = decoderLogic
                    )
                } finally {
                    encoderResult.close()
                }
            }
        }

        private fun decode(
            decoderSession: OrtSession,
            crossK: OnnxTensor,
            crossV: OnnxTensor,
            prefix: IntArray,
            eotId: Int,
            suppressIds: IntArray,
            tokenDecoder: Andr2SttDecoder
        ): String {
            var selfK = OnnxTensor.createTensor(
                ENVIRONMENT,
                FloatBuffer.allocate(0),
                longArrayOf(4L, 1L, 6L, 0L, 64L)
            )
            var selfV = OnnxTensor.createTensor(
                ENVIRONMENT,
                FloatBuffer.allocate(0),
                longArrayOf(4L, 1L, 6L, 0L, 64L)
            )

            var previousResult: OrtSession.Result? = null
            var logits: FloatArray? = null
            val generated = ArrayList<Int>(64)

            try {
                for (position in prefix.indices) {
                    val result = runDecoderStep(
                        decoderSession,
                        prefix[position],
                        position,
                        selfK,
                        selfV,
                        crossK,
                        crossV
                    )

                    selfK.close()
                    selfV.close()
                    previousResult?.close()
                    previousResult = result

                    selfK = result[1] as? OnnxTensor
                        ?: error("andr2 decoder new_self_k is not an ONNX tensor")
                    selfV = result[2] as? OnnxTensor
                        ?: error("andr2 decoder new_self_v is not an ONNX tensor")
                    logits = tensorToFloatArray(result[0])
                }

                if (logits == null) {
                    error("andr2 decoder produced no prefix logits")
                }

                var nextId = chooseNext(logits, suppressIds)
                while (generated.size < MAX_OUTPUT_TOKENS) {
                    if (nextId == eotId) break

                    generated += nextId

                    if (hasRepeatedFourGramX3(generated)) {
                        repeat(8) {
                            if (generated.isNotEmpty()) generated.removeAt(generated.lastIndex)
                        }
                        break
                    }

                    val position = prefix.size + generated.size - 1
                    val result = runDecoderStep(
                        decoderSession,
                        nextId,
                        position,
                        selfK,
                        selfV,
                        crossK,
                        crossV
                    )

                    selfK.close()
                    selfV.close()
                    previousResult?.close()
                    previousResult = result

                    selfK = result[1] as? OnnxTensor
                        ?: error("andr2 decoder new_self_k is not an ONNX tensor")
                    selfV = result[2] as? OnnxTensor
                        ?: error("andr2 decoder new_self_v is not an ONNX tensor")
                    logits = tensorToFloatArray(result[0])
                    nextId = chooseNext(logits, suppressIds)
                }

                return tokenDecoder.decode(generated)
            } finally {
                selfK.close()
                selfV.close()
                previousResult?.close()
            }
        }

        private fun runDecoderStep(
            decoderSession: OrtSession,
            tokenId: Int,
            position: Int,
            selfK: OnnxTensor,
            selfV: OnnxTensor,
            crossK: OnnxTensor,
            crossV: OnnxTensor
        ): OrtSession.Result {
            val tokenTensor = OnnxTensor.createTensor(
                ENVIRONMENT,
                LongBuffer.wrap(longArrayOf(tokenId.toLong())),
                longArrayOf(1L, 1L)
            )
            val positionTensor = OnnxTensor.createTensor(
                ENVIRONMENT,
                LongBuffer.wrap(longArrayOf(position.toLong())),
                longArrayOf(1L)
            )

            try {
                return decoderSession.run(
                    mapOf(
                        "input_id" to tokenTensor,
                        "position" to positionTensor,
                        "self_k" to selfK,
                        "self_v" to selfV,
                        "cross_k" to crossK,
                        "cross_v" to crossV
                    )
                )
            } finally {
                tokenTensor.close()
                positionTensor.close()
            }
        }

        private fun chooseNext(
            logitsInput: FloatArray,
            suppressIds: IntArray
        ): Int {
            val logits = logitsInput.copyOf()
            for (tokenId in suppressIds) {
                if (tokenId in logits.indices) logits[tokenId] = Float.NEGATIVE_INFINITY
            }

            var bestId = 0
            var bestValue = Float.NEGATIVE_INFINITY
            for (index in logits.indices) {
                if (logits[index] > bestValue) {
                    bestValue = logits[index]
                    bestId = index
                }
            }
            return bestId
        }

        private fun hasRepeatedFourGramX3(tokens: List<Int>): Boolean {
            if (tokens.size < 12) return false
            val start = tokens.size - 12
            for (offset in 0 until 4) {
                if (
                    tokens[start + offset] != tokens[start + 4 + offset] ||
                    tokens[start + offset] != tokens[start + 8 + offset]
                ) return false
            }
            return true
        }

        companion object {
            private val ENVIRONMENT = OrtEnvironment.getEnvironment()
        }
    }

    private suspend fun loadRuntime(): Runtime = loadMutex.withLock {
        val existingEncoder = encoderSession
        val existingDecoder = decoderSession
        val existingPreprocessor = preprocessor
        val existingTokenDecoder = decoder
        val existingMetadata = metadata

        if (
            existingEncoder != null &&
            existingDecoder != null &&
            existingPreprocessor != null &&
            existingTokenDecoder != null &&
            existingMetadata != null
        ) {
            return@withLock Runtime(
                existingEncoder,
                existingDecoder,
                existingPreprocessor,
                existingTokenDecoder,
                existingMetadata
            )
        }

        val root = modelStore.ensureBundledModelAvailable()

        val loadedMetadata = Andr2SttMetadata.load(File(root, "preprocess.json"))
        require(loadedMetadata.sampleRate == SAMPLE_RATE) {
            "andr2 sample rate mismatch: " + loadedMetadata.sampleRate
        }
        require(loadedMetadata.frames == FRAME_COUNT) {
            "andr2 frame count mismatch: " + loadedMetadata.frames
        }

        val loadedPreprocessor = Andr2SttPreprocessor(
            File(root, "mel_filters_80x201.npy")
        )
        val loadedTokenDecoder = Andr2SttDecoder(
            File(root, "vocab_map.json"),
            File(root, "tokenizer/vocab.json")
        )

        val loadedEncoder = environment.createSession(
            File(root, "encoder_int8.onnx").absolutePath,
            OrtSession.SessionOptions()
        )
        val loadedDecoder = environment.createSession(
            File(root, "decoder_fp32.onnx").absolutePath,
            OrtSession.SessionOptions()
        )

        try {
            validateSessions(loadedEncoder, loadedDecoder, loadedMetadata)
        } catch (t: Throwable) {
            runCatching { loadedEncoder.close() }
            runCatching { loadedDecoder.close() }
            throw t
        }

        encoderSession = loadedEncoder
        decoderSession = loadedDecoder
        preprocessor = loadedPreprocessor
        decoder = loadedTokenDecoder
        metadata = loadedMetadata

        Runtime(
            loadedEncoder,
            loadedDecoder,
            loadedPreprocessor,
            loadedTokenDecoder,
            loadedMetadata
        )
    }

    private fun validateSessions(
        encoder: OrtSession,
        decoder: OrtSession,
        metadata: Andr2SttMetadata
    ) {
        val encoderInput = encoder.inputInfo["input_features"]
            ?: error("andr2 encoder input_features is missing")
        require(
            encoderInput.info.shape.contentEquals(longArrayOf(1L, 80L, 1000L))
        ) {
            "Unexpected andr2 encoder input shape: " + encoderInput.info.shape.contentToString()
        }

        require(encoder.outputInfo.size == 2) {
            "andr2 encoder must have cross_k and cross_v outputs"
        }

        require(decoder.inputInfo.containsKey("input_id"))
        require(decoder.inputInfo.containsKey("position"))
        require(decoder.inputInfo.containsKey("self_k"))
        require(decoder.inputInfo.containsKey("self_v"))
        require(decoder.inputInfo.containsKey("cross_k"))
        require(decoder.inputInfo.containsKey("cross_v"))

        val logits = decoder.outputInfo["logits"]
            ?: error("andr2 decoder logits output is missing")
        require(
            logits.info.shape.contentEquals(longArrayOf(1L, 1L, 5181L))
        ) {
            "Unexpected andr2 logits shape: " + logits.info.shape.contentToString()
        }

        require(metadata.prefixNewIds.keys.containsAll(REQUIRED_LANGUAGES)) {
            "andr2 preprocess.json is missing one or more required languages"
        }
    }

    private fun closeRuntime() {
        runCatching { encoderSession?.close() }
        runCatching { decoderSession?.close() }
        encoderSession = null
        decoderSession = null
        preprocessor = null
        decoder = null
        metadata = null
    }

    private fun rmsAmplitude(pcmBytes: ByteArray): Double {
        val sampleCount = pcmBytes.size / 2
        if (sampleCount == 0) return 0.0

        var sum = 0.0
        var offset = 0
        repeat(sampleCount) {
            val low = pcmBytes[offset].toInt() and 0xFF
            val high = pcmBytes[offset + 1].toInt()
            val sample = ((high shl 8) or low).toShort().toDouble()
            sum += sample * sample
            offset += 2
        }

        return sqrt(sum / sampleCount.toDouble())
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val FRAME_COUNT = 1_000
        private const val SILENCE_RMS_THRESHOLD = 500.0
        private const val SILENCE_THRESHOLD_MS = 400L
        private const val PARTIAL_INTERVAL_MS = 1_000L
        private const val MAX_BUFFER_MS = 9_000L
        private const val MAX_OUTPUT_TOKENS = 192

        private val REQUIRED_LANGUAGES = setOf(
            "hi", "gu", "mr", "kn", "ml",
            "ta", "te", "or", "bn", "en"
        )
    }
}
