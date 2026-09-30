package com.tactical.platform.speech.andr2

import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max

class Andr2SttPreprocessor(
    melFilterFile: File
) {
    private val melFilters = Andr2NpyReader.readFloatMatrix(melFilterFile)
    private val fft = Andr2Fft(FFT_SIZE)
    private val window = DoubleArray(FFT_SIZE) { index ->
        0.5 - 0.5 * cos(2.0 * PI * index.toDouble() / FFT_SIZE.toDouble())
    }

    init {
        require(melFilters.size == N_MELS) {
            "andr2 expected " + N_MELS + " mel filters"
        }
        require(melFilters.all { it.size == FFT_SIZE / 2 + 1 }) {
            "andr2 expected " + (FFT_SIZE / 2 + 1) + " FFT bins per mel filter"
        }
    }

    fun pcm16ToFeatures(pcm: ByteArray): FloatArray =
        audioToFeatures(pcm16ToFloat(pcm))

    fun audioToFeatures(samples: FloatArray): FloatArray {
        val audio = when {
            samples.size < WINDOW_SAMPLES ->
                FloatArray(WINDOW_SAMPLES).also { samples.copyInto(it) }
            samples.size > WINDOW_SAMPLES ->
                samples.copyOfRange(samples.size - WINDOW_SAMPLES, samples.size)
            else -> samples.copyOf()
        }

        // Reflect-pad n_fft/2 samples on both ends, matching numpy/scipy
        // reflect padding for the andr2 preprocessing contract.
        val padded = FloatArray(WINDOW_SAMPLES + FFT_SIZE)
        val half = FFT_SIZE / 2

        for (i in padded.indices) {
            val source = when {
                i < half -> half - i
                i >= half + WINDOW_SAMPLES ->
                    WINDOW_SAMPLES - 2 - (i - (half + WINDOW_SAMPLES))
                else -> i - half
            }
            padded[i] = audio[source.coerceIn(0, WINDOW_SAMPLES - 1)]
        }

        // Layout is [mel, frame], exactly what a [1,80,1000] ONNX tensor needs.
        val mel = FloatArray(N_MELS * TARGET_FRAMES)
        val frame = DoubleArray(FFT_SIZE)

        for (frameIndex in 0 until TARGET_FRAMES) {
            val start = frameIndex * HOP_LENGTH

            for (i in 0 until FFT_SIZE) {
                frame[i] = padded[start + i].toDouble() * window[i]
            }

            val (real, imag) = fft.forward(frame)

            for (melBin in 0 until N_MELS) {
                val filter = melFilters[melBin]
                var energy = 0.0

                for (frequencyBin in 0..FFT_SIZE / 2) {
                    val re = real[frequencyBin]
                    val im = imag[frequencyBin]
                    energy += (re * re + im * im) * filter[frequencyBin].toDouble()
                }

                mel[melBin * TARGET_FRAMES + frameIndex] =
                    log10(max(energy, 1e-10)).toFloat()
            }
        }

        var maxValue = Float.NEGATIVE_INFINITY
        for (value in mel) {
            if (value > maxValue) maxValue = value
        }

        val floor = maxValue - 8f
        for (i in mel.indices) {
            mel[i] = (max(mel[i], floor) + 4f) / 4f
        }

        return mel
    }

    private fun pcm16ToFloat(pcm: ByteArray): FloatArray {
        require(pcm.size % 2 == 0) {
            "PCM16 buffer has an odd byte count: " + pcm.size
        }

        val samples = FloatArray(pcm.size / 2)
        var index = 0

        for (i in samples.indices) {
            val low = pcm[index].toInt() and 0xFF
            val high = pcm[index + 1].toInt()
            samples[i] = ((high shl 8) or low).toShort() / 32768f
            index += 2
        }

        return samples
    }

    companion object {
        private const val FFT_SIZE = 400
        private const val HOP_LENGTH = 160
        private const val N_MELS = 80
        private const val WINDOW_SAMPLES = 160_000
        private const val TARGET_FRAMES = 1_000
    }
}
