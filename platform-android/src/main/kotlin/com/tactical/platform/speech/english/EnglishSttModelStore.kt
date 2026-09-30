package com.tactical.platform.speech.english

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
class EnglishSttModelStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val rootDirectory: File
        get() = File(context.filesDir, "english_stt")

    suspend fun ensureBundledModelAvailable(): File = withContext(Dispatchers.IO) {
        if (isComplete(rootDirectory)) return@withContext rootDirectory

        val tempRoot = File(
            context.filesDir,
            ".english_stt_staging_" + System.currentTimeMillis()
        )
        tempRoot.mkdirs()

        try {
            context.assets.open(BUNDLED_ZIP_ASSET).use { input ->
                ZipInputStream(BufferedInputStream(input, BUFFER_SIZE)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) {
                            zip.closeEntry()
                            continue
                        }

                        val basename = File(
                            entry.name.replace('\\', '/')
                        ).name

                        if (basename !in REQUIRED_FILES) {
                            zip.closeEntry()
                            continue
                        }

                        val output = File(tempRoot, basename)
                        val canonicalRoot = tempRoot.canonicalFile
                        val canonicalOutput = output.canonicalFile

                        if (!canonicalOutput.path.startsWith(
                                canonicalRoot.path + File.separator
                            )
                        ) {
                            throw IOException("Unsafe ZIP entry: " + entry.name)
                        }

                        output.outputStream().use { out ->
                            zip.copyTo(out, BUFFER_SIZE)
                        }
                        zip.closeEntry()
                    }
                }
            }

            if (!isComplete(tempRoot)) {
                throw IOException(
                    "Bundled English STT model is incomplete. Expected: " +
                        REQUIRED_FILES.joinToString()
                )
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
                "Could not extract bundled English STT model: " +
                    (e.message ?: "unknown error"),
                e
            )
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    fun cleanupUnbundledModels() {
        context.filesDir.listFiles()
            ?.filter { it.name.startsWith(".english_stt_staging_") }
            ?.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun isComplete(root: File): Boolean =
        REQUIRED_FILES.all { filename ->
            val file = File(root, filename)
            file.isFile && file.length() > 0L
        }

    companion object {
        private const val BUNDLED_ZIP_ASSET = "models/english_stt.zip"
        private const val BUFFER_SIZE = 64 * 1024

        private val REQUIRED_FILES = setOf(
            "model.int8.onnx",
            "config.json",
            "vocab.txt"
        )
    }
}
