package com.tactical.platform.speech.english

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.tactical.domain.audio.AudioFrame
import com.tactical.domain.speech.TranscriptionChunk
import com.tactical.platform.api.speech.SpeechToText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
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
 * Android runtime for the English-only NVIDIA NeMo Conformer CTC model.
 *
 * This backend is selected only for English. All other supported languages
 * continue through the bundled andr2 multilingual backend.
 */
@Singleton
class EnglishConformerCtcSpeechToText @Inject constructor(
    private val modelStore: EnglishSttModelStore
) : SpeechToText {

    private val environment = OrtEnvironment.getEnvironment()
    private val loadMutex = Mutex()
    private val inferenceMutex = Mutex()

    private var modelSession: OrtSession? = null
    private var preprocessor: EnglishConformerCtcPreprocessor? = null
    private var vocabulary: List<String>? = null
    private var blankIndex: Int? = null

    override fun transcribe(
        audio: Flow<AudioFrame>
    ): Flow<TranscriptionChunk> = flow {
        val runtime = loadRuntime()

        val pcmBuffer = ByteArrayOutputStream()
        var accumulatedDurationMs = 0L
        var silenceDurationMs = 0L

        suspend fun infer(bytes: ByteArray): String =
            inferenceMutex.withLock {
                val startedAt = android.os.SystemClock.elapsedRealtime()
                android.util.Log.d(
                    TAG,
                    "English STT inference start: pcmBytes=" + bytes.size
                )
                try {
                    runtime.transcribe(bytes)
                } finally {
                    val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
                    android.util.Log.d(
                        TAG,
                        "English STT inference finished in " + elapsed + "ms"
                    )
                }
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
                silenceDurationMs >= SILENCE_THRESHOLD_MS &&
                pcmBuffer.size() > 0
            ) {
                val segmentBytes = pcmBuffer.toByteArray()
                android.util.Log.d(
                    TAG,
                    "English STT sentence boundary: durationMs=" +
                        accumulatedDurationMs +
                        " pcmBytes=" + segmentBytes.size
                )
                val finalText = infer(segmentBytes)
                if (finalText.isNotBlank()) {
                    emit(
                        TranscriptionChunk(
                            text = finalText,
                            isFinal = true,
                            languageCode = LANGUAGE_CODE
                        )
                    )
                }

                pcmBuffer.reset()
                accumulatedDurationMs = 0L
                silenceDurationMs = 0L
            }

            // Keep continuous speech bounded so Call Mode still emits
            // sentence-sized transmissions.
            if (accumulatedDurationMs >= MAX_BUFFER_MS) {
                val segmentBytes = pcmBuffer.toByteArray()
                android.util.Log.d(
                    TAG,
                    "English STT max buffer reached: durationMs=" +
                        accumulatedDurationMs +
                        " pcmBytes=" + segmentBytes.size
                )
                val finalText = infer(segmentBytes)
                if (finalText.isNotBlank()) {
                    emit(
                        TranscriptionChunk(
                            text = finalText,
                            isFinal = true,
                            languageCode = LANGUAGE_CODE
                        )
                    )
                }

                pcmBuffer.reset()
                accumulatedDurationMs = 0L
                silenceDurationMs = 0L
            }
        }

        if (pcmBuffer.size() > 0) {
            val finalBytes = pcmBuffer.toByteArray()
            android.util.Log.d(
                TAG,
                "English STT finalizing after audio stream close: pcmBytes=" +
                    finalBytes.size
            )
            val finalText = infer(finalBytes)
            if (finalText.isNotBlank()) {
                emit(
                    TranscriptionChunk(
                        text = finalText,
                        isFinal = true,
                        languageCode = LANGUAGE_CODE
                    )
                )
            }
        }
    }.flowOn(Dispatchers.Default)

    suspend fun preload() {
        loadRuntime()
    }

    fun close() {
        if (!loadMutex.tryLock()) return
        try {
            closeRuntime()
        } finally {
            loadMutex.unlock()
        }
    }

    private fun closeRuntime() {
        runCatching { modelSession?.close() }
        modelSession = null
        preprocessor = null
        vocabulary = null
        blankIndex = null
    }

    private suspend fun loadRuntime(): Runtime = loadMutex.withLock {
        val existingModel = modelSession
        val existingPreprocessor = preprocessor
        val existingVocabulary = vocabulary
        val existingBlankIndex = blankIndex

        if (
            existingModel != null &&
            existingPreprocessor != null &&
            existingVocabulary != null &&
            existingBlankIndex != null
        ) {
            return@withLock Runtime(
                existingModel,
                existingPreprocessor,
                existingVocabulary,
                existingBlankIndex
            )
        }

        val root = modelStore.ensureBundledModelAvailable()
        val loadedVocabulary = loadVocabulary(File(root, "vocab.txt"))
        val loadedBlankIndex = loadedVocabulary.indexOf("<blk>")

        require(loadedBlankIndex >= 0) {
            "English STT vocabulary does not contain <blk>"
        }

        val loadedPreprocessor = EnglishConformerCtcPreprocessor()
        val loadedSession = environment.createSession(
            File(root, "model.int8.onnx").absolutePath,
            OrtSession.SessionOptions()
        )

        try {
            validateSession(loadedSession, loadedVocabulary.size)
        } catch (t: Throwable) {
            runCatching { loadedSession.close() }
            throw t
        }

        modelSession = loadedSession
        preprocessor = loadedPreprocessor
        vocabulary = loadedVocabulary
        blankIndex = loadedBlankIndex

        Runtime(
            loadedSession,
            loadedPreprocessor,
            loadedVocabulary,
            loadedBlankIndex
        )
    }

    private fun validateSession(
        session: OrtSession,
        vocabularySize: Int
    ) {
        require(session.inputInfo.containsKey("audio_signal")) {
            "English STT model is missing audio_signal input"
        }
        require(session.inputInfo.containsKey("length")) {
            "English STT model is missing length input"
        }

        val audioInfo = session.inputInfo["audio_signal"]?.info as? TensorInfo
            ?: error("English STT audio_signal is not a tensor")
        val audioShape = audioInfo.getShape()

        require(audioShape.size == 3) {
            "Unexpected English STT audio_signal rank: " +
                audioShape.contentToString()
        }
        require(audioShape[1] == 80L || audioShape[1] == -1L) {
            "Unexpected English STT feature dimension: " +
                audioShape.contentToString()
        }

        val lengthInfo = session.inputInfo["length"]?.info as? TensorInfo
            ?: error("English STT length is not a tensor")
        require(lengthInfo.getShape().size == 1) {
            "Unexpected English STT length shape: " +
                lengthInfo.getShape().contentToString()
        }

        val output = session.outputInfo["logprobs"]
            ?: error("English STT model is missing logprobs output")
        val outputInfo = output.info as? TensorInfo
            ?: error("English STT logprobs is not a tensor")
        val outputShape = outputInfo.getShape()

        require(outputShape.size == 3) {
            "Unexpected English STT logprobs rank: " +
                outputShape.contentToString()
        }
        require(
            outputShape[2] == vocabularySize.toLong() ||
                outputShape[2] == -1L
        ) {
            "English STT vocabulary/output mismatch: " +
                outputShape.contentToString() +
                " vs " + vocabularySize
        }
    }

    private fun loadVocabulary(file: File): List<String> {
        val result = MutableList(1025) { "<missing>" }

        file.useLines { lines ->
            lines.filter { it.isNotBlank() }.forEach { line ->
                val separator = line.lastIndexOf(' ')
                require(separator > 0) {
                    "Invalid English STT vocabulary line: $line"
                }

                val token = line.substring(0, separator)
                    .replace('\u2581', ' ')
                val id = line.substring(separator + 1).trim().toInt()

                require(id in result.indices) {
                    "English STT token id out of range: $id"
                }

                result[id] = token
            }
        }

        require(result.none { it == "<missing>" }) {
            "English STT vocabulary is incomplete"
        }

        return result
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

    private data class Runtime(
        val session: OrtSession,
        val preprocessor: EnglishConformerCtcPreprocessor,
        val vocabulary: List<String>,
        val blankIndex: Int
    ) {
        fun transcribe(pcm: ByteArray): String {
            val features = preprocessor.pcm16ToFeatures(pcm)
            if (
                features.values.isEmpty() ||
                features.featureLength <= 0L
            ) {
                return ""
            }

            val timeSteps = features.values.size / 80
            val inputTensor = OnnxTensor.createTensor(
                ENVIRONMENT,
                FloatBuffer.wrap(features.values),
                longArrayOf(
                    1L,
                    80L,
                    timeSteps.toLong()
                )
            )

            val lengthTensor = OnnxTensor.createTensor(
                ENVIRONMENT,
                LongBuffer.wrap(
                    longArrayOf(features.featureLength)
                ),
                longArrayOf(1L)
            )

            try {
                val result = session.run(
                    mapOf(
                        "audio_signal" to inputTensor,
                        "length" to lengthTensor
                    )
                )

                try {
                    val outputTensor = result["logprobs"] as? OnnxTensor
                        ?: error(
                            "English STT logprobs output is not an ONNX tensor"
                        )
                    val outputInfo = outputTensor.info as? TensorInfo
                        ?: error(
                            "English STT logprobs output has no tensor info"
                        )
                    val outputShape = outputInfo.getShape()

                    require(outputShape.size == 3) {
                        "Unexpected English STT output shape: " +
                            outputShape.contentToString()
                    }

                    val batchSize = outputShape[0]
                    val outputTimeSteps = outputShape[1]
                    val outputVocabSize = outputShape[2]

                    require(batchSize == 1L) {
                        "English STT expected batch size 1, got " +
                            outputShape.contentToString()
                    }
                    require(outputVocabSize >= vocabulary.size) {
                        "English STT output vocabulary is smaller than vocab.txt: " +
                            outputShape.contentToString()
                    }

                    val encodedLength =
                        ((features.featureLength - 1L) / 4L + 1L)
                            .coerceAtLeast(0L)
                            .coerceAtMost(outputTimeSteps)

                    if (encodedLength <= 0L) return ""

                    val buffer = outputTensor.floatBuffer.duplicate()
                    buffer.rewind()

                    val tokens = ArrayList<Int>()
                    var previousRawToken = blankIndex

                    for (timeIndex in 0 until encodedLength.toInt()) {
                        val base =
                            timeIndex * outputVocabSize.toInt()

                        var bestId = 0
                        var bestValue = Float.NEGATIVE_INFINITY

                        for (tokenId in 0 until vocabulary.size) {
                            val value = buffer.get(base + tokenId)
                            if (value > bestValue) {
                                bestValue = value
                                bestId = tokenId
                            }
                        }

                        // Match onnx-asr's CTC decoding: blanks are removed,
                        // and adjacent repeated raw argmax tokens collapse.
                        if (
                            bestId != blankIndex &&
                            bestId != previousRawToken
                        ) {
                            tokens += bestId
                        }

                        previousRawToken = bestId
                    }

                    val decoded = buildString {
                        for (tokenId in tokens) {
                            append(vocabulary[tokenId])
                        }
                    }.trim()

                    android.util.Log.d(
                        "EnglishConformerCtc",
                        "English STT decoded text: " + decoded
                    )

                    return decoded
                } finally {
                    result.close()
                }
            } finally {
                inputTensor.close()
                lengthTensor.close()
            }
        }

        companion object {
            private val ENVIRONMENT = OrtEnvironment.getEnvironment()
        }
    }

    companion object {
        private const val TAG = "EnglishConformerCtc"
        private const val LANGUAGE_CODE = "en"
        private const val SILENCE_RMS_THRESHOLD = 500.0
        private const val SILENCE_THRESHOLD_MS = 400L
        private const val MAX_BUFFER_MS = 9_000L
    }
}
