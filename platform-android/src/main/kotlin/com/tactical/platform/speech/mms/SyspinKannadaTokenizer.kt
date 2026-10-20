package com.tactical.platform.speech.mms

import org.json.JSONObject
import java.io.File
import java.util.LinkedHashSet
import java.util.Locale

/**
 * Character-level tokenizer for the SYSPIN Kannada Coqui-VITS checkpoint.
 *
 * Builds its IDs from the bundled config in the same order as Coqui's
 * VitsCharacters: [pad] + punctuation + characters + [blank]. Duplicate
 * symbols in the characters string intentionally override earlier punctuation
 * IDs, matching Coqui's char-to-ID dictionary construction.
 */
class SyspinKannadaTokenizer(configFile: File) {
    data class TokenizedText(
        val originalText: String,
        val cleanedText: String,
        val ids: LongArray,
        val droppedCharacters: List<String>
    )

    private val symbolToId: Map<Int, Int>
    private val blankId: Int

    init {
        require(configFile.isFile && configFile.length() > 0L) {
            "SYSPIN Kannada config is missing: " + configFile.absolutePath
        }
        val config = JSONObject(configFile.readText(Charsets.UTF_8))
        val characterConfig = config.getJSONObject("characters")
        val punctuationCodePoints = codePoints(characterConfig.getString("punctuations"))
        val characterCodePoints = codePoints(characterConfig.getString("characters"))

        require(punctuationCodePoints.isNotEmpty()) {
            "SYSPIN Kannada config has an empty punctuation vocabulary"
        }
        require(characterCodePoints.isNotEmpty()) {
            "SYSPIN Kannada config has an empty character vocabulary"
        }

        val ids = LinkedHashMap<Int, Int>()
        punctuationCodePoints.forEachIndexed { index, codePoint ->
            ids[codePoint] = index + 1
        }
        characterCodePoints.forEachIndexed { index, codePoint ->
            // Characters follow the pad token and all punctuation IDs. Like
            // Coqui's dictionary comprehension, this overwrites duplicate
            // punctuation entries that are also present in the character set.
            ids[codePoint] = punctuationCodePoints.size + index + 1
        }

        blankId = punctuationCodePoints.size + characterCodePoints.size + 1
        val modelArgs = config.optJSONObject("model_args")
        val configuredVocabularySize = modelArgs?.optInt("num_chars", -1) ?: -1
        require(configuredVocabularySize < 0 || configuredVocabularySize == blankId + 1) {
            "SYSPIN Kannada vocabulary mismatch: config expects " +
                configuredVocabularySize + " symbols, derived " + (blankId + 1)
        }
        symbolToId = ids
    }

    fun tokenize(text: String): TokenizedText {
        require(text.isNotBlank()) { "text must not be blank" }
        val cleaned = cleanText(text)
        require(cleaned.isNotEmpty()) {
            "text is empty after Coqui multilingual_cleaners"
        }

        val characterIds = ArrayList<Int>(cleaned.length)
        val dropped = LinkedHashSet<String>()
        var offset = 0
        while (offset < cleaned.length) {
            val codePoint = cleaned.codePointAt(offset)
            val symbol = String(Character.toChars(codePoint))
            val id = symbolToId[codePoint]
            if (id == null) {
                // Coqui TTSTokenizer records and discards out-of-vocabulary
                // symbols rather than failing the entire utterance.
                dropped.add(symbol)
            } else {
                characterIds.add(id)
            }
            offset += Character.charCount(codePoint)
        }
        require(characterIds.isNotEmpty()) {
            "text contains no characters supported by the SYSPIN Kannada vocabulary"
        }

        // Coqui add_blank=true: [blank, ch1, blank, ..., chN, blank].
        val tokenIds = LongArray(characterIds.size * 2 + 1)
        tokenIds[0] = blankId.toLong()
        characterIds.forEachIndexed { index, id ->
            tokenIds[index * 2 + 1] = id.toLong()
            tokenIds[index * 2 + 2] = blankId.toLong()
        }

        return TokenizedText(
            originalText = text,
            cleanedText = cleaned,
            ids = tokenIds,
            droppedCharacters = dropped.toList()
        )
    }

    /**
     * Mirrors Coqui multilingual_cleaners:
     * lowercase, normalize basic symbols, remove auxiliary symbols, collapse whitespace.
     */
    fun cleanText(text: String): String {
        var result = text.lowercase(Locale.ROOT)
        result = result
            .replace(";", ",")
            .replace("-", " ")
            .replace(":", ",")
            .replace("'", "")
        result = removeAuxSymbols(result)
        return collapseWhitespace(result)
    }

    private fun removeAuxSymbols(text: String): String {
        val out = StringBuilder(text.length)
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            if (
                codePoint != '<'.code && codePoint != '>'.code &&
                codePoint != '('.code && codePoint != ')'.code &&
                codePoint != '['.code && codePoint != ']'.code &&
                codePoint != '"'.code
            ) {
                out.appendCodePoint(codePoint)
            }
            offset += Character.charCount(codePoint)
        }
        return out.toString()
    }

    private fun collapseWhitespace(text: String): String {
        val out = StringBuilder(text.length)
        var pendingSpace = false
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            if (isCoquiWhitespace(codePoint)) {
                if (out.isNotEmpty()) pendingSpace = true
            } else {
                if (pendingSpace) out.append(' ')
                out.appendCodePoint(codePoint)
                pendingSpace = false
            }
            offset += Character.charCount(codePoint)
        }
        return out.toString()
    }

    private fun isCoquiWhitespace(codePoint: Int): Boolean =
        Character.isWhitespace(codePoint) ||
            Character.isSpaceChar(codePoint) ||
            codePoint == 0x0085 ||
            codePoint in 0x001C..0x001F

    private fun codePoints(value: String): List<Int> {
        val result = ArrayList<Int>(value.length)
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            result.add(codePoint)
            offset += Character.charCount(codePoint)
        }
        return result
    }
}
