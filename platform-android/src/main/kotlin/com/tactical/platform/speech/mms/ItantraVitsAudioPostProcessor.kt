package com.tactical.platform.speech.mms

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Conservative cleanup for the new mixed-precision multilingual VITS outputs.
 *
 * This is deliberately not a neural denoiser: it removes DC offset, attenuates
 * sub-bass/high-frequency hiss, avoids hard clipping, and softens the final
 * samples so AudioTrack does not end on a discontinuity. It does not modify
 * the source ONNX model or the legacy English/Hindi output path.
 */
internal object ItantraVitsAudioPostProcessor {
    data class Stats(
        val inputPeak: Float,
        val inputRms: Float,
        val inputMean: Float,
        val inputClippedPercent: Float,
        val outputPeak: Float,
        val outputRms: Float,
        val addedTailMs: Int
    ) {
        override fun toString(): String =
            "inputPeak=$inputPeak inputRms=$inputRms inputMean=$inputMean " +
                "inputClippedPercent=$inputClippedPercent outputPeak=$outputPeak " +
                "outputRms=$outputRms addedTailMs=$addedTailMs"
    }

    data class Result(val samples: FloatArray, val stats: Stats)

    fun process(
        input: FloatArray,
        sampleRate: Int,
        reduceHiss: Boolean
    ): Result {
        require(input.isNotEmpty()) { "VITS returned an empty waveform" }
        require(sampleRate > 0) { "Invalid sample rate: $sampleRate" }

        val finiteInput = FloatArray(input.size)
        var sum = 0.0
        var inputPeak = 0f
        var inputSquaredSum = 0.0
        var clipped = 0
        input.forEachIndexed { index, sample ->
            val clean = if (sample.isFinite()) sample else 0f
            finiteInput[index] = clean
            sum += clean.toDouble()
            inputPeak = max(inputPeak, abs(clean))
            inputSquaredSum += clean.toDouble() * clean.toDouble()
            if (abs(clean) > 0.999f) clipped++
        }
        val mean = (sum / finiteInput.size).toFloat()
        val inputRms = sqrt(inputSquaredSum / finiteInput.size).toFloat()
        val clippedPercent = clipped.toFloat() * 100f / finiteInput.size

        // Remove only the waveform's global DC offset.
        for (i in finiteInput.indices) {
            finiteInput[i] -= mean
        }

        // Gentle first-order high-pass at 55 Hz to remove DC rumble.
        val hpCutoff = 55.0
        val hpRc = 1.0 / (2.0 * Math.PI * hpCutoff)
        val dt = 1.0 / sampleRate.toDouble()
        val hpAlpha = (hpRc / (hpRc + dt)).toFloat()
        var previousInput = 0f
        var previousOutput = 0f
        for (i in finiteInput.indices) {
            val current = finiteInput[i]
            val filtered = hpAlpha * (previousOutput + current - previousInput)
            finiteInput[i] = filtered
            previousInput = current
            previousOutput = filtered
        }

        if (reduceHiss) {
            // The first pass used a one-pole 6.5 kHz filter, which only very
            // lightly attenuated the hiss reported for seven of the languages.
            // Use a second-order Butterworth low-pass at 4.8 kHz for those
            // outputs. This preserves most speech fundamentals/formants while
            // attenuating more high-band hiss. Gujarati keeps the gentler
            // original filter because it did not exhibit the reported static.
            applyButterworthLowPass(
                samples = finiteInput,
                sampleRate = sampleRate,
                cutoffHz = minOf(4_800.0, sampleRate * 0.30)
            )
        } else {
            val lpCutoff = minOf(6_500.0, sampleRate * 0.42)
            val lpAlpha = (1.0 - exp(-2.0 * Math.PI * lpCutoff / sampleRate)).toFloat()
            var lowPassState = 0f
            for (i in finiteInput.indices) {
                lowPassState += lpAlpha * (finiteInput[i] - lowPassState)
                finiteInput[i] = lowPassState
            }
        }

        // Scale down only if peaks approach/exceed full scale. Do not normalize
        // quiet signals upward, since that would also amplify background noise.
        var filteredPeak = 0f
        var filteredSquareSum = 0.0
        for (sample in finiteInput) {
            filteredPeak = max(filteredPeak, abs(sample))
            filteredSquareSum += sample.toDouble() * sample.toDouble()
        }
        if (filteredPeak > 0.97f) {
            val gain = 0.97f / filteredPeak
            for (i in finiteInput.indices) finiteInput[i] *= gain
        }

        // Do not fade the final 12 ms of the generated waveform blindly:
        // some VITS checkpoints end directly on the final phoneme, so doing so
        // attenuates that phoneme and can sound like a clipped word. Append a
        // quiet drain interval instead; AudioTrack waits for these frames too.
        val tailSamples = (sampleRate * TAIL_SILENCE_MS / 1000).toInt()
        val output = finiteInput.copyOf(finiteInput.size + tailSamples)
        var outputPeak = 0f
        var outputSquaredSum = 0.0
        for (sample in finiteInput) {
            outputPeak = max(outputPeak, abs(sample))
            outputSquaredSum += sample.toDouble() * sample.toDouble()
        }
        val outputRms = sqrt(outputSquaredSum / finiteInput.size).toFloat()

        return Result(
            samples = output,
            stats = Stats(
                inputPeak = inputPeak,
                inputRms = inputRms,
                inputMean = mean,
                inputClippedPercent = clippedPercent,
                outputPeak = outputPeak,
                outputRms = outputRms,
                addedTailMs = TAIL_SILENCE_MS
            )
        )
    }

    /**
     * In-place second-order low-pass Butterworth filter (biquad). Uses
     * normalized coefficients and Direct Form I to remain stable at 16 kHz.
     */
    private fun applyButterworthLowPass(
        samples: FloatArray,
        sampleRate: Int,
        cutoffHz: Double
    ) {
        val omega = 2.0 * Math.PI * cutoffHz / sampleRate
        val cosine = kotlin.math.cos(omega)
        val sine = kotlin.math.sin(omega)
        val q = 1.0 / kotlin.math.sqrt(2.0)
        val alpha = sine / (2.0 * q)
        val a0 = 1.0 + alpha
        val b0 = ((1.0 - cosine) / 2.0) / a0
        val b1 = (1.0 - cosine) / a0
        val b2 = ((1.0 - cosine) / 2.0) / a0
        val a1 = (-2.0 * cosine) / a0
        val a2 = (1.0 - alpha) / a0

        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        for (i in samples.indices) {
            val x0 = samples[i].toDouble()
            val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            samples[i] = y0.toFloat()
            x2 = x1
            x1 = x0
            y2 = y1
            y1 = y0
        }
    }

    private const val TAIL_SILENCE_MS = 180
}
