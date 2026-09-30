package com.tactical.platform.speech.andr2

import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Andr2NpyReader {

    fun readFloatMatrix(file: File): Array<FloatArray> {
        FileInputStream(file).use { input ->
            val data = DataInputStream(input)

            val magic = ByteArray(6)
            data.readFully(magic)
            val expected = byteArrayOf(
                0x93.toByte(), 'N'.code.toByte(), 'U'.code.toByte(),
                'M'.code.toByte(), 'P'.code.toByte(), 'Y'.code.toByte()
            )
            require(magic.contentEquals(expected)) {
                "Invalid NPY file: " + file.name
            }

            val major = data.readUnsignedByte()
            data.readUnsignedByte()

            val headerLength = when (major) {
                1 -> readLeU16(data)
                2, 3 -> readLeU32(data).toInt()
                else -> error("Unsupported NPY version: " + major)
            }

            val headerBytes = ByteArray(headerLength)
            data.readFully(headerBytes)
            val header = String(headerBytes, Charsets.US_ASCII)

            require(
                header.contains("'descr': '<f4'") ||
                    header.contains("\"descr\": \"<f4\"")
            ) {
                "Expected little-endian float32 NPY: " + header
            }

            require(
                header.contains("'fortran_order': False") ||
                    header.contains("\"fortran_order\": false")
            ) {
                "Expected C-order NPY: " + header
            }

            val shape = Regex(
                """['"]shape['"]\s*:\s*\((\d+)\s*,\s*(\d+)\s*(?:,)?\)"""
            ).find(header)
                ?: error("Could not parse NPY shape: " + header)

            val rows = shape.groupValues[1].toInt()
            val columns = shape.groupValues[2].toInt()
            val byteCount = rows.toLong() * columns.toLong() * 4L

            require(byteCount <= Int.MAX_VALUE) {
                "NPY payload is too large: " + byteCount
            }

            val raw = ByteArray(byteCount.toInt())
            data.readFully(raw)

            val buffer = ByteBuffer
                .wrap(raw)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asFloatBuffer()

            return Array(rows) { row ->
                FloatArray(columns) { column ->
                    buffer.get(row * columns + column)
                }
            }
        }
    }

    private fun readLeU16(data: DataInputStream): Int {
        val b0 = data.readUnsignedByte()
        val b1 = data.readUnsignedByte()
        return b0 or (b1 shl 8)
    }

    private fun readLeU32(data: DataInputStream): Long {
        val b0 = data.readUnsignedByte().toLong()
        val b1 = data.readUnsignedByte().toLong()
        val b2 = data.readUnsignedByte().toLong()
        val b3 = data.readUnsignedByte().toLong()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }
}
