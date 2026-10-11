package com.dpdpxray.app.autoguard

import com.dpdpxray.app.apkguard.ApkGuardRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The downloads watcher's MediaStore logic, against a fake MediaStore. */
class DownloadsWatchTest {
    private val now = 1_791_600_000L
    private val apk = "application/vnd.android.package-archive"

    /** A fake MediaStore: [items] by id; [readable] ids are visible to TraceVeil, the rest only reveal their MIME type. */
    private class FakeSource(val items: Map<Long, DownloadItem>, val recent: List<DownloadItem> = emptyList()) : DownloadsSource {
        val described = mutableListOf<String>()
        var recentQueries = 0
        override fun describe(uri: String): DownloadItem? {
            described += uri
            return DownloadsWatch.mediaId(uri)?.let { items[it] }?.copy(uri = uri)
        }
        override fun recentApks(sinceSec: Long): List<DownloadItem> { recentQueries++; return recent.filter { (it.dateAddedSec ?: 0) >= sinceSec } }
    }

    private fun item(id: Long, name: String? = "app-$id.apk", mime: String? = apk, pending: Boolean? = false, added: Long? = now - 30, readable: Boolean = true) =
        DownloadItem("content://media/external/downloads/$id", id, name, mime, pending, added, readable)

    private fun uri(id: Long) = "content://media/external/downloads/$id"

    @Test fun `a finished APK that Android lets TraceVeil see is alerted with its name`() {
        val source = FakeSource(mapOf(12L to item(12, "Fast Loan.apk")))
        val found = DownloadsWatch.newApks(listOf(uri(12)), source, alerted = emptySet(), nowSec = now)
        assertEquals(listOf("Fast Loan.apk"), found.map { it.name })
        assertTrue(found.single().readable)
    }

    @Test fun `an APK Android hides from TraceVeil is still alerted when Android tells its type, without a name`() {
        val hidden = DownloadItem(uri(13), 13, null, apk, pending = null, dateAddedSec = null, readable = false)
        val found = DownloadsWatch.newApks(listOf(uri(13)), FakeSource(mapOf(13L to hidden)), emptySet(), now)
        assertEquals(1, found.size)
        assertNull(found.single().name)
        assertFalse(found.single().readable)
    }

    @Test fun `downloads still being written, other file types and items Android tells nothing about are skipped`() {
        val source = FakeSource(mapOf(
            1L to item(1, pending = true),
            2L to item(2, name = "report.pdf", mime = "application/pdf"),
            3L to item(3, name = "photo.jpg", mime = "image/jpeg"),
        ))
        assertTrue(DownloadsWatch.newApks(listOf(uri(1), uri(2), uri(3), uri(4)), source, emptySet(), now).isEmpty())
    }

    @Test fun `an old file that only changed now is not a new download`() {
        val source = FakeSource(mapOf(5L to item(5, added = now - 3 * 3_600)))
        assertTrue(DownloadsWatch.newApks(listOf(uri(5)), source, emptySet(), now).isEmpty())
    }

    @Test fun `each download is alerted once, even when Android reports it through several collections`() {
        val source = FakeSource(mapOf(7L to item(7)))
        val triggered = listOf(uri(7), "content://media/external/file/7", "content://media/external_primary/downloads/7")
        val first = DownloadsWatch.newApks(triggered, source, emptySet(), now)
        assertEquals(1, first.size)
        assertEquals(1, source.described.size)
        val again = DownloadsWatch.newApks(listOf(uri(7)), source, first.map(DownloadsWatch::key).toSet(), now)
        assertTrue(again.isEmpty())
    }

    @Test fun `when Android names no single item, only APKs TraceVeil can see are found by a query`() {
        val source = FakeSource(emptyMap(), recent = listOf(item(20), item(21, added = now - 4 * 3_600)))
        val viaNull = DownloadsWatch.newApks(null, source, emptySet(), now)
        val viaCollection = DownloadsWatch.newApks(listOf("content://media/external/downloads"), source, emptySet(), now)
        assertEquals(listOf(20L), viaNull.map { it.id })
        assertEquals(listOf(20L), viaCollection.map { it.id })
        assertEquals(2, source.recentQueries)
    }

    @Test fun `APK detection uses the MIME type or the file name`() {
        assertTrue(DownloadsWatch.isApk(null, apk))
        assertTrue(DownloadsWatch.isApk("x.APK", "application/octet-stream"))
        assertTrue(DownloadsWatch.isApk("x.apk", null))
        assertFalse(DownloadsWatch.isApk("x.apks", "application/octet-stream"))
        assertFalse(DownloadsWatch.isApk("x.apk.crdownload", null))
        assertFalse(DownloadsWatch.isApk(null, null))
    }

    @Test fun `only MediaStore item uris carry an id`() {
        assertEquals(42L, DownloadsWatch.mediaId("content://media/external/downloads/42"))
        assertEquals(42L, DownloadsWatch.mediaId("content://media/external_primary/file/42"))
        assertNull(DownloadsWatch.mediaId("content://media/external/downloads"))
        assertNull(DownloadsWatch.mediaId("content://com.other/downloads/42"))
        assertNull(DownloadsWatch.mediaId("content://media/external/images/media/x"))
        assertTrue(DownloadsWatch.isMediaStoreItem("content://media/external/downloads/42"))
        assertFalse(DownloadsWatch.isMediaStoreItem("content://com.dpdpxray.app.files/x"))
        assertFalse(DownloadsWatch.isMediaStoreItem(null))
    }

    @Test fun `the alerted ledger keeps the newest entries`() {
        val keys = (1..150).map { "id:$it" }
        val kept = DownloadsWatch.record(emptyList(), keys)
        assertEquals(DownloadsWatch.LEDGER_LIMIT, kept.size)
        assertEquals("id:150", kept.last())
        assertEquals(DownloadsWatch.decode(DownloadsWatch.encode(kept)), kept)
        assertEquals(emptyList<String>(), DownloadsWatch.decode(null))
        assertEquals(emptyList<String>(), DownloadsWatch.decode("  "))
    }

    // --- notification copy and taps --------------------------------------------------------------------------------

    @Test fun `the notification names the file and asks for a check before installing`() {
        val alert = DownloadAlertCopy.alert("Fast Loan.apk")
        assertEquals("New APK downloaded: Fast Loan.apk", alert.title)
        assertEquals("Check it before installing. Tap to check it in TraceVeil.", alert.text)
        assertTrue(alert.hindi, alert.hindi.startsWith("नई APK डाउनलोड हुई: Fast Loan.apk"))
    }

    @Test fun `without a name it still says what to do, and long or multi-line names stay readable`() {
        val unnamed = DownloadAlertCopy.alert(null)
        assertEquals("New APK downloaded", unnamed.title)
        assertTrue(unnamed.text.contains("pick it from Downloads"))
        val long = DownloadAlertCopy.alert("A".repeat(300) + ".apk")
        assertTrue(long.title.length < 100)
        assertFalse(DownloadAlertCopy.alert("evil\nname.apk").title.contains("\n"))
        listOf(unnamed.title, unnamed.text, long.text).forEach { t ->
            listOf("malicious", "malware", "safe", "fraud").forEach { assertFalse("$it in $t", t.lowercase().contains(it)) }
        }
    }

    @Test fun `a tapped download alert opens only a MediaStore file check or the download picker`() {
        assertEquals(DownloadTap.Check("content://media/external/downloads/42"),
            DownloadAlertCopy.tap(DownloadAlertCopy.ACTION_CHECK_DOWNLOAD, "content://media/external/downloads/42"))
        assertNull(DownloadAlertCopy.tap(DownloadAlertCopy.ACTION_CHECK_DOWNLOAD, "content://com.dpdpxray.app.files/x"))
        assertNull(DownloadAlertCopy.tap(DownloadAlertCopy.ACTION_CHECK_DOWNLOAD, null))
        assertEquals(DownloadTap.Pick, DownloadAlertCopy.tap(DownloadAlertCopy.ACTION_PICK_DOWNLOAD, null))
        assertNull(DownloadAlertCopy.tap("android.intent.action.MAIN", "content://media/external/downloads/42"))
        assertEquals("apkguard/downloads", ApkGuardRoutes.DOWNLOADS)
    }

    @Test fun `the setting has both languages and says what Android may hide`() {
        assertTrue(DownloadAlertCopy.switchTitle.en == "Check new APK downloads")
        assertTrue(DownloadAlertCopy.switchTitle.hi.isNotBlank())
        assertTrue(DownloadAlertCopy.switchDetail.en.contains("Android"))
        assertTrue(DownloadAlertCopy.switchDetail.hi.isNotBlank())
    }
}
