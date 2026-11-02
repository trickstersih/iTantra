package com.tactical.platform.speech.mms

import java.io.File
import java.text.Normalizer
import java.util.LinkedHashSet
import java.util.Locale

/**
 * Character-level tokenizer for SYSPIN English Male Coqui VITS.
 *
 * Mirrors Coqui multilingual_cleaners and VitsCharacters:
 * - Unicode NFC normalization
 * - Lowercase
 * - Symbol replacement
 * - Auxiliary symbol removal
 * - Whitespace collapse
 * - Last-occurrence-wins character mapping
 * - Blank token insertion
 *
 * The existing MMS English tokenizer remains untouched.
 */
class SyspinEnglishTokenizer(configFile: File) {

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
            "SYSPIN English config is missing: ${configFile.absolutePath}"
        }

        val config = TtsConfigReader.readObject(configFile)

        require(config.optString("model", "") == "vits") {
            "SYSPIN English config must describe a VITS model"
        }

        require(!config.optBoolean("use_phonemes", false)) {
            "SYSPIN English tokenizer expects character-level input"
        }

        require(!config.optBoolean("enable_eos_bos_chars", false)) {
            "SYSPIN English tokenizer does not support BOS/EOS tokens"
        }

        val characterConfig = config.getJSONObject("characters")

        val punctuationCodePoints =
            codePoints(characterConfig.getString("punctuations"))

        val characterCodePoints =
            codePoints(characterConfig.getString("characters"))

        require(punctuationCodePoints.isNotEmpty()) {
            "SYSPIN English punctuation vocabulary is empty"
        }

        require(characterCodePoints.isNotEmpty()) {
            "SYSPIN English character vocabulary is empty"
        }

        // Coqui vocabulary:
        // [PAD] + punctuation + characters + [BLANK]
        //
        // Duplicate symbols intentionally overwrite earlier IDs.
        val ids = LinkedHashMap<Int, Int>()

        punctuationCodePoints.forEachIndexed { index, codePoint ->
            ids[codePoint] = index + 1
        }

        characterCodePoints.forEachIndexed { index, codePoint ->
            ids[codePoint] = punctuationCodePoints.size + index + 1
        }

        blankId = punctuationCodePoints.size +
            characterCodePoints.size + 1

        addBlank = config.optBoolean("add_blank", false)

        val configuredVocabularySize = config.optJSONObject("model_args")
            ?.optInt("num_chars", -1) ?: -1

        require(
            configuredVocabularySize < 0 ||
                configuredVocabularySize == blankId + 1
        ) {
            "SYSPIN English vocabulary mismatch: config expects " +
                "$configuredVocabularySize symbols, derived ${blankId + 1}"
        }

        require(blankId == 101) {
            "Unexpected SYSPIN English blank token ID: $blankId"
        }

        require(addBlank) {
            "SYSPIN English checkpoint requires add_blank=true"
        }

        symbolToId = ids
    }

    fun tokenize(text: String): TokenizedText {
        require(text.isNotBlank()) {
            "text must not be blank"
        }

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
                dropped.add(symbol)
            } else {
                characterIds.add(id)
            }

            offset += Character.charCount(codePoint)
        }

        require(characterIds.isNotEmpty()) {
            "Text contains no characters supported by SYSPIN English vocabulary"
        }

        // Coqui intersperse_blank_char(..., True):
        // [blank, char1, blank, char2, ..., blank]
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
     * Exact multilingual_cleaners pipeline for lang=None.
     */
    fun cleanText(text: String): String {
        var result = Normalizer.normalize(
            text,
            Normalizer.Form.NFC
        ).lowercase(Locale.ROOT)

        // replace_symbols(text, lang=None)
        result = result
            .replace(';', ',')
            .replace('-', ' ')
            .replace(':', ',')

        // Do not expand '&' or remove apostrophes:
        // multilingual_cleaners passes lang=None.
        return collapseWhitespace(removeAuxSymbols(result))
    }

    private fun removeAuxSymbols(text: String): String {
        val out = StringBuilder(text.length)
        var offset = 0

        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)

            if (
                codePoint != '<'.code &&
                codePoint != '>'.code &&
                codePoint != '('.code &&
                codePoint != ')'.code &&
                codePoint != '['.code &&
                codePoint != ']'.code &&
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
                if (out.isNotEmpty()) {
                    pendingSpace = true
                }
            } else {
                if (pendingSpace) {
                    out.append(' ')
                }

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
