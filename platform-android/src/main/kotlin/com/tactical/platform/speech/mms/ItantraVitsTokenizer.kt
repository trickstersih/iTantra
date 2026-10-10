package com.tactical.platform.speech.mms

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.LinkedHashSet

/**
 * Character-level tokenizer for the multilingual iTantra VITS export.
 *
 * Unlike the legacy MMS model, this model uses tokens.txt and an ONNX input
 * named "x". The blank token is a symbol in each language's vocabulary, not
 * necessarily token ID 0. The inspected bundle uses "_" for nine languages
 * and "|" for Gujarati.
 */
class ItantraVitsTokenizer(
    modelDirectory: File,
    private val languageCode: String
) {
    private val config = JSONObject(
        File(modelDirectory, "config.json").readText(Charsets.UTF_8)
    )
    private val tokenIds: Map<String, Int> = readTokenIds(
        File(modelDirectory, "tokens.txt")
    )

    private val addBlank = config.optBoolean("add_blank", false)

    private val blankSymbol: String = when (languageCode) {
        "gu" -> "|"
        else -> "_"
    }

    private val blankId: Int? = tokenIds[blankSymbol]

    init {
        require(config.optString("language", languageCode) == languageCode) {
            "TTS config language does not match requested language " + languageCode
        }
        require(tokenIds.isNotEmpty()) { "tokens.txt is empty for " + languageCode }
        require(!addBlank || blankId != null) {
            "The add_blank setting is enabled, but blank symbol '" +
                blankSymbol + "' is missing from " + languageCode + "/tokens.txt"
        }
    }

    data class TokenizedText(
        val normalizedText: String,
        val ids: LongArray,
        val droppedCharacters: List<String>
    )

    fun tokenize(text: String): TokenizedText {
        require(text.isNotBlank()) { "text must not be blank" }

        // Keep the model's character vocabulary authoritative. Do not apply
        // language-independent lowercasing or Unicode normalization here.
        val input = text.replace('\r', ' ').replace('\n', ' ').trim()
        val characterIds = ArrayList<Long>()
        val acceptedText = StringBuilder()
        val dropped = LinkedHashSet<String>()

        var offset = 0
        while (offset < input.length) {
            val codePoint = input.codePointAt(offset)
            val symbol = String(Character.toChars(codePoint))
            val tokenId = tokenIds[symbol]

            if (tokenId != null) {
                characterIds.add(tokenId.toLong())
                acceptedText.append(symbol)
            } else {
                dropped.add(symbol)
            }
            offset += Character.charCount(codePoint)
        }

        require(characterIds.isNotEmpty()) {
            "None of the input characters exist in the " +
                languageCode + " tokens.txt vocabulary"
        }

        val ids = if (addBlank) {
            val blank = checkNotNull(blankId).toLong()
            LongArray(characterIds.size * 2 + 1).also { output ->
                characterIds.forEachIndexed { index, value ->
                    output[index * 2] = blank
                    output[index * 2 + 1] = value
                }
                output[output.lastIndex] = blank
            }
        } else {
            LongArray(characterIds.size) { index -> characterIds[index] }
        }

        return TokenizedText(
            normalizedText = acceptedText.toString(),
            ids = ids,
            droppedCharacters = dropped.toList()
        )
    }

    private fun readTokenIds(file: File): Map<String, Int> {
        if (!file.isFile) {
            throw IOException("Missing TTS token file: " + file.absolutePath)
        }

        val mapping = LinkedHashMap<String, Int>()
        file.readLines(Charsets.UTF_8).forEachIndexed { index, rawLine ->
            if (rawLine.isEmpty()) return@forEachIndexed

            // split at the final space so a literal space can itself be a token.
            val separator = rawLine.lastIndexOf(' ')
            if (separator < 0 || separator == rawLine.lastIndex) {
                throw IOException(
                    "Malformed tokens.txt row " + (index + 1) + ": " + rawLine
                )
            }

            val symbol = rawLine.substring(0, separator)
            val id = rawLine.substring(separator + 1).trim().toIntOrNull()
                ?: throw IOException(
                    "Invalid token ID at row " + (index + 1) + " in " + file.name
                )

            val previous = mapping[symbol]
            if (previous != null && previous != id) {
                throw IOException(
                    "Token '" + symbol + "' has conflicting IDs in " + file.name
                )
            }
            // Different symbols may intentionally share the same token ID.
            mapping[symbol] = id
        }
        return mapping
    }
}
