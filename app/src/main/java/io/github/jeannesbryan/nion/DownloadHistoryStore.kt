package io.github.jeannesbryan.nion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class DownloadRecord(
    val id: String,
    val fileName: String,
    val sourceUrl: String,
    val mimeType: String,
    val totalBytes: Long?,
    val downloadedBytes: Long,
    val status: String,
    val location: String?,
    val contentUri: String?,
    val error: String?,
    val startedAt: Long,
    val finishedAt: Long?
)

class DownloadHistoryStore(context: Context) {
    companion object {
        const val DOWNLOADING = "downloading"
        const val COMPLETED = "completed"
        const val FAILED = "failed"
        const val CANCELLED = "cancelled"
        private const val PREFS = "nion_download_history"
        private const val ITEMS = "items"
        private const val MAX_ITEMS = 100
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun list(): List<DownloadRecord> {
        val now = System.currentTimeMillis()
        val original = readInternal()

        val normalized = original.map {
            if (it.status == DOWNLOADING) {
                it.copy(
                    status = FAILED,
                    error = "Interrupted before completion",
                    finishedAt = now
                )
            } else {
                it
            }
        }

        if (normalized != original) {
            writeInternal(normalized)
        }

        return normalized.sortedByDescending {
            it.startedAt
        }
    }

    @Synchronized
    fun put(record: DownloadRecord) {
        val items = readInternal().toMutableList()
        val index = items.indexOfFirst { it.id == record.id }
        if (index >= 0) items[index] = record else items.add(0, record)
        writeInternal(items.take(MAX_ITEMS))
    }

    @Synchronized
    fun remove(id: String) = writeInternal(readInternal().filterNot { it.id == id })

    @Synchronized
    fun clear() {
        prefs.edit().remove(ITEMS).apply()
    }

    @Synchronized
    fun clearFinished() {
        writeInternal(
            readInternal().filter {
                it.status == DOWNLOADING
            }
        )
    }

    private fun readInternal(): List<DownloadRecord> {
        val raw = prefs.getString(ITEMS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val id = o.optString("id", "")
                    val name = o.optString("fileName", "")
                    if (id.isBlank() || name.isBlank()) continue
                    add(
                        DownloadRecord(
                            id = id,
                            fileName = name,
                            sourceUrl = o.optString("sourceUrl", ""),
                            mimeType = o.optString("mimeType", "application/octet-stream"),
                            totalBytes = if (o.has("totalBytes") && !o.isNull("totalBytes")) o.optLong("totalBytes") else null,
                            downloadedBytes = o.optLong("downloadedBytes", 0L),
                            status = o.optString("status", FAILED),
                            location = o.optString("location", "").takeIf { it.isNotBlank() },
                            contentUri = o.optString("contentUri", "").takeIf { it.isNotBlank() },
                            error = o.optString("error", "").takeIf { it.isNotBlank() },
                            startedAt = o.optLong("startedAt", 0L),
                            finishedAt = if (o.has("finishedAt") && !o.isNull("finishedAt")) o.optLong("finishedAt") else null
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeInternal(records: List<DownloadRecord>) {
        val array = JSONArray()
        records.take(MAX_ITEMS).forEach { r ->
            array.put(JSONObject().apply {
                put("id", r.id)
                put("fileName", r.fileName)
                put("sourceUrl", r.sourceUrl)
                put("mimeType", r.mimeType)
                put("totalBytes", r.totalBytes ?: JSONObject.NULL)
                put("downloadedBytes", r.downloadedBytes)
                put("status", r.status)
                put("location", r.location ?: JSONObject.NULL)
                put("contentUri", r.contentUri ?: JSONObject.NULL)
                put("error", r.error ?: JSONObject.NULL)
                put("startedAt", r.startedAt)
                put("finishedAt", r.finishedAt ?: JSONObject.NULL)
            })
        }
        prefs.edit().putString(ITEMS, array.toString()).apply()
    }
}
