package com.tactical.platform.speech.mms

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores bundled TTS models under app-private storage.
 *
 * English and Hindi continue to use the existing MMS bundle. The eight other
 * languages use the new iTantra VITS bundle. Only the selected language's
 * files are extracted; model sessions are serialized by MmsTtsEngine.
 *
 * The multilingual ZIP is intentionally a local-only asset during this test
 * branch; it is not added to Git because of its size.
 */
@Singleton
class MmsTtsModelStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val rootDirectory: File
        get() = File(context.filesDir, "tts_models")

    suspend fun ensureBundledModelAvailable(
        language: MmsTtsLanguage
    ): File = withContext(Dispatchers.IO) {
        require(language in BUNDLED_LANGUAGES) {
            "TTS model for " + language.displayName + " is not bundled in this APK"
        }

        val profile = profileFor(language)
        val destination = rootDirectory.resolve(language.modelCode)
        if (isComplete(destination, profile)) {
            return@withContext destination
        }

        val tempRoot = File(
            context.filesDir,
            ".tts_model_staging_" + language.modelCode + "_" + System.currentTimeMillis()
        )
        tempRoot.mkdirs()

        try {
            context.assets.open(profile.assetName).use { input ->
                ZipInputStream(
                    BufferedInputStream(input, BUFFER_SIZE)
                ).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val normalized = entry.name.replace('\\', '/')
                        val prefix = profile.zipRoot + "/"

                        if (!normalized.startsWith(prefix) || entry.isDirectory) {
                            zip.closeEntry()
                            continue
                        }

                        val relative = normalized.removePrefix(prefix)
                        val parts = relative.split('/')
                        if (
                            parts.size != 2 ||
                            parts[0] != profile.archiveLanguageCode ||
                            parts[1] !in profile.allowedFiles
                        ) {
                            zip.closeEntry()
                            continue
                        }

                        // Normalize archive-specific language codes (bn, gu, ...)
                        // to the existing model codes (ben, guj, ...) for app storage.
                        val output = File(tempRoot, language.modelCode + "/" + parts[1])
                        val canonicalRoot = tempRoot.canonicalFile
                        val canonicalOutput = output.canonicalFile

                        if (
                            !canonicalOutput.path.startsWith(
                                canonicalRoot.path + File.separator
                            )
                        ) {
                            throw IOException("Unsafe ZIP entry: " + entry.name)
                        }

                        output.parentFile?.mkdirs()
                        output.outputStream().use { out ->
                            zip.copyTo(out, BUFFER_SIZE)
                        }
                        zip.closeEntry()
                    }
                }
            }

            val stagedModel = tempRoot.resolve(language.modelCode)
            if (!isComplete(stagedModel, profile)) {
                throw IOException(
                    "Bundled TTS model is incomplete for " + language.displayName +
                        ". Expected files: " + profile.requiredFiles.joinToString()
                )
            }

            rootDirectory.mkdirs()
            destination.deleteRecursively()

            if (!stagedModel.renameTo(destination)) {
                stagedModel.copyRecursively(destination, overwrite = true)
                stagedModel.deleteRecursively()
            }

            destination
        } catch (e: IOException) {
            throw IOException(
                "Could not extract bundled TTS model for " +
                    language.displayName + ": " + (e.message ?: "unknown error"),
                e
            )
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    /**
     * Removes model directories for languages no longer supported by this APK.
     */
    fun cleanupUnbundledModels() {
        rootDirectory.listFiles()
            ?.filter { file ->
                file.isDirectory &&
                    BUNDLED_LANGUAGES.none { it.modelCode == file.name }
            }
            ?.forEach { file ->
                runCatching { file.deleteRecursively() }
            }
    }

    private fun isComplete(directory: File, profile: ModelProfile): Boolean {
        if (!directory.isDirectory) return false
        return profile.requiredFiles.all { name ->
            val file = File(directory, name)
            file.isFile && file.length() > 0L
        }
    }

    private fun profileFor(language: MmsTtsLanguage): ModelProfile {
        return if (language.modelCode in LEGACY_MMS_CODES) {
            ModelProfile(
                assetName = LEGACY_BUNDLED_ZIP_ASSET,
                zipRoot = LEGACY_ZIP_ROOT,
                archiveLanguageCode = language.modelCode,
                allowedFiles = LEGACY_ALLOWED_FILES,
                requiredFiles = LEGACY_REQUIRED_FILES
            )
        } else {
            ModelProfile(
                assetName = ITANTRA_BUNDLED_ZIP_ASSET,
                zipRoot = ITANTRA_ZIP_ROOT,
                archiveLanguageCode = language.isoCode,
                allowedFiles = ITANTRA_ALLOWED_FILES,
                requiredFiles = ITANTRA_REQUIRED_FILES
            )
        }
    }

    private data class ModelProfile(
        val assetName: String,
        val zipRoot: String,
        val archiveLanguageCode: String,
        val allowedFiles: Set<String>,
        val requiredFiles: Set<String>
    )

    companion object {
        private const val LEGACY_BUNDLED_ZIP_ASSET = "models/quantized_models.zip"
        private const val LEGACY_ZIP_ROOT = "quantized_models"

        // The new ZIP is a local asset at app/src/main/assets/models/ during testing.
        private const val ITANTRA_BUNDLED_ZIP_ASSET = "models/itantra-tts.zip"
        private const val ITANTRA_ZIP_ROOT = ".itantra-tts-onnx"
        private const val BUFFER_SIZE = 64 * 1024

        private val LEGACY_MMS_CODES = setOf("eng", "hin")
        private val BUNDLED_LANGUAGES = MmsTtsLanguage.ALL

        private val LEGACY_ALLOWED_FILES = setOf(
            "config.json",
            "tokenizer_config.json",
            "vocab.json",
            "tokens.txt",
            "model.int8.onnx"
        )

        private val LEGACY_REQUIRED_FILES = setOf(
            "config.json",
            "tokenizer_config.json",
            "vocab.json",
            "model.int8.onnx"
        )

        private val ITANTRA_ALLOWED_FILES = setOf(
            "config.json",
            "tokens.txt",
            "model.int8.mixed.onnx"
        )

        private val ITANTRA_REQUIRED_FILES = setOf(
            "config.json",
            "tokens.txt",
            "model.int8.mixed.onnx"
        )
    }
}
