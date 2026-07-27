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
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object TorDownloadHandler {
    data class Result(
        val fileName: String,
        val location: String,
        val contentUri: String?,
        val mimeType: String
    )

    class Handle internal constructor(
        val id: String,
        private val cancelled: AtomicBoolean,
        private val input: InputStream
    ) {
        fun cancel() {
            cancelled.set(true)
            try { input.close() } catch (_: Exception) {}
        }
    }

    private class DownloadCancelled : Exception("Download cancelled")
    private val executor = Executors.newFixedThreadPool(2)
    private val handles = ConcurrentHashMap<String, Handle>()

    fun suggestedFileName(response: WebResponse): String {
        val guessed = URLUtil.guessFileName(
            response.uri,
            response.headers["content-disposition"],
            mimeType(response)
        )
        return guessed
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim().trim('.').take(180)
            .ifBlank { "download.bin" }
    }

    fun mimeType(response: WebResponse): String =
        response.headers["content-type"]
            ?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }
            ?: "application/octet-stream"

    fun contentLength(response: WebResponse): Long? =
        response.headers["content-length"]?.trim()?.toLongOrNull()?.takeIf { it >= 0 }

    fun discard(response: WebResponse) {
        try { response.body?.close() } catch (_: Exception) {}
    }

    fun save(
        context: Context,
        id: String,
        response: WebResponse,
        fileName: String,
        onProgress: (Long, Long?) -> Unit,
        onSuccess: (Result) -> Unit,
        onFailure: (String) -> Unit,
        onCancelled: () -> Unit
    ): Handle? {
        val body = response.body ?: run {
            onFailure("Download contains no data")
            return null
        }
        val cancelled = AtomicBoolean(false)
        val handle = Handle(id, cancelled, body)
        handles[id] = handle
        val total = contentLength(response)
        val mime = mimeType(response)

        executor.execute {
            try {
                val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    savePublic(context, body, fileName, mime, total, cancelled, onProgress)
                } else {
                    saveApp(context, body, fileName, mime, total, cancelled, onProgress)
                }
                if (cancelled.get()) throw DownloadCancelled()
                onSuccess(result)
            } catch (_: DownloadCancelled) {
                onCancelled()
            } catch (e: Exception) {
                if (cancelled.get()) onCancelled()
                else onFailure(e.message ?: "Download failed")
            } finally {
                handles.remove(id)
                try { body.close() } catch (_: Exception) {}
            }
        }
        return handle
    }

    fun cancel(id: String): Boolean {
        val handle = handles[id] ?: return false
        handle.cancel()
        return true
    }

    private fun savePublic(
        context: Context,
        input: InputStream,
        fileName: String,
        mime: String,
        total: Long?,
        cancelled: AtomicBoolean,
        onProgress: (Long, Long?) -> Unit
    ): Result {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Cannot create download file")
        try {
            resolver.openOutputStream(uri, "w")?.use {
                copyWithProgress(input, it, total, cancelled, onProgress)
            } ?: throw IllegalStateException("Cannot open download file")
            if (cancelled.get()) throw DownloadCancelled()
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            return Result(
                fileName,
                "${Environment.DIRECTORY_DOWNLOADS}/$fileName",
                uri.toString(),
                mime
            )
        } catch (e: Exception) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            throw e
        }
    }

    private fun saveApp(
        context: Context,
        input: InputStream,
        fileName: String,
        mime: String,
        total: Long?,
        cancelled: AtomicBoolean,
        onProgress: (Long, Long?) -> Unit
    ): Result {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads")
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("Cannot create download directory")
        }
        val outputFile = uniqueFile(dir, fileName)
        try {
            outputFile.outputStream().buffered().use {
                copyWithProgress(input, it, total, cancelled, onProgress)
            }
            if (cancelled.get()) throw DownloadCancelled()
        } catch (e: Exception) {
            outputFile.delete()
            throw e
        }
        return Result(outputFile.name, outputFile.absolutePath, null, mime)
    }

    private fun copyWithProgress(
        input: InputStream,
        output: OutputStream,
        total: Long?,
        cancelled: AtomicBoolean,
        onProgress: (Long, Long?) -> Unit
    ) {
        val buffer = ByteArray(64 * 1024)
        var downloaded = 0L
        var lastCallback = 0L
        while (true) {
            if (cancelled.get()) throw DownloadCancelled()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            output.write(buffer, 0, count)
            downloaded += count.toLong()
            val now = System.currentTimeMillis()
            if (now - lastCallback >= 250L) {
                lastCallback = now
                onProgress(downloaded, total)
            }
        }
        output.flush()
        onProgress(downloaded, total)
    }

    private fun uniqueFile(directory: File, fileName: String): File {
        var candidate = File(directory, fileName)
        if (!candidate.exists()) return candidate
        val dot = fileName.lastIndexOf('.')
        val hasExt = dot > 0 && dot < fileName.lastIndex
        val base = if (hasExt) fileName.substring(0, dot) else fileName
        val ext = if (hasExt) fileName.substring(dot) else ""
        var counter = 1
        while (candidate.exists() && counter < 10000) {
            candidate = File(directory, "$base ($counter)$ext")
            counter++
        }
        return candidate
    }
}
