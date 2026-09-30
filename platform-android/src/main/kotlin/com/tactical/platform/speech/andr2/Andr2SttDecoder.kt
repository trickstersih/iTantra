package com.tactical.platform.speech.andr2

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

class Andr2SttDecoder(
    vocabMapFile: File,
    vocabFile: File
) {
    private val newToOld: IntArray
    private val oldIdToToken: Array<String?>

    init {
        val map = JSONObject(vocabMapFile.readText(Charsets.UTF_8))
        val mapArray = map.getJSONArray("new_to_old")
        newToOld = IntArray(mapArray.length()) { index ->
            mapArray.getInt(index)
        }

        val vocab = JSONObject(vocabFile.readText(Charsets.UTF_8))
        var maxId = 0

        val keys = vocab.keys()
        while (keys.hasNext()) {
            val token = keys.next()
            maxId = maxOf(maxId, vocab.getInt(token))
        }

        oldIdToToken = arrayOfNulls(maxId + 1)

        val secondPass = vocab.keys()
        while (secondPass.hasNext()) {
            val token = secondPass.next()
            val id = vocab.getInt(token)
            oldIdToToken[id] = token
        }
    }

    fun decode(newIds: List<Int>): String {
        val tokenText = StringBuilder()

        for (newId in newIds) {
            require(newId in newToOld.indices) {
                "andr2 token ID " + newId +
                    " is outside map size " + newToOld.size
            }

            val oldId = newToOld[newId]
            val token = oldIdToToken.getOrNull(oldId) ?: continue
            tokenText.append(token)
        }

        return decodeGpt2ByteLevel(tokenText.toString()).trim()
    }

    private fun decodeGpt2ByteLevel(text: String): String {
        val bytes = ByteArrayOutputStream(text.length)

        for (character in text) {
            val byteValue = GPT2_UNICODE_TO_BYTE[character]
            if (byteValue != null) {
                bytes.write(byteValue)
            } else {
                bytes.write(character.toString().toByteArray(Charsets.UTF_8))
            }
        }

        return bytes.toByteArray().toString(Charsets.UTF_8)
    }

    companion object {
        private val GPT2_UNICODE_TO_BYTE: Map<Char, Int> by lazy {
            val bytes = ArrayList<Int>(256)
            val unicode = ArrayList<Int>(256)

            for (value in 33..126) {
                bytes += value
                unicode += value
            }
            for (value in 161..172) {
                bytes += value
                unicode += value
            }
            for (value in 174..255) {
                bytes += value
                unicode += value
            }

            var extra = 0
            for (value in 0..255) {
                if (value !in bytes) {
                    bytes += value
                    unicode += 256 + extra
                    extra++
                }
            }

            buildMap {
                for (index in bytes.indices) {
                    put(unicode[index].toChar(), bytes[index])
                }
            }
        }
    }
}
