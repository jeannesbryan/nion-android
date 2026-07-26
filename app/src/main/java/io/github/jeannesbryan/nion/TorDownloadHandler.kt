package io.github.jeannesbryan.nion

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.URLUtil
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors

object TorDownloadHandler {

    data class Result(
        val fileName: String,
        val location: String
    )

    private val executor =
        Executors.newFixedThreadPool(2)

    fun suggestedFileName(
        response: WebResponse
    ): String {
        val disposition =
            response.headers["content-disposition"]

        val mimeType =
            mimeType(response)

        val guessed =
            URLUtil.guessFileName(
                response.uri,
                disposition,
                mimeType
            )

        val sanitized =
            guessed
                .replace(
                    Regex("""[\\/:*?"<>|\p{Cntrl}]"""),
                    "_"
                )
                .trim()
                .trim('.')
                .take(180)

        return sanitized.ifBlank {
            "download.bin"
        }
    }

    fun mimeType(
        response: WebResponse
    ): String {
        return response
            .headers["content-type"]
            ?.substringBefore(';')
            ?.trim()
            ?.takeIf {
                it.isNotEmpty()
            }
            ?: "application/octet-stream"
    }

    fun contentLength(
        response: WebResponse
    ): Long? {
        return response
            .headers["content-length"]
            ?.trim()
            ?.toLongOrNull()
            ?.takeIf {
                it >= 0
            }
    }

    fun discard(
        response: WebResponse
    ) {
        try {
            response.body?.close()
        } catch (_: Exception) {
        }
    }

    fun save(
        context: Context,
        response: WebResponse,
        fileName: String,
        onSuccess: (Result) -> Unit,
        onFailure: (String) -> Unit
    ) {
        val body =
            response.body

        if (body == null) {
            onFailure(
                "Download contains no data"
            )
            return
        }

        executor.execute {
            try {
                val result =
                    if (
                        Build.VERSION.SDK_INT >=
                        Build.VERSION_CODES.Q
                    ) {
                        saveToPublicDownloads(
                            context,
                            body,
                            fileName,
                            mimeType(response)
                        )
                    } else {
                        saveToAppDownloads(
                            context,
                            body,
                            fileName
                        )
                    }

                onSuccess(result)

            } catch (e: Exception) {
                try {
                    body.close()
                } catch (_: Exception) {
                }

                onFailure(
                    e.message
                        ?: "Download failed"
                )
            }
        }
    }

    private fun saveToPublicDownloads(
        context: Context,
        input: InputStream,
        fileName: String,
        mimeType: String
    ): Result {
        val resolver =
            context.contentResolver

        val values =
            ContentValues().apply {
                put(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    fileName
                )

                put(
                    MediaStore.MediaColumns.MIME_TYPE,
                    mimeType
                )

                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS
                )

                put(
                    MediaStore.MediaColumns.IS_PENDING,
                    1
                )
            }

        val uri =
            resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            )
                ?: throw IllegalStateException(
                    "Cannot create download file"
                )

        try {
            resolver
                .openOutputStream(
                    uri,
                    "w"
                )
                ?.use { output ->
                    input.use {
                        it.copyTo(
                            output,
                            64 * 1024
                        )
                    }
                }
                ?: throw IllegalStateException(
                    "Cannot open download file"
                )

            val finished =
                ContentValues().apply {
                    put(
                        MediaStore.MediaColumns.IS_PENDING,
                        0
                    )
                }

            resolver.update(
                uri,
                finished,
                null,
                null
            )

            return Result(
                fileName = fileName,
                location =
                    "${Environment.DIRECTORY_DOWNLOADS}/$fileName"
            )

        } catch (e: Exception) {
            try {
                resolver.delete(
                    uri,
                    null,
                    null
                )
            } catch (_: Exception) {
            }

            throw e
        }
    }

    private fun saveToAppDownloads(
        context: Context,
        input: InputStream,
        fileName: String
    ): Result {
        val directory =
            context.getExternalFilesDir(
                Environment.DIRECTORY_DOWNLOADS
            )
                ?: File(
                    context.filesDir,
                    "downloads"
                )

        if (
            !directory.exists() &&
            !directory.mkdirs()
        ) {
            throw IllegalStateException(
                "Cannot create download directory"
            )
        }

        val outputFile =
            uniqueFile(
                directory,
                fileName
            )

        try {
            outputFile
                .outputStream()
                .buffered()
                .use { output ->
                    input.use {
                        it.copyTo(
                            output,
                            64 * 1024
                        )
                    }
                }

        } catch (e: Exception) {
            outputFile.delete()
            throw e
        }

        return Result(
            fileName =
                outputFile.name,

            location =
                outputFile.absolutePath
        )
    }

    private fun uniqueFile(
        directory: File,
        fileName: String
    ): File {
        var candidate =
            File(
                directory,
                fileName
            )

        if (!candidate.exists()) {
            return candidate
        }

        val dot =
            fileName.lastIndexOf('.')

        val hasExtension =
            dot > 0 &&
            dot < fileName.lastIndex

        val base =
            if (hasExtension) {
                fileName.substring(
                    0,
                    dot
                )
            } else {
                fileName
            }

        val extension =
            if (hasExtension) {
                fileName.substring(dot)
            } else {
                ""
            }

        var counter = 1

        while (
            candidate.exists() &&
            counter < 10000
        ) {
            candidate =
                File(
                    directory,
                    "$base ($counter)$extension"
                )

            counter++
        }

        return candidate
    }
}
