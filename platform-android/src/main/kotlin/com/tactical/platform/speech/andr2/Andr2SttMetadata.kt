package com.tactical.platform.speech.andr2

import org.json.JSONObject
import java.io.File

data class Andr2SttMetadata(
    val sampleRate: Int,
    val frames: Int,
    val eotNewId: Int,
    val prefixNewIds: Map<String, IntArray>,
    val suppressNewIds: IntArray
) {
    companion object {
        fun load(file: File): Andr2SttMetadata {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val prefixObject = root.getJSONObject("prefix_new_ids")

            val prefixMap = buildMap {
                val keys = prefixObject.keys()
                while (keys.hasNext()) {
                    val language = keys.next()
                    val array = prefixObject.getJSONArray(language)
                    put(language, IntArray(array.length()) { index -> array.getInt(index) })
                }
            }

            val suppressArray = root.getJSONArray("suppress_new_ids")
            val suppress = IntArray(suppressArray.length()) { index ->
                suppressArray.getInt(index)
            }

            return Andr2SttMetadata(
                sampleRate = root.getInt("sample_rate"),
                frames = root.getInt("frames"),
                eotNewId = root.getInt("eot_new_id"),
                prefixNewIds = prefixMap,
                suppressNewIds = suppress
            )
        }
    }
}
