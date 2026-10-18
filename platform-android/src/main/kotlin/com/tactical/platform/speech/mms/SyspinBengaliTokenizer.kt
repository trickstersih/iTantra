package com.tactical.platform.speech.mms

import java.lang.StringBuilder
import java.util.Locale

/**
 * Character-level tokenizer for the SYSPIN Bengali Coqui-VITS checkpoint.
 *
 * The vocabulary IDs below are copied from the loaded checkpoint tokenizer.
 * This tokenizer is deliberately separate from the existing iTantra and MMS
 * tokenizers because those models use different vocabularies and input rules.
 */
class SyspinBengaliTokenizer {
    data class TokenizedText(
        val originalText: String,
        val cleanedText: String,
        val ids: LongArray
    )

    fun tokenize(text: String): TokenizedText {
        require(text.isNotBlank()) { "text must not be blank" }
        val cleaned = cleanText(text)
        require(cleaned.isNotEmpty()) { "text is empty after Coqui text cleaning" }

        val characterIds = ArrayList<Int>(cleaned.length)
        var offset = 0
        while (offset < cleaned.length) {
            val codePoint = cleaned.codePointAt(offset)
            val symbol = String(Character.toChars(codePoint))
            val id = SYMBOL_TO_ID[symbol] ?: throw IllegalArgumentException(
                "Unsupported character '" + symbol + "' (U+" +
                    codePoint.toString(16).uppercase(Locale.ROOT).padStart(4, '0') +
                    ") for the SYSPIN Bengali vocabulary"
            )
            characterIds.add(id)
            offset += Character.charCount(codePoint)
        }

        // Coqui VITS intersperses blanks: [blank, ch1, blank, ..., chN, blank].
        val ids = LongArray(characterIds.size * 2 + 1)
        characterIds.forEachIndexed { index, id ->
            ids[index * 2] = BLANK_ID.toLong()
            ids[index * 2 + 1] = id.toLong()
        }
        ids[ids.lastIndex] = BLANK_ID.toLong()

        return TokenizedText(
            originalText = text,
            cleanedText = cleaned,
            ids = ids
        )
    }

    /**
     * Mirrors Coqui multilingual_cleaners:
     * lowercase -> ; to , -> - to space -> : to , -> remove auxiliary
     * symbols -> collapse whitespace and trim.
     */
    fun cleanText(text: String): String {
        var result = text.lowercase(Locale.ROOT)
        result = result.replace(";", ",").replace("-", " ").replace(":", ",")
        result = removeAuxSymbols(result)
        return collapseWhitespace(result)
    }

    private fun removeAuxSymbols(text: String): String {
        val out = StringBuilder(text.length)
        var offset = 0
        while (offset < text.length) {
            val cp = text.codePointAt(offset)
            if (
                cp != '<'.code && cp != '>'.code &&
                cp != '('.code && cp != ')'.code &&
                cp != '['.code && cp != ']'.code &&
                cp != '"'.code
            ) {
                out.appendCodePoint(cp)
            }
            offset += Character.charCount(cp)
        }
        return out.toString()
    }

    private fun collapseWhitespace(text: String): String {
        val out = StringBuilder(text.length)
        var pendingSpace = false
        var offset = 0

        while (offset < text.length) {
            val cp = text.codePointAt(offset)
            if (isCoquiWhitespace(cp)) {
                if (out.isNotEmpty()) pendingSpace = true
            } else {
                if (pendingSpace) out.append(' ')
                out.appendCodePoint(cp)
                pendingSpace = false
            }
            offset += Character.charCount(cp)
        }
        return out.toString()
    }

    private fun isCoquiWhitespace(codePoint: Int): Boolean =
        Character.isWhitespace(codePoint) ||
            Character.isSpaceChar(codePoint) ||
            codePoint == 0x0085 ||
            codePoint in 0x001C..0x001F

    companion object {
        const val BLANK_ID: Int = 84

        // Preserve the checkpoint's actual symbol-to-ID mapping; do not sort it.
        private val SYMBOL_TO_ID: Map<String, Int> = linkedMapOf(
            "!" to 1,
            "¡" to 2,
            "'" to 18,
            "(" to 4,
            ")" to 5,
            "," to 34,
            "-" to 7,
            "." to 8,
            ":" to 9,
            ";" to 10,
            "¿" to 11,
            "?" to 73,
            " " to 61,
            "ৰ" to 14,
            "ক" to 15,
            "।" to 16,
            "এ" to 17,
            "প" to 19,
            "ঔ" to 20,
            "স" to 21,
            "ৗ" to 22,
            "ল" to 23,
            "দ" to 24,
            "ঈ" to 25,
            "আ" to 26,
            "ং" to 27,
            "জ" to 28,
            "্" to 29,
            "ঙ" to 30,
            "ন" to 31,
            "ছ" to 32,
            "ঁ" to 33,
            "ঊ" to 35,
            "ম" to 36,
            "ৈ" to 37,
            "হ" to 38,
            "ও" to 39,
            "ৃ" to 40,
            "গ" to 41,
            "ঝ" to 42,
            "ণ" to 43,
            "ফ" to 44,
            "অ" to 45,
            "ড" to 46,
            "ূ" to 47,
            "ী" to 48,
            "ই" to 49,
            "য়" to 50,
            "ব" to 51,
            "ি" to 52,
            "ভ" to 53,
            "ঐ" to 54,
            "ঠ" to 55,
            "ৎ" to 56,
            "ঋ" to 57,
            "থ" to 58,
            "\"" to 59,
            "ষ" to 60,
            "ট" to 62,
            "ু" to 63,
            "য" to 64,
            "ত" to 65,
            "র" to 66,
            "ো" to 67,
            "ৌ" to 68,
            "ধ" to 69,
            "খ" to 70,
            "ে" to 71,
            "ড়" to 72,
            "শ" to 74,
            "ঢ" to 75,
            "ঢ়" to 76,
            "়" to 77,
            "উ" to 78,
            "চ" to 79,
            "ঃ" to 80,
            "ঘ" to 81,
            "া" to 82,
            "ঞ" to 83
        )
    }
}
