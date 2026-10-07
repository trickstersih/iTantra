package com.tactical.platform.speech.andr2

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
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
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton
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
@Singleton
class Andr2SpeechToText @Inject constructor(
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
        val selectedLanguage = languagePreferences.selectedLanguageCode
        require(selectedLanguage in REQUIRED_LANGUAGES) {
            "Unsupported outgoing speech language: $selectedLanguage"
        }
        val runtime = loadRuntime()

        val pcmBuffer = ByteArrayOutputStream()

        // Do not feed ambient mic noise straight into andr2. The model is
        // strong enough to turn low-energy noise into plausible words, so
        // inference must only begin after a sustained, clearly audible onset.
        val preSpeechFrames = ArrayDeque<AudioFrame>()
        var preSpeechDurationMs = 0L
        var preSpeechStrongDurationMs = 0L

        var speechActive = false
        var accumulatedDurationMs = 0L
        var silenceDurationMs = 0L
        var strongSpeechDurationMs = 0L
        var lastPartialAtMs = 0L

        suspend fun infer(bytes: ByteArray): String =
            inferenceMutex.withLock {
                runtime.transcribe(bytes, selectedLanguage)
            }

        fun resetSegment() {
            pcmBuffer.reset()
            preSpeechFrames.clear()
            preSpeechDurationMs = 0L
            preSpeechStrongDurationMs = 0L
            speechActive = false
            accumulatedDurationMs = 0L
            silenceDurationMs = 0L
            strongSpeechDurationMs = 0L
            lastPartialAtMs = 0L
        }

        fun addPreSpeechFrame(frame: AudioFrame, strong: Boolean) {
            preSpeechFrames.addLast(frame)
            preSpeechDurationMs += frame.durationMs
            if (strong) {
                preSpeechStrongDurationMs += frame.durationMs
            }

            while (preSpeechDurationMs > PRE_SPEECH_MAX_MS) {
                val removed = preSpeechFrames.removeFirst()
                preSpeechDurationMs -= removed.durationMs
                if (rmsAmplitude(removed.data) >= SPEECH_START_RMS_THRESHOLD) {
                    preSpeechStrongDurationMs =
                        (preSpeechStrongDurationMs - removed.durationMs).coerceAtLeast(0L)
                }
            }
        }

        fun startSpeechFromPreRoll() {
            pcmBuffer.reset()
            var durationMs = 0L
            var strongMs = 0L

            for (preSpeechFrame in preSpeechFrames) {
                pcmBuffer.write(preSpeechFrame.data)
                durationMs += preSpeechFrame.durationMs
                if (rmsAmplitude(preSpeechFrame.data) >= SPEECH_START_RMS_THRESHOLD) {
                    strongMs += preSpeechFrame.durationMs
                }
            }

            speechActive = true
            accumulatedDurationMs = durationMs
            silenceDurationMs = 0L
            strongSpeechDurationMs = strongMs
            lastPartialAtMs = 0L

            preSpeechFrames.clear()
            preSpeechDurationMs = 0L
            preSpeechStrongDurationMs = 0L
        }

        suspend fun finalizeSegment() {
            if (
                speechActive &&
                pcmBuffer.size() > 0 &&
                accumulatedDurationMs >= MIN_SEGMENT_MS &&
                strongSpeechDurationMs >= MIN_STRONG_SPEECH_MS
            ) {
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

            resetSegment()
        }

        audio.collect { frame ->
            val rms = rmsAmplitude(frame.data)
            val strong = rms >= SPEECH_START_RMS_THRESHOLD
            val silent = rms < SILENCE_RMS_THRESHOLD

            if (!speechActive) {
                addPreSpeechFrame(frame, strong)

                if (strong) {
                    preSpeechDurationMs =
                        preSpeechDurationMs.coerceAtLeast(frame.durationMs)
                    if (preSpeechStrongDurationMs >= SPEECH_START_CONFIRMATION_MS) {
                        startSpeechFromPreRoll()
                    }
                }
                return@collect
            }

            pcmBuffer.write(frame.data)
            accumulatedDurationMs += frame.durationMs

            if (strong) {
                strongSpeechDurationMs += frame.durationMs
            }

            silenceDurationMs = if (silent) {
                silenceDurationMs + frame.durationMs
            } else {
                0L
            }

            if (
                strongSpeechDurationMs >= MIN_STRONG_SPEECH_MS &&
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

            if (silenceDurationMs >= SILENCE_THRESHOLD_MS) {
                finalizeSegment()
            }

            // Avoid silently dropping a long continuous utterance. andr2's
            // encoder is fixed at 10 seconds, so flush at 9 seconds when no
            // pause has occurred.
            if (speechActive && accumulatedDurationMs >= MAX_BUFFER_MS) {
                finalizeSegment()
            }
        }

        if (speechActive && pcmBuffer.size() > 0) {
            finalizeSegment()
        } else {
            resetSegment()
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
            var cacheOwnedByResult = false
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

                    if (cacheOwnedByResult) {
                        previousResult?.close()
                    } else {
                        selfK.close()
                        selfV.close()
                    }

                    previousResult = result
                    cacheOwnedByResult = true

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

                    if (cacheOwnedByResult) {
                        previousResult?.close()
                    } else {
                        selfK.close()
                        selfV.close()
                    }

                    previousResult = result
                    cacheOwnedByResult = true

                    selfK = result[1] as? OnnxTensor
                        ?: error("andr2 decoder new_self_k is not an ONNX tensor")
                    selfV = result[2] as? OnnxTensor
                        ?: error("andr2 decoder new_self_v is not an ONNX tensor")
                    logits = tensorToFloatArray(result[0])
                    nextId = chooseNext(logits, suppressIds)
                }

                return tokenDecoder.decode(generated)
            } finally {
                if (cacheOwnedByResult) {
                    previousResult?.close()
                } else {
                    selfK.close()
                    selfV.close()
                }
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

        private fun tensorToFloatArray(value: Any?): FloatArray {
            val tensor = value as? OnnxTensor
                ?: error("andr2 decoder logits output is not an ONNX tensor")

            val buffer = tensor.floatBuffer.duplicate()
            buffer.rewind()
            val values = FloatArray(buffer.remaining())
            buffer.get(values)
            return values
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
        val decoderFile = listOf("decoder_int8.onnx", "decoder_fp32.onnx")
            .asSequence()
            .map { File(root, it) }
            .firstOrNull { it.isFile && it.length() > 0L }
            ?: error("andr2 decoder model is missing")

        val loadedDecoder = environment.createSession(
            decoderFile.absolutePath,
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
        val encoderTensorInfo = encoderInput.info as? TensorInfo
            ?: error("andr2 encoder input_features is not a tensor")
        val encoderShape = encoderTensorInfo.getShape()
        require(
            encoderShape.contentEquals(longArrayOf(1L, 80L, 1000L))
        ) {
            "Unexpected andr2 encoder input shape: " +
                encoderShape.contentToString()
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
        val logitsTensorInfo = logits.info as? TensorInfo
            ?: error("andr2 decoder logits is not a tensor")
        val logitsShape = logitsTensorInfo.getShape()
        require(
            logitsShape.contentEquals(longArrayOf(1L, 1L, 5181L))
        ) {
            "Unexpected andr2 logits shape: " +
                logitsShape.contentToString()
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

        // 500 RMS was too permissive for the hand-trained multilingual
        // checkpoint: mic/background noise could enter inference and produce
        // plausible-looking hallucinations. Use a noticeably stronger onset
        // threshold and require it to persist before declaring speech.
        private const val SILENCE_RMS_THRESHOLD = 500.0
        private const val SPEECH_START_RMS_THRESHOLD = 1_200.0
        private const val SPEECH_START_CONFIRMATION_MS = 160L

        // Keep a small pre-roll so the first consonant/syllable is not cut off
        // while still refusing to infer until the onset is sustained.
        private const val PRE_SPEECH_MAX_MS = 220L

        // Do not finalize/infer very short or barely-voiced segments.
        private const val MIN_SEGMENT_MS = 450L
        private const val MIN_STRONG_SPEECH_MS = 240L

        // A longer silence boundary prevents ordinary intra-word / between-word
        // pauses from turning into a stream of tiny call-mode transmissions.
        private const val SILENCE_THRESHOLD_MS = 700L
        private const val PARTIAL_INTERVAL_MS = 1_000L
        private const val MAX_BUFFER_MS = 9_000L
        private const val MAX_OUTPUT_TOKENS = 192

        private val REQUIRED_LANGUAGES = setOf(
            "hi", "gu", "mr", "kn", "ml",
            "ta", "te", "or", "bn", "en"
        )
    }
}
