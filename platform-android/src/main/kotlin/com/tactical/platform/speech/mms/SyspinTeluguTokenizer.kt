package com.tactical.platform.speech.mms

import java.io.File
import java.util.LinkedHashSet
import java.util.Locale

/**
 * Character-level tokenizer for the SYSPIN Telugu Coqui-VITS checkpoint.
 *
 * Derives character IDs from the bundled config using Coqui's VitsCharacters
 * ordering: pad, configured punctuation, configured characters, then blank.
 * Duplicate characters intentionally override earlier punctuation IDs, matching
 * Coqui's effective character-to-ID mapping.
 */
class SyspinTeluguTokenizer(configFile: File) {
    data class TokenizedText(
        val originalText: String,
        val cleanedText: String,
        val ids: LongArray,
        val droppedCharacters: List<String>
    )

    private val symbolToId: Map<Int, Int>
    private val blankId: Int
    private val addBlank: Boolean

    init {
        require(configFile.isFile && configFile.length() > 0L) {
            "SYSPIN Telugu config is missing: " + configFile.absolutePath
        }
        val config = TtsConfigReader.readObject(configFile)
        require(config.optString("model", "") == "vits") {
            "SYSPIN Telugu config must describe a VITS model"
        }
        require(!config.optBoolean("use_phonemes", false)) {
            "SYSPIN Telugu tokenizer supports the checkpoint's character vocabulary only"
        }

        val characterConfig = config.getJSONObject("characters")
        val punctuationCodePoints = codePoints(characterConfig.getString("punctuations"))
        val characterCodePoints = codePoints(characterConfig.getString("characters"))
        require(punctuationCodePoints.isNotEmpty()) {
            "SYSPIN Telugu config has an empty punctuation vocabulary"
        }
        require(characterCodePoints.isNotEmpty()) {
            "SYSPIN Telugu config has an empty character vocabulary"
        }

        val ids = LinkedHashMap<Int, Int>()
        punctuationCodePoints.forEachIndexed { index, codePoint ->
            ids[codePoint] = index + 1
        }
        characterCodePoints.forEachIndexed { index, codePoint ->
            // Like Coqui's dictionary construction, later character entries
            // override punctuation IDs when a symbol appears in both lists.
            ids[codePoint] = punctuationCodePoints.size + index + 1
        }

        blankId = punctuationCodePoints.size + characterCodePoints.size + 1
        addBlank = config.optBoolean("add_blank", false)
        val configuredVocabularySize = config.optJSONObject("model_args")
            ?.optInt("num_chars", -1) ?: -1
        require(configuredVocabularySize < 0 || configuredVocabularySize == blankId + 1) {
            "SYSPIN Telugu vocabulary mismatch: config expects " +
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
                // Coqui TTSTokenizer drops out-of-vocabulary symbols rather than
                // failing the whole utterance. Log these at the engine boundary.
                dropped.add(symbol)
            } else {
                characterIds.add(id)
            }
            offset += Character.charCount(codePoint)
        }
        require(characterIds.isNotEmpty()) {
            "Text contains no characters supported by the SYSPIN Telugu vocabulary"
        }

        val tokenIds = if (addBlank) {
            // Coqui add_blank=true: [blank, ch1, blank, ..., chN, blank].
            LongArray(characterIds.size * 2 + 1).also { output ->
                output[0] = blankId.toLong()
                characterIds.forEachIndexed { index, id ->
                    output[index * 2 + 1] = id.toLong()
                    output[index * 2 + 2] = blankId.toLong()
                }
            }
        } else {
            characterIds.map { it.toLong() }.toLongArray()
        }

        return TokenizedText(
            originalText = text,
            cleanedText = cleaned,
            ids = tokenIds,
            droppedCharacters = dropped.toList()
        )
    }

    /**
     * Mirrors the existing SYSPIN Coqui multilingual_cleaners implementation:
     * lowercase, normalize common punctuation, remove auxiliary symbols, and
     * collapse whitespace.
     */
    fun cleanText(text: String): String {
        var result = text.lowercase(Locale.ROOT)
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
