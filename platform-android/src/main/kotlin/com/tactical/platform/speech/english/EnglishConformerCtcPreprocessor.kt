package com.tactical.platform.speech.english

import com.tactical.platform.speech.andr2.Andr2Fft
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Matches onnx-asr's NeMo 80-feature preprocessing.
 *
 * - 16 kHz mono PCM
 * - 0.97 pre-emphasis
 * - 512-point STFT
 * - 400-sample symmetric Hann window centered in the 512-point frame
 * - 160-sample hop
 * - 80 Slaney mel bins with Slaney area normalization
 * - natural-log power mel spectrogram
 * - per-mel-bin mean/variance normalization over valid frames
 */
class EnglishConformerCtcPreprocessor {

    private val fft = Andr2Fft(FFT_SIZE)

    private val window = DoubleArray(FFT_SIZE).also { values ->
        for (i in 0 until WINDOW_LENGTH) {
            val centeredIndex = WINDOW_OFFSET + i
            values[centeredIndex] =
                0.5 - 0.5 * kotlin.math.cos(2.0 * PI * i / (WINDOW_LENGTH - 1.0))
        }
    }

    private val melFilters: Array<DoubleArray> by lazy {
        buildMelFilters()
    }

    /**
     * Returns [1, 80, T] features flattened in row-major order, plus the
     * waveform-derived feature length expected by the NeMo ONNX model.
     */
    fun pcm16ToFeatures(pcm: ByteArray): Features {
        require(pcm.size % 2 == 0) {
            "English STT PCM16 buffer has an odd byte count: ${{pcm.size}"
        }

        return audioToFeatures(pcm16ToFloat(pcm))
    }

    fun audioToFeatures(samples: FloatArray): Features {
        if (samples.isEmpty()) {
            return Features(FloatArray(0), 0)
        }

        val sampleCount = samples.size
        val validFrames = sampleCount / HOP_LENGTH
        val frameCount = validFrames + 1

        val preemphasized = FloatArray(sampleCount)
        preemphasized[0] = samples[0]
        for (i in 1 until sampleCount) {
            preemphasized[i] = samples[i] - PREEMPHASIS * samples[i - 1]
        }

        // Zero-pad n_fft/2 samples on both ends, matching onnx-asr.
        val padded = FloatArray(sampleCount + FFT_SIZE)
        preemphasized.copyInto(
            padded,
            destinationOffset = FFT_SIZE / 2
        )

        val features = FloatArray(N_MELS * frameCount)
        val frame = DoubleArray(FFT_SIZE)

        for (frameIndex in 0 until frameCount) {
            val start = frameIndex * HOP_LENGTH

            for (i in 0 until FFT_SIZE) {
                frame[i] = padded[start + i].toDouble() * window[i]
            }

            val (real, imag) = fft.forward(frame)

            for (melIndex in 0 until N_MELS) {
                val filter = melFilters[melIndex]
                var energy = 0.0

                for (frequencyBin in 0..FFT_SIZE / 2) {
                    val filterWeight = filter[frequencyBin]
                    if (filterWeight == 0.0) continue

                    val re = real[frequencyBin]
                    val im = imag[frequencyBin]
                    energy += (re * re + im * im) * filterWeight
                }

                features[melIndex * frameCount + frameIndex] =
                    ln(energy + LOG_ZERO_GUARD).toFloat()
            }
        }

        normalizePerMel(features, frameCount, validFrames)

        return Features(
            values = features,
            featureLength = validFrames.toLong()
        )
    }

    private fun normalizePerMel(
        features: FloatArray,
        frameCount: Int,
        validFrames: Int
    ) {
        for (melIndex in 0 until N_MELS) {
            val offset = melIndex * frameCount

            if (validFrames <= 0) {
                for (frameIndex in 0 until frameCount) {
                    features[offset + frameIndex] = 0f
                }
                continue
            }

            var sum = 0.0
            for (frameIndex in 0 until validFrames) {
                sum += features[offset + frameIndex].toDouble()
            }
            val mean = sum / validFrames.toDouble()

            val denominator = max(1, validFrames - 1)
            var varianceSum = 0.0
            for (frameIndex in 0 until validFrames) {
                val delta = features[offset + frameIndex].toDouble() - mean
                varianceSum += delta * delta
            }

            val variance = varianceSum / denominator.toDouble()
            val scale = kotlin.math.sqrt(variance) + NORMALIZATION_EPSILON

            for (frameIndex in 0 until validFrames) {
                features[offset + frameIndex] =
                    ((features[offset + frameIndex].toDouble() - mean) / scale).toFloat()
            }

            // The STFT has one padded tail frame beyond waveforms_lens;
            // onnx-asr's normalization mask zeroes it.
            for (frameIndex in validFrames until frameCount) {
                features[offset + frameIndex] = 0f
            }
        }
    }

    private fun pcm16ToFloat(pcm: ByteArray): FloatArray {
        val samples = FloatArray(pcm.size / 2)
        var offset = 0

        for (i in samples.indices) {
            val low = pcm[offset].toInt() and 0xFF
            val high = pcm[offset + 1].toInt()
            samples[i] = (((high shl 8) or low).toShort() / 32768f)
            offset += 2
        }

        return samples
    }

    /**
     * Exact Slaney mel-bank construction used by onnx-asr:
     * 0..8 kHz, Slaney mel scale, Slaney area normalization.
     */
    private fun buildMelFilters(): Array<DoubleArray> {
        val allFreqs = DoubleArray(FFT_SIZE / 2 + 1) { index ->
            index.toDouble() * (SAMPLE_RATE / 2.0) / (FFT_SIZE / 2.0)
        }

        val minMel = hzToMel(0.0)
        val maxMel = hzToMel(SAMPLE_RATE / 2.0)

        val melPoints = DoubleArray(N_MELS + 2) { index ->
            melToHz(
                minMel +
                    (maxMel - minMel) *
                    index.toDouble() / (N_MELS + 1).toDouble()
            )
        }

        return Array(N_MELS) { melIndex ->
            val filter = DoubleArray(FFT_SIZE / 2 + 1)
            val left = melPoints[melIndex]
            val center = melPoints[melIndex + 1]
            val right = melPoints[melIndex + 2]
            val leftWidth = center - left
            val rightWidth = right - center
            val areaNormalization = 2.0 / (right - left)

            for (frequencyIndex in allFreqs.indices) {
                val frequency = allFreqs[frequencyIndex]
                val upSlope = (frequency - left) / leftWidth
                val downSlope = (right - frequency) / rightWidth
                filter[frequencyIndex] =
                    max(0.0, min(upSlope, downSlope)) * areaNormalization
            }

            filter
        }
    }

    private fun hzToMel(freq: Double): Double =
        if (freq < 1000.0) {
            3.0 * freq / 200.0
        } else {
            15.0 + 27.0 * ln(
                freq / 1000.0 + FLOAT32_EPSILON
            ) / ln(6.4)
        }

    private fun melToHz(mel: Double): Double =
        if (mel < 15.0) {
            200.0 * mel / 3.0
        } else {
            1000.0 * exp((mel - 15.0) * ln(6.4) / 27.0)
        }

    data class Features(
        val values: FloatArray,
        val featureLength: Long
    )

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val FFT_SIZE = 512
        private const val WINDOW_LENGTH = 400
        private const val WINDOW_OFFSET = (FFT_SIZE - WINDOW_LENGTH) / 2
        private const val HOP_LENGTH = 160
        private const val N_MELS = 80
        private const val PREEMPHASIS = 0.97
        private const val LOG_ZERO_GUARD = 5.960464477539063E-8
        private const val NORMALIZATION_EPSILON = 1.0E-5
        private const val FLOAT32_EPSILON = 1.1920928955078125E-7
    }
}
