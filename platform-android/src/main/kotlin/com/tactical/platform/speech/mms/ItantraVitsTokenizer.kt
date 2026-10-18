package com.tactical.platform.speech.mms

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.LinkedHashSet
import java.util.Locale

/**
 * Character-level tokenizer for the multilingual iTantra VITS export.
 *
 * The token-to-ID map comes from tokens.txt. Special token IDs and the
 * add_blank/use_eos_bos switches must come from ONNX model metadata, not from
 * guessing that "_" or "|" is the blank token. Ordinary characters can have
 * ID 0, so a vocabulary symbol cannot safely identify the blank ID.
 */
class ItantraVitsTokenizer(
    modelDirectory: File,
    private val languageCode: String,
    modelMetadata: Map<String, String>
) {
    private val config = JSONObject(
        File(modelDirectory, "config.json").readText(Charsets.UTF_8)
    )
    private val tokenIds: Map<String, Int> = readTokenIds(
        File(modelDirectory, "tokens.txt")
    )
    private val metadata = modelMetadata

    private fun metadataFlag(key: String, fallback: Boolean): Boolean =
        metadata[key]?.trim()?.let {
            it == "1" || it.equals("true", ignoreCase = true)
        } ?: fallback

    private val addBlank = metadataFlag(
        "add_blank",
        config.optBoolean("add_blank", false)
    )
    private val useEosBos = metadataFlag("use_eos_bos", false)
    // These exports omit blank_id from ONNX custom metadata. The Hugging Face
    // VitsTokenizer configs use a pad_token whose vocabulary ID is 0; that ID
    // is preserved by tokens.txt and is the blank inserted by add_blank.
    private val blankId = metadata["blank_id"]?.toIntOrNull() ?: 0
    private val bosId = metadata["bos_id"]?.toIntOrNull()
    private val eosId = metadata["eos_id"]?.toIntOrNull()

    init {
        require(config.optString("language", languageCode) == languageCode) {
            "TTS config language does not match requested language " + languageCode
        }
        require(tokenIds.isNotEmpty()) { "tokens.txt is empty for " + languageCode }
        require(!addBlank || tokenIds.values.any { it == checkNotNull(blankId) }) {
            "Blank token ID " + blankId + " is absent from " + languageCode + "/tokens.txt"
        }
        require(!useEosBos || (bosId != null && eosId != null)) {
            "ONNX metadata for " + languageCode +
                " enables use_eos_bos but does not provide numeric bos_id/eos_id"
        }
    }

    data class TokenizedText(
        val normalizedText: String,
        val ids: LongArray,
        val droppedCharacters: List<String>
    )

    fun tokenize(text: String): TokenizedText {
        require(text.isNotBlank()) { "text must not be blank" }

        // Hugging Face VitsTokenizer defaults to normalize=true for MMS:
        // preserve exact vocabulary symbols, lowercase unmatched characters,
        // then discard characters outside the vocabulary (e.g. punctuation).
        val input = text.replace('\r', ' ').replace('\n', ' ').trim()
        val normalized = StringBuilder()
        val dropped = LinkedHashSet<String>()

        var offset = 0
        while (offset < input.length) {
            val codePoint = input.codePointAt(offset)
            val symbol = String(Character.toChars(codePoint))
            if (tokenIds.containsKey(symbol)) {
                normalized.append(symbol)
            } else {
                val lower = symbol.lowercase(Locale.ROOT)
                normalized.append(lower)
            }
            offset += Character.charCount(codePoint)
        }

        val normalizedInput = normalized.toString()
        val characterIds = ArrayList<Long>()
        val acceptedText = StringBuilder()
        offset = 0
        while (offset < normalizedInput.length) {
            val codePoint = normalizedInput.codePointAt(offset)
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

        // Match the model's exported Coqui-style tokenizer order:
        // map characters -> intersperse the model's declared blank ID -> BOS/EOS.
        val withBlanks = if (addBlank) {
            val blank = checkNotNull(blankId).toLong()
            LongArray(characterIds.size * 2 + 1).also { output ->
                characterIds.forEachIndexed { index, value ->
                    output[index * 2] = blank
                    output[index * 2 + 1] = value
                }
                output[output.lastIndex] = blank
            }.toList()
        } else {
            characterIds
        }

        val finalIds = if (useEosBos) {
            longArrayOf(checkNotNull(bosId).toLong()) +
                withBlanks +
                longArrayOf(checkNotNull(eosId).toLong())
        } else {
            withBlanks.toLongArray()
        }

        return TokenizedText(
            normalizedText = acceptedText.toString(),
            ids = finalIds,
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

            // Split at the final space so a literal space is itself a token.
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
