package com.tactical.platform.speech.mms

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SyspinEnglishTokenizerParityTest {

    @Test
    fun matchesPythonGoldenTokenIds() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val configFile = File(context.cacheDir, "syspin_english_config.json")

        context.assets.open("syspin_english/config.json").use { input ->
            configFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        val golden = JSONObject(
            context.assets.open("syspin_english/english_tokenizer_golden.json")
                .bufferedReader()
                .use { it.readText() }
        )
        val samples = golden.getJSONArray("samples")
        val tokenizer = SyspinEnglishTokenizer(configFile)

        assertEquals("Expected six Python golden samples", 6, samples.length())

        for (index in 0 until samples.length()) {
            val sample = samples.getJSONObject(index)
            val text = sample.getString("text")
            val expectedJson = sample.getJSONArray("token_ids")
            val expected = LongArray(expectedJson.length()) {
                expectedJson.getLong(it)
            }

            val actual = tokenizer.tokenize(text)

            assertEquals(
                "Cleaned text mismatch for: $text",
                sample.getString("cleaned_text"),
                actual.cleanedText
            )
            assertEquals(
                "Token count mismatch for: $text",
                expected.size,
                actual.ids.size
            )
            assertArrayEquals(
                "Token IDs differ from Python for: $text",
                expected,
                actual.ids
            )
            println("PASSED: $text")
        }

        println("ALL SIX PYTHON/KOTLIN TOKENIZER TESTS PASSED")
    }
}
