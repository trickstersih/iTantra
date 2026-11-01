package com.tactical.platform.speech.mms

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.LinkedHashMap
import java.util.LinkedHashSet

/**
 * Character-level tokenizer for the selected samprabin/tamil_vits Coqui VITS model.
 *
 * Its tokens.txt is generated from the effective Coqui TTSTokenizer so the IDs
 * match the tested ONNX inputs. The blank token is explicitly <BLNK> (ID 55).
 */
class TamilVitsTokenizer(modelDirectory: File) {
    private val config = TtsConfigReader.readObject(File(modelDirectory, "config.json"))
    private val tokenIds = readTokenIds(File(modelDirectory, "tokens.txt"))
    private val addBlank = config.optBoolean("add_blank", false)
    private val blankId: Int = tokenIds["<BLNK>"]
        ?: throw IllegalArgumentException(
            "Tamil VITS token map must include the model blank token <BLNK>"
        )

    init {
        require(!config.optBoolean("use_phonemes", false)) {
            "Tamil Coqui VITS test adapter supports the model's character tokenizer only"
        }
        require(tokenIds.isNotEmpty()) { "Tamil VITS tokens.txt is empty" }
        val expectedVocabSize = config.optJSONObject("model_args")
            ?.optInt("num_chars", tokenIds.size) ?: tokenIds.size
        require(tokenIds.values.toSet().size == expectedVocabSize) {
            "Tamil VITS token map size does not match config model_args.num_chars"
        }
        require(tokenIds.values.sorted() == (0 until expectedVocabSize).toList()) {
            "Tamil VITS token IDs must be contiguous from zero"
        }
        require(!addBlank || tokenIds.values.any { it == blankId }) {
            "Tamil VITS blank ID is missing from tokens.txt"
        }
    }

    data class TokenizedText(
        val normalizedText: String,
        val ids: LongArray,
        val droppedCharacters: List<String>
    )

    fun tokenize(text: String): TokenizedText {
        require(text.isNotBlank()) { "text must not be blank" }

        // Preserve Tamil characters and punctuation as encoded in the source
        // tokenizer; normalize line breaks to spaces, then report unknown code points.
        val input = text.replace('\r', ' ').replace('\n', ' ').trim()
        val tokenizedCharacters = ArrayList<Long>()
        val acceptedText = StringBuilder()
        val dropped = LinkedHashSet<String>()

        var offset = 0
        while (offset < input.length) {
            val codePoint = input.codePointAt(offset)
            val symbol = String(Character.toChars(codePoint))
            val id = tokenIds[symbol]
            if (id != null && symbol != "<BLNK>") {
                tokenizedCharacters.add(id.toLong())
                acceptedText.append(symbol)
            } else {
                dropped.add(symbol)
            }
            offset += Character.charCount(codePoint)
        }

        require(tokenizedCharacters.isNotEmpty()) {
            "None of the input characters exist in the Tamil VITS vocabulary"
        }

        val finalIds = if (addBlank) {
            LongArray(tokenizedCharacters.size * 2 + 1).also { output ->
                tokenizedCharacters.forEachIndexed { index, value ->
                    output[index * 2] = blankId.toLong()
                    output[index * 2 + 1] = value
                }
                output[output.lastIndex] = blankId.toLong()
            }
        } else {
            tokenizedCharacters.toLongArray()
        }

        return TokenizedText(
            normalizedText = acceptedText.toString(),
            ids = finalIds,
            droppedCharacters = dropped.toList()
        )
    }

    private fun readTokenIds(file: File): Map<String, Int> {
        if (!file.isFile) {
            throw IOException("Missing Tamil VITS token file: " + file.absolutePath)
        }

        val mapping = LinkedHashMap<String, Int>()
        file.readLines(Charsets.UTF_8).forEachIndexed { index, rawLine ->
            if (rawLine.isEmpty()) return@forEachIndexed

            // Split on the last space so the literal space character can be a token.
            val separator = rawLine.lastIndexOf(' ')
            if (separator < 0 || separator == rawLine.lastIndex) {
                throw IOException(
                    "Malformed Tamil VITS tokens.txt row " + (index + 1) + ": " + rawLine
                )
            }

            val symbol = rawLine.substring(0, separator)
            val id = rawLine.substring(separator + 1).trim().toIntOrNull()
                ?: throw IOException(
                    "Invalid Tamil VITS token ID at row " + (index + 1)
                )

            val previous = mapping[symbol]
            if (previous != null && previous != id) {
                throw IOException(
                    "Tamil VITS symbol '" + symbol + "' has conflicting token IDs"
                )
            }
            mapping[symbol] = id
        }
        return mapping
    }
}
