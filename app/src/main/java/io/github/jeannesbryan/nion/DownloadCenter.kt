package io.github.jeannesbryan.nion

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class DownloadCenter(
    private val activity: Activity,
    private val onRetry: (String) -> Unit
) {
    private val history = DownloadHistoryStore(activity.applicationContext)
    private val active = ConcurrentHashMap<String, TorDownloadHandler.Handle>()
    private val lastPersistAt = ConcurrentHashMap<String, Long>()

    private val suppressHistory =
        ConcurrentHashMap<String, Boolean>()

    @Volatile
    private var closed = false

    fun handleExternalResponse(response: WebResponse) {
        if (closed) {
            TorDownloadHandler.discard(response)
            return
        }
        if (response.body == null) {
            toast("Download contains no data", true)
            return
        }

        val fileName = TorDownloadHandler.suggestedFileName(response)
        val size = TorDownloadHandler.contentLength(response)
        val host = try { Uri.parse(response.uri).host } catch (_: Exception) { null }
        val details = buildString {
            append(fileName)
            if (size != null) append("\n${formatBytes(size)}")
            if (!host.isNullOrBlank()) append("\nFrom: $host")
        }

        AlertDialog.Builder(activity)
            .setTitle("Download file?")
            .setMessage(details)
            .setPositiveButton("Download") { _, _ -> startDownload(response, fileName) }
            .setNegativeButton("Cancel") { _, _ -> TorDownloadHandler.discard(response) }
            .setOnCancelListener { TorDownloadHandler.discard(response) }
            .show()
    }

    fun show() {
        val records = history.list()
        if (records.isEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle("Downloads")
                .setMessage("No download history.")
                .setPositiveButton("Close", null)
                .show()
            return
        }

        val labels = records.map { displayLabel(it) }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("Downloads")
            .setItems(labels) { _, which -> records.getOrNull(which)?.let { showActions(it) } }
            .setNeutralButton("Clear History") { _, _ -> confirmClearHistory() }
            .setNegativeButton("Close", null)
            .show()
    }

    fun clearHistorySilently() =
        history.clear()

    fun clearForBrowsingData() {
        val ids =
            active.keys.toList()

        ids.forEach {
            suppressHistory[it] =
                true
        }

        ids.forEach {
            TorDownloadHandler.cancel(it)
        }

        history.clear()
    }

    fun cancelActiveForTorLoss() {
        active.keys.toList().forEach {
            TorDownloadHandler.cancel(it)
        }
    }

    fun shutdown() {
        closed = true
        cancelActiveForTorLoss()
    }

    private fun startDownload(response: WebResponse, fileName: String) {
        val id = UUID.randomUUID().toString()
        val sourceUrl = response.uri?.trim().orEmpty()
        val total = TorDownloadHandler.contentLength(response)
        val mime = TorDownloadHandler.mimeType(response)

        var record = DownloadRecord(
            id = id,
            fileName = fileName,
            sourceUrl = sourceUrl,
            mimeType = mime,
            totalBytes = total,
            downloadedBytes = 0L,
            status = DownloadHistoryStore.DOWNLOADING,
            location = null,
            contentUri = null,
            error = null,
            startedAt = System.currentTimeMillis(),
            finishedAt = null
        )
        history.put(record)
        toast("Downloading $fileName")

        val handle = TorDownloadHandler.save(
            context = activity.applicationContext,
            id = id,
            response = response,
            fileName = fileName,
            onProgress = { downloaded, knownTotal ->
                if (!suppressHistory.containsKey(id)) {
                    val now =
                        System.currentTimeMillis()

                    val last =
                        lastPersistAt[id] ?: 0L

                    val complete =
                        knownTotal != null &&
                        downloaded >= knownTotal

                    if (
                        complete ||
                        now - last >= 500L
                    ) {
                        lastPersistAt[id] =
                            now

                        record = record.copy(
                            downloadedBytes = downloaded,
                            totalBytes =
                                knownTotal
                                    ?: record.totalBytes
                        )

                        history.put(record)
                    }
                }
            },
            onSuccess = { result ->
                active.remove(id)
                lastPersistAt.remove(id)

                val suppressed =
                    suppressHistory.remove(id) != null

                record = record.copy(
                    fileName = result.fileName,
                    downloadedBytes =
                        record.totalBytes
                            ?: record.downloadedBytes,
                    status =
                        DownloadHistoryStore.COMPLETED,
                    location = result.location,
                    contentUri = result.contentUri,
                    mimeType = result.mimeType,
                    error = null,
                    finishedAt =
                        System.currentTimeMillis()
                )

                if (!suppressed) {
                    history.put(record)
                }

                if (
                    !suppressed &&
                    !closed
                ) {
                    activity.runOnUiThread {
                        toast(
                            "Downloaded: ${result.fileName}"
                        )
                    }
                }
            },
            onFailure = { reason ->
                active.remove(id)
                lastPersistAt.remove(id)

                val suppressed =
                    suppressHistory.remove(id) != null

                record = record.copy(
                    status =
                        DownloadHistoryStore.FAILED,
                    error = reason,
                    finishedAt =
                        System.currentTimeMillis()
                )

                if (!suppressed) {
                    history.put(record)
                }

                if (
                    !suppressed &&
                    !closed
                ) {
                    activity.runOnUiThread {
                        toast(
                            "Download failed: $reason",
                            true
                        )
                    }
                }
            },
            onCancelled = {
                active.remove(id)
                lastPersistAt.remove(id)

                val suppressed =
                    suppressHistory.remove(id) != null

                record = record.copy(
                    status =
                        DownloadHistoryStore.CANCELLED,
                    error = null,
                    finishedAt =
                        System.currentTimeMillis()
                )

                if (!suppressed) {
                    history.put(record)
                }

                if (
                    !suppressed &&
                    !closed
                ) {
                    activity.runOnUiThread {
                        toast(
                            "Download cancelled"
                        )
                    }
                }
            }
        )
        if (handle != null) active[id] = handle
    }

    private fun showActions(record: DownloadRecord) {
        val actions = mutableListOf<String>()
        if (record.status == DownloadHistoryStore.DOWNLOADING) actions += "Cancel Download"
        if (record.status == DownloadHistoryStore.COMPLETED) actions += "Open File"
        if (
            (record.status == DownloadHistoryStore.FAILED || record.status == DownloadHistoryStore.CANCELLED) &&
            isWebUrl(record.sourceUrl)
        ) actions += "Retry in New Tab"
        actions += "Remove from History"

        AlertDialog.Builder(activity)
            .setTitle(record.fileName)
            .setMessage(detailText(record))
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions.getOrNull(which)) {
                    "Cancel Download" -> cancel(record)
                    "Open File" -> openFile(record)
                    "Retry in New Tab" -> onRetry(record.sourceUrl)
                    "Remove from History" -> history.remove(record.id)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun cancel(record: DownloadRecord) {
        if (!TorDownloadHandler.cancel(record.id)) {
            history.put(record.copy(
                status = DownloadHistoryStore.CANCELLED,
                finishedAt = System.currentTimeMillis()
            ))
        }
    }

    private fun openFile(record: DownloadRecord) {
        val uri = try {
            when {
                !record.contentUri.isNullOrBlank() -> Uri.parse(record.contentUri)
                !record.location.isNullOrBlank() -> {
                    val file = File(record.location)
                    if (!file.exists()) null
                    else FileProvider.getUriForFile(
                        activity,
                        "${activity.packageName}.files",
                        file
                    )
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }

        if (uri == null) {
            toast("Downloaded file is no longer available", true)
            return
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, record.mimeType.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast("No app can open this file type", true)
        } catch (_: Exception) {
            toast("Could not open downloaded file", true)
        }
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(activity)
            .setTitle(
                "Clear download history?"
            )
            .setMessage(
                "Completed, failed, and cancelled records " +
                    "will be removed. Active downloads continue. " +
                    "Downloaded files are not deleted."
            )
            .setPositiveButton(
                "Clear History"
            ) { _, _ ->
                history.clearFinished()

                toast(
                    "Finished download history cleared"
                )
            }
            .setNegativeButton(
                "Cancel",
                null
            )
            .show()
    }

    private fun displayLabel(record: DownloadRecord): String {
        val prefix = when (record.status) {
            DownloadHistoryStore.DOWNLOADING -> "↓"
            DownloadHistoryStore.COMPLETED -> "✓"
            DownloadHistoryStore.CANCELLED -> "×"
            else -> "!"
        }
        return "$prefix ${record.fileName}\n${shortStatus(record)}"
    }

    private fun shortStatus(record: DownloadRecord): String = when (record.status) {
        DownloadHistoryStore.DOWNLOADING -> {
            val total = record.totalBytes
            if (total != null && total > 0L) {
                val percent = (record.downloadedBytes * 100L / total).coerceIn(0L, 100L)
                "$percent% • ${formatBytes(record.downloadedBytes)} / ${formatBytes(total)}"
            } else {
                "${formatBytes(record.downloadedBytes)} downloaded"
            }
        }
        DownloadHistoryStore.COMPLETED -> "Completed • ${formatBytes(record.downloadedBytes)}"
        DownloadHistoryStore.CANCELLED -> "Cancelled"
        else -> record.error?.take(80) ?: "Failed"
    }

    private fun detailText(record: DownloadRecord): String = buildString {
        append(shortStatus(record))
        if (record.sourceUrl.isNotBlank()) append("\n\nSource\n${record.sourceUrl}")
        if (!record.location.isNullOrBlank()) append("\n\nSaved to\n${record.location}")
    }

    private fun isWebUrl(value: String): Boolean = try {
        when (Uri.parse(value).scheme?.lowercase()) {
            "http", "https" -> true
            else -> false
        }
    } catch (_: Exception) {
        false
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kib = bytes / 1024.0
        if (kib < 1024.0) return String.format("%.1f KiB", kib)
        val mib = kib / 1024.0
        if (mib < 1024.0) return String.format("%.1f MiB", mib)
        return String.format("%.2f GiB", mib / 1024.0)
    }

    private fun toast(message: String, long: Boolean = false) {
        if (closed) return
        Toast.makeText(activity, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }
}
