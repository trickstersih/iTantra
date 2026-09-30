package com.tactical.platform.speech.backend.onnx

import com.tactical.domain.audio.AudioFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * ONNX tensor helper retained because OnnxTextToSpeech still depends on it.
 * andr2 STT does not use this class.
 */
class OnnxTensorMapper {

    fun audioFrameToInputBuffer(frame: AudioFrame): FloatBuffer {
        val sampleCount = frame.data.size / BYTES_PER_PCM16_SAMPLE
        val samples = ByteBuffer
            .wrap(frame.data)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()

        val floatBuffer = FloatBuffer.allocate(sampleCount)
        for (i in 0 until sampleCount) {
            floatBuffer.put(samples.get(i) / PCM16_MAX_MAGNITUDE)
        }
        floatBuffer.rewind()
        return floatBuffer
    }

    fun outputBufferToFloatArray(buffer: FloatBuffer): FloatArray {
        val duplicate = buffer.duplicate()
        duplicate.rewind()
        val result = FloatArray(duplicate.remaining())
        duplicate.get(result)
        return result
    }

    fun floatArrayToAudioFrame(
        samples: FloatArray,
        sampleRate: Int,
        timestamp: Long
    ): AudioFrame {
        val pcmBytes = ByteArray(samples.size * BYTES_PER_PCM16_SAMPLE)
        val pcmBuffer = ByteBuffer
            .wrap(pcmBytes)
            .order(ByteOrder.LITTLE_ENDIAN)

        for (sample in samples) {
            val clamped = sample.coerceIn(-1f, 1f)
            pcmBuffer.putShort(
                (clamped * PCM16_MAX_MAGNITUDE).toInt().toShort()
            )
        }

        val durationMs = (samples.size * 1000L) / sampleRate
        return AudioFrame(
            data = pcmBytes,
            timestamp = timestamp,
            durationMs = durationMs.coerceAtLeast(1)
        )
    }

    fun textToInputBuffer(text: String): IntBuffer {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val intBuffer = IntBuffer.allocate(bytes.size)
        for (b in bytes) {
            intBuffer.put(b.toInt() and 0xFF)
        }
        intBuffer.rewind()
        return intBuffer
    }

    companion object {
        private const val BYTES_PER_PCM16_SAMPLE = 2
        private const val PCM16_MAX_MAGNITUDE = 32767f
    }
}
