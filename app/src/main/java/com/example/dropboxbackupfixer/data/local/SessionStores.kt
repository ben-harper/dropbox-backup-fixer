package com.example.dropboxbackupfixer.data.local

import android.content.Context
import java.io.File

/**
 * Remembers that a local scan finished completely, so it can be reused on the next app launch
 * instead of re-scanning and re-hashing every file. The scanned files themselves live in Room.
 */
object ScanMetaStore {
    private const val PREFS = "scan_meta"

    data class LastScan(val completedAt: Long, val fileCount: Int, val folderPaths: List<String>)

    fun markStarted(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun markCompleted(context: Context, fileCount: Int, folderPaths: List<String>) {
        prefs(context).edit()
            .putLong("completed_at", System.currentTimeMillis())
            .putInt("file_count", fileCount)
            .putString("folders", folderPaths.joinToString("\n"))
            .apply()
    }

    fun getLastScan(context: Context): LastScan? {
        val p = prefs(context)
        val completedAt = p.getLong("completed_at", 0L)
        if (completedAt == 0L) return null
        val folders = p.getString("folders", "").orEmpty().split("\n").filter { it.isNotBlank() }
        return LastScan(completedAt, p.getInt("file_count", 0), folders)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * On-disk cache of the Dropbox file index (content_hash -> Dropbox path), so verification can
 * skip re-listing the whole Dropbox account. Stored as a simple tab-separated file.
 */
object DropboxIndexStore {
    private const val FILE_NAME = "dropbox_index.tsv"
    private const val PREFS = "dropbox_index_meta"

    data class IndexInfo(val indexedAt: Long, val fileCount: Int)

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun info(context: Context): IndexInfo? {
        val p = prefs(context)
        val at = p.getLong("indexed_at", 0L)
        if (at == 0L || !file(context).exists()) return null
        return IndexInfo(at, p.getInt("file_count", 0))
    }

    @Synchronized
    fun save(context: Context, index: Map<String, String>) {
        val target = file(context)
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        tmp.bufferedWriter().use { w ->
            for ((hash, path) in index) {
                w.write(hash); w.write("\t"); w.write(path); w.newLine()
            }
        }
        if (target.exists()) target.delete()
        tmp.renameTo(target)
        prefs(context).edit()
            .putLong("indexed_at", System.currentTimeMillis())
            .putInt("file_count", index.size)
            .apply()
    }

    @Synchronized
    fun load(context: Context): MutableMap<String, String>? {
        val f = file(context)
        if (!f.exists()) return null
        val map = HashMap<String, String>()
        f.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val tab = line.indexOf('\t')
                if (tab > 0) map[line.substring(0, tab)] = line.substring(tab + 1)
            }
        }
        return map
    }

    /** Keeps the saved index accurate after this app uploads a file. */
    @Synchronized
    fun append(context: Context, hash: String, path: String) {
        val f = file(context)
        if (!f.exists()) return // no saved index yet; next full refresh will pick it up
        f.appendText("$hash\t$path\n")
        val p = prefs(context)
        p.edit().putInt("file_count", p.getInt("file_count", 0) + 1).apply()
    }
}
