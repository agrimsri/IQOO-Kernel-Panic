package com.dpdpxray.app.autoguard

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import com.dpdpxray.app.MainActivity
import com.dpdpxray.app.apkguard.ApkInstallWatcher
import com.dpdpxray.app.graph

/**
 * MediaStore's Downloads collection, read without any storage permission. Since Android 11 (and under scoped storage on
 * Android 10) MediaStore shows TraceVeil only the downloads it made itself, so for another app's download a query
 * returns nothing; TraceVeil then asks only for the item's MIME type (ContentResolver.getType), which may or may not be
 * answered depending on the phone. It never opens, copies or deletes the file.
 */
class MediaStoreDownloads(context: Context) : DownloadsSource {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    override fun describe(uri: String): DownloadItem? {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return null
        val id = DownloadsWatch.mediaId(uri)
        query(parsed, null, null)?.firstOrNull()?.let { return it }
        val type = typeOf(parsed) ?: id?.let { typeOf(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL, it)) } ?: return null
        return DownloadItem(uri, id, name = null, mime = type, pending = null, dateAddedSec = null, readable = false)
    }

    override fun recentApks(sinceSec: Long): List<DownloadItem> = query(
        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        "(${MediaStore.MediaColumns.MIME_TYPE} = ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?) AND ${MediaStore.MediaColumns.DATE_ADDED} >= ?",
        arrayOf(APK_MIME, "%.apk", sinceSec.toString()),
    ).orEmpty()

    /** Rows Android lets TraceVeil see, including downloads still being written (so they can be skipped as pending). */
    private fun query(uri: Uri, selection: String?, args: Array<String>?): List<DownloadItem>? = runCatching {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.DATE_ADDED,
        )
        val cursor: Cursor? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val queryArgs = Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                if (selection != null) {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                }
            }
            resolver.query(uri, projection, queryArgs, null)
        } else {
            @Suppress("DEPRECATION") resolver.query(MediaStore.setIncludePending(uri), projection, selection, args, null)
        }
        cursor?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val itemUri = if (DownloadsWatch.isMediaStoreItem(uri.toString())) uri.toString()
                    else MediaStore.Downloads.EXTERNAL_CONTENT_URI.buildUpon().appendPath(id.toString()).build().toString()
                    add(DownloadItem(itemUri, id, c.getString(1), c.getString(2), c.getInt(3) != 0, if (c.isNull(4)) null else c.getLong(4), readable = true))
                }
            }
        }
    }.getOrNull()

    private fun typeOf(uri: Uri): String? = runCatching { resolver.getType(uri) }.getOrNull()?.takeIf { it.isNotBlank() }

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}

/**
 * Runs the downloads watcher once (from [DownloadsJobService]) and posts one "New APK downloaded" notification per new
 * APK. Tapping it opens the APK Guard check of that file when TraceVeil can read it, or Android's file picker in
 * Downloads when it cannot. Nothing is installed, opened or deleted by itself.
 */
object DownloadAlerts {
    /** Returns how many downloads were alerted; 0 when the setting is off. */
    fun run(context: Context, triggered: List<String>?): Int {
        val settings = runCatching { context.graph.settings.settings.value }.getOrNull() ?: return 0
        if (!settings.checkApkDownloads) return 0
        val store = AutoGuardStore(context)
        val found = DownloadsWatch.newApks(triggered, MediaStoreDownloads(context), store.alertedDownloads(), System.currentTimeMillis() / 1000)
        if (found.isEmpty()) return 0
        store.recordDownloads(found.map(DownloadsWatch::key))
        found.forEach { post(context, it) }
        return found.size
    }

    private fun post(context: Context, item: DownloadItem) {
        val open = Intent(context, MainActivity::class.java)
            .setAction(if (item.readable) DownloadAlertCopy.ACTION_CHECK_DOWNLOAD else DownloadAlertCopy.ACTION_PICK_DOWNLOAD)
            .putExtra(DownloadAlertCopy.EXTRA_URI, item.uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val id = ApkInstallWatcher.DOWNLOAD_BASE + (DownloadsWatch.key(item).hashCode() and 0xFFFF)
        ApkInstallWatcher.post(context, id, id, open, DownloadAlertCopy.alert(item.name), ApkInstallWatcher.DOWNLOADS_CHANNEL)
    }
}
