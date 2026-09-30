package com.tactical.platform.speech.andr2

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

@Singleton
class Andr2SttModelStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val rootDirectory: File
        get() = File(context.filesDir, "andr2_stt")

    suspend fun ensureBundledModelAvailable(): File = withContext(Dispatchers.IO) {
        if (isComplete(rootDirectory)) return@withContext rootDirectory

        val tempRoot = File(
            context.filesDir,
            ".andr2_stt_staging_" + System.currentTimeMillis()
        )
        tempRoot.mkdirs()

        try {
            context.assets.open(BUNDLED_ZIP_ASSET).use { input ->
                ZipInputStream(BufferedInputStream(input, BUFFER_SIZE)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val normalized = entry.name.replace('\\', '/')
                        val prefix = ZIP_ROOT + "/"

                        if (!normalized.startsWith(prefix) || entry.isDirectory) {
                            zip.closeEntry()
                            continue
                        }

                        val relative = normalized.removePrefix(prefix)
                        if (relative !in REQUIRED_FILES) {
                            zip.closeEntry()
                            continue
                        }

                        val output = File(tempRoot, relative)
                        val canonicalRoot = tempRoot.canonicalFile
                        val canonicalOutput = output.canonicalFile

                        if (!canonicalOutput.path.startsWith(
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

            if (!isComplete(tempRoot)) {
                throw IOException("Bundled andr2 STT model is incomplete")
            }

            rootDirectory.deleteRecursively()
            rootDirectory.parentFile?.mkdirs()

            if (!tempRoot.renameTo(rootDirectory)) {
                tempRoot.copyRecursively(rootDirectory, overwrite = true)
                tempRoot.deleteRecursively()
            }

            rootDirectory
        } catch (e: IOException) {
            throw IOException(
                "Could not extract bundled andr2 STT model: " +
                    (e.message ?: "unknown error"),
                e
            )
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    fun cleanupUnbundledModels() {
        context.filesDir.listFiles()
            ?.filter { it.name.startsWith(".andr2_stt_staging_") }
            ?.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun isComplete(root: File): Boolean =
        REQUIRED_FILES.all { relative ->
            val file = File(root, relative)
            file.isFile && file.length() > 0L
        }

    companion object {
        private const val BUNDLED_ZIP_ASSET = "models/andr2.zip"
        private const val ZIP_ROOT = "andr2"
        private const val BUFFER_SIZE = 64 * 1024

        private val REQUIRED_FILES = setOf(
            "encoder_int8.onnx",
            "decoder_fp32.onnx",
            "mel_filters_80x201.npy",
            "preprocess.json",
            "vocab_map.json",
            "tokenizer/vocab.json"
        )
    }
}
