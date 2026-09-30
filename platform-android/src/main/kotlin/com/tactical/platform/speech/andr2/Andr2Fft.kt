package com.tactical.platform.speech.andr2

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Dependency-free mixed-radix FFT. Used by both andr2 (400-point) and the
 * English Conformer backend (512-point) without bringing in another FFT lib.
 */
class Andr2Fft(private val size: Int = 400) {
    private val cosTables = HashMap<Int, DoubleArray>()
    private val sinTables = HashMap<Int, DoubleArray>()

    fun forward(input: DoubleArray): Pair<DoubleArray, DoubleArray> {
        require(input.size == size) {
            "FFT expected " + size + " samples, got " + input.size
        }

        val real = input.copyOf()
        val imag = DoubleArray(size)
        fftRecursive(real, imag)
        return real to imag
    }

    private fun fftRecursive(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        if (n <= 1) return

        val radix = smallestFactor(n)
        val m = n / radix

        val subReal = Array(radix) { DoubleArray(m) }
        val subImag = Array(radix) { DoubleArray(m) }

        for (r in 0 until radix) {
            var source = r
            var k = 0
            while (k < m) {
                subReal[r][k] = real[source]
                subImag[r][k] = imag[source]
                source += radix
                k++
            }
            fftRecursive(subReal[r], subImag[r])
        }

        val outReal = DoubleArray(n)
        val outImag = DoubleArray(n)
        val cosTable = cosTable(n)
        val sinTable = sinTable(n)

        for (k in 0 until m) {
            for (q in 0 until radix) {
                val outputIndex = k + q * m
                var sumReal = 0.0
                var sumImag = 0.0

                for (r in 0 until radix) {
                    val index = (r * outputIndex) % n
                    val wr = cosTable[index]
                    val wi = -sinTable[index]
                    val ar = subReal[r][k]
                    val ai = subImag[r][k]

                    sumReal += ar * wr - ai * wi
                    sumImag += ar * wi + ai * wr
                }

                outReal[outputIndex] = sumReal
                outImag[outputIndex] = sumImag
            }
        }

        for (i in 0 until n) {
            real[i] = outReal[i]
            imag[i] = outImag[i]
        }
    }

    private fun smallestFactor(value: Int): Int {
        var factor = 2
        while (factor * factor <= value) {
            if (value % factor == 0) return factor
            factor++
        }
        return value
    }

    private fun cosTable(n: Int): DoubleArray =
        cosTables.getOrPut(n) {
            DoubleArray(n) { i ->
                cos(2.0 * PI * i.toDouble() / n.toDouble())
            }
        }

    private fun sinTable(n: Int): DoubleArray =
        sinTables.getOrPut(n) {
            DoubleArray(n) { i ->
                sin(2.0 * PI * i.toDouble() / n.toDouble())
            }
        }
}
