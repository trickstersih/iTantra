package com.tactical.platform.speech.mms

import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Reads model configs that were serialized by Python/Coqui.
 *
 * Python's JSON encoder may emit bare Infinity, -Infinity, or NaN values,
 * which are accepted by some Python parsers but rejected by Android's
 * org.json parser. These values are not needed by the app's tokenizer or
 * inference setup, so convert only bare non-finite numeric tokens to JSON
 * null before parsing. Text inside quoted JSON strings is left untouched.
 */
internal object TtsConfigReader {
    fun readObject(file: File): JSONObject {
        if (!file.isFile || file.length() == 0L) {
            throw IOException("TTS model config is missing: " + file.absolutePath)
        }
        return JSONObject(replaceNonFiniteNumbers(file.readText(Charsets.UTF_8)))
    }

    private fun replaceNonFiniteNumbers(source: String): String {
        val output = StringBuilder(source.length)
        var index = 0
        var insideString = false
        var escaped = false

        while (index < source.length) {
            val current = source[index]

            if (insideString) {
                output.append(current)
                if (escaped) {
                    escaped = false
                } else if (current == '\\\\') {
                    escaped = true
                } else if (current == '"') {
                    insideString = false
                }
                index++
                continue
            }

            if (current == '"') {
                insideString = true
                output.append(current)
                index++
                continue
            }

            val token = when {
                source.startsWith("-Infinity", index) -> "-Infinity"
                source.startsWith("Infinity", index) -> "Infinity"
                source.startsWith("NaN", index) -> "NaN"
                else -> null
            }

            if (token != null) {
                val afterIndex = index + token.length
                val before = if (index > 0) source[index - 1] else '\\u0000'
                val after = if (afterIndex < source.length) source[afterIndex] else '\\u0000'
                val leftBoundary = index == 0 || before.isWhitespace() ||
                    before == ':' || before == ',' || before == '['
                val rightBoundary = afterIndex == source.length || after.isWhitespace() ||
                    after == ',' || after == '}' || after == ']'

                if (leftBoundary && rightBoundary) {
                    output.append("null")
                    index = afterIndex
                    continue
                }
            }

            output.append(current)
            index++
        }

        return output.toString()
    }
}
