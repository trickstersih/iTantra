package com.tactical.platform.speech.mms

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SyspinHindiTokenizerTest {

    @Test
    fun derivesCoquiIdsAndInsertsConfiguredBlankTokens() {
        val tokenizer = tokenizerWithCharacters("अआइ")
        val result = tokenizer.tokenize("अआ")

        assertEquals("अआ", result.cleanedText)
        assertArrayEquals(
            longArrayOf(8L, 5L, 8L, 6L, 8L),
            result.ids
        )
        assertTrue(result.droppedCharacters.isEmpty())
    }

    @Test
    fun normalizesDevanagariNuktaBeforeVocabularyLookup() {
        val tokenizer = tokenizerWithCharacters("अफ़")
        val result = tokenizer.tokenize("फ\u093c")

        assertEquals("फ़", result.cleanedText)
        assertArrayEquals(longArrayOf(7L, 6L, 7L), result.ids)
        assertTrue(result.droppedCharacters.isEmpty())
    }

    @Test
    fun recordsCharactersOutsideTheConfiguredVocabulary() {
        val tokenizer = tokenizerWithCharacters("अ")
        val result = tokenizer.tokenize("अक")

        assertArrayEquals(longArrayOf(6L, 5L, 6L), result.ids)
        assertEquals(listOf("क"), result.droppedCharacters)
    }

    private fun tokenizerWithCharacters(characters: String): SyspinHindiTokenizer {
        val context = InstrumentationRegistry.getInstrumentation().context
        val configFile = File(context.cacheDir, "syspin_hindi_tokenizer_test.json")
        val characterCount = characters.codePointCount(0, characters.length)
        val vocabularySize = 4 + characterCount + 2

        configFile.writeText(
            """{
              "model": "vits",
              "use_phonemes": false,
              "enable_eos_bos_chars": false,
              "add_blank": true,
              "characters": {
                "punctuations": "!?,.",
                "characters": "$characters"
              },
              "model_args": {
                "num_chars": $vocabularySize
              }
            }""",
            Charsets.UTF_8
        )
        return SyspinHindiTokenizer(configFile)
    }
}