package com.dpdpxray.app.autoguard

import com.dpdpxray.app.apkguard.InstallAlert
import com.dpdpxray.app.ui.guide.Bi
import java.util.Locale

/**
 * One item in Android's Downloads collection, as far as Android tells TraceVeil about it. [readable] is true when
 * MediaStore lets TraceVeil open the item (TraceVeil's own downloads, or a phone that shows other apps' downloads);
 * otherwise only its MIME type may be known, and [name], [pending] and [dateAddedSec] are null (unknown, not "none").
 */
data class DownloadItem(
    val uri: String,
    val id: Long?,
    val name: String?,
    val mime: String?,
    val pending: Boolean?,
    val dateAddedSec: Long?,
    val readable: Boolean,
)

/** MediaStore, as the downloads watcher reads it. The real one is [MediaStoreDownloads]; tests use a fake. */
interface DownloadsSource {
    /** What Android tells TraceVeil about one changed MediaStore item, or null when it tells nothing. */
    fun describe(uri: String): DownloadItem?

    /** APK files added to Downloads since [sinceSec] that Android lets TraceVeil see (on Android 11+ usually none). */
    fun recentApks(sinceSec: Long): List<DownloadItem>
}

/**
 * Pure rules for the downloads watcher. Android tells the job which MediaStore items changed in Downloads; each is
 * looked at once. An item is alerted when it is an APK (by MIME type or name), is not still being written, was added in
 * the last hour (when Android says when), and was not alerted before. Nothing is read from the file itself.
 */
object DownloadsWatch {
    const val MAX_AGE_SEC = 3_600L
    const val LEDGER_LIMIT = 100
    private const val APK_MIME = "application/vnd.android.package-archive"
    private val ITEM = Regex("content://media/[A-Za-z0-9_.-]+/(?:[A-Za-z_]+/)*([0-9]{1,18})")

    fun isApk(name: String?, mime: String?): Boolean =
        mime.equals(APK_MIME, ignoreCase = true) || name?.trim()?.lowercase(Locale.ROOT)?.endsWith(".apk") == true

    /** The MediaStore row id of an item URI ("content://media/external/downloads/42" → 42), or null for anything else. */
    fun mediaId(uri: String?): Long? = uri?.let { ITEM.matchEntire(it.trim()) }?.groupValues?.get(1)?.toLongOrNull()

    fun isMediaStoreItem(uri: String?): Boolean = mediaId(uri) != null

    /** The ledger key of an item: its MediaStore id (the same row appears under several collection URIs). */
    fun key(item: DownloadItem): String = item.id?.let { "id:$it" } ?: "uri:${item.uri}"

    /**
     * The new APK downloads to alert about. [triggered] are the URIs Android reported as changed; when it reported none
     * that name one item (too many changes, or only the collection), the visible recent APKs are queried instead.
     */
    fun newApks(triggered: List<String>?, source: DownloadsSource, alerted: Set<String>, nowSec: Long): List<DownloadItem> {
        val items = triggered.orEmpty().filter { isMediaStoreItem(it) }.distinctBy { mediaId(it) }
        val candidates = if (items.isEmpty()) source.recentApks(nowSec - MAX_AGE_SEC)
        else items.filter { "id:${mediaId(it)}" !in alerted }.mapNotNull { source.describe(it) }
        return candidates.asSequence()
            .filter { isApk(it.name, it.mime) }
            .filter { it.pending != true }
            .filter { it.dateAddedSec == null || nowSec - it.dateAddedSec in -60..MAX_AGE_SEC }
            .filter { key(it) !in alerted }
            .distinctBy { key(it) }
            .toList()
    }

    /** [ledger] plus [keys], keeping the [LEDGER_LIMIT] most recent. */
    fun record(ledger: List<String>, keys: List<String>): List<String> = (ledger - keys.toSet() + keys).takeLast(LEDGER_LIMIT)

    fun encode(ledger: List<String>): String = ledger.joinToString("\n")

    fun decode(stored: String?): List<String> = stored.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
}

/** What a tapped download alert opens. */
sealed interface DownloadTap {
    /** The APK Guard check of this MediaStore item, which TraceVeil can read. */
    data class Check(val uri: String) : DownloadTap

    /** Android's file picker, opened in Downloads, because TraceVeil cannot open the item itself. */
    data object Pick : DownloadTap
}

/** The downloads watcher's text: the Settings switch and the notification. */
object DownloadAlertCopy {
    const val ACTION_CHECK_DOWNLOAD = "com.dpdpxray.app.autoguard.CHECK_DOWNLOAD"
    const val ACTION_PICK_DOWNLOAD = "com.dpdpxray.app.autoguard.PICK_DOWNLOAD"
    const val EXTRA_URI = "com.dpdpxray.app.autoguard.URI"
    private const val MAX_NAME = 60

    val switchTitle = Bi("Check new APK downloads", "नई APK डाउनलोड की जाँच करें")
    val switchDetail = Bi(
        "When Android tells TraceVeil that a new app file (APK) arrived in Downloads, a notification asks you to check it before installing. " +
            "TraceVeil asks Android only for the type of each new download and never reads other files. Android 11 and later hide other apps' " +
            "downloads, so some phones never tell; then tap the APK in Files or Chrome and choose TraceVeil.",
        "जब Android TraceVeil को बताता है कि Downloads में नई ऐप फ़ाइल (APK) आई है, तो एक सूचना इंस्टॉल करने से पहले उसकी जाँच करने को कहती है। " +
            "TraceVeil Android से हर नई डाउनलोड का सिर्फ़ प्रकार पूछता है और कोई दूसरी फ़ाइल नहीं पढ़ता। Android 11 और उसके बाद दूसरे ऐप्स की डाउनलोड " +
            "छिपाता है, इसलिए कुछ फ़ोन यह नहीं बताते; तब Files या Chrome में APK पर टैप करके TraceVeil चुनें।",
    )

    fun alert(name: String?): InstallAlert {
        val shown = name?.replace(Regex("[\\r\\n\\t]+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { if (it.length > MAX_NAME) it.take(MAX_NAME - 1) + "…" else it }
        return if (shown != null) InstallAlert(
            title = "New APK downloaded: $shown",
            text = "Check it before installing. Tap to check it in TraceVeil.",
            hindi = "नई APK डाउनलोड हुई: $shown। इंस्टॉल करने से पहले जाँच लें।",
            spokenEn = "New APK downloaded: $shown. Check it before installing.",
            spokenHi = "नई APK डाउनलोड हुई: $shown। इंस्टॉल करने से पहले जाँच लें।",
        ) else InstallAlert(
            title = "New APK downloaded",
            text = "Check it before installing. Tap, then pick it from Downloads.",
            hindi = "नई APK डाउनलोड हुई। इंस्टॉल करने से पहले जाँच लें: टैप करें और Downloads से उसे चुनें।",
            spokenEn = "New APK downloaded. Check it before installing.",
            spokenHi = "नई APK डाउनलोड हुई। इंस्टॉल करने से पहले जाँच लें।",
        )
    }

    /** What a tapped download alert opens; anything else (including a non-MediaStore URI) opens nothing. */
    fun tap(action: String?, uri: String?): DownloadTap? = when (action) {
        ACTION_CHECK_DOWNLOAD -> uri?.trim()?.takeIf { DownloadsWatch.isMediaStoreItem(it) }?.let { DownloadTap.Check(it) }
        ACTION_PICK_DOWNLOAD -> DownloadTap.Pick
        else -> null
    }
}
