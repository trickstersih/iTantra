import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.DisableCachingByDefault
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@DisableCachingByDefault(because = "Downloads and packages a remote model archive")
abstract class PrepareMoonshineModelTask : DefaultTask() {

    @get:OutputFile
    abstract val outputArchive: RegularFileProperty

    @get:Input
    abstract val baseUrl: Property<String>

    @get:InputFiles
    abstract val modelFiles: ListProperty<String>

    @TaskAction
    fun prepare() {
        val output = outputArchive.get().asFile

        if (output.isFile && output.length() > 0L) {
            logger.lifecycle(
                "Moonshine Tiny Streaming model already present: ${output.name}"
            )
            return
        }

        output.parentFile.mkdirs()
        val tempZip = File(output.parentFile, ".${output.name}.tmp")

        try {
            logger.lifecycle("Downloading Moonshine Tiny Streaming English model...")

            ZipOutputStream(tempZip.outputStream().buffered()).use { zip ->
                modelFiles.get().forEach { fileName ->
                    val url = URL("${baseUrl.get().trimEnd('/')}/$fileName")
                    val connection = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 20_000
                        readTimeout = 120_000
                        setRequestProperty(
                            "User-Agent",
                            "Itantra-Gradle-Moonshine/1.0"
                        )
                        instanceFollowRedirects = true
                    }

                    try {
                        val status = connection.responseCode
                        check(status in 200..299) {
                            "HTTP $status while downloading $fileName"
                        }

                        zip.putNextEntry(
                            ZipEntry("moonshine_stt_tiny_en/$fileName")
                        )
                        connection.inputStream.buffered().use { input ->
                            input.copyTo(zip, 64 * 1024)
                        }
                        zip.closeEntry()

                        logger.lifecycle("  downloaded $fileName")
                    } finally {
                        connection.disconnect()
                    }
                }
            }

            check(tempZip.isFile && tempZip.length() > 0L) {
                "Moonshine model archive was not created"
            }

            if (output.exists()) {
                check(output.delete()) {
                    "Could not replace existing ${output.absolutePath}"
                }
            }

            check(tempZip.renameTo(output)) {
                "Could not move temporary Moonshine model archive into place"
            }

            logger.lifecycle(
                "Moonshine Tiny Streaming model ready: ${output.length()} bytes"
            )
        } catch (e: Exception) {
            tempZip.delete()
            throw org.gradle.api.GradleException(
                "Could not prepare bundled Moonshine Tiny Streaming English model. " +
                    "Check internet access and try the build again.",
                e
            )
        }
    }
}
