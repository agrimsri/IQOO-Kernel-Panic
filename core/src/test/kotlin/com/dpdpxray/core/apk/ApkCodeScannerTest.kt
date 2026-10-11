package com.dpdpxray.core.apk

import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * "Found in the app's code": SDK class names read from the string table of an APK's classes*.dex files. The DEX
 * fixtures are built by hand here (header, string_ids, string data), not taken from a real app.
 */
class ApkCodeScannerTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A minimal DEX: 0x70-byte header, string_ids, then each string as uleb128 length + MUTF-8 bytes + NUL. */
    private fun dex(strings: List<String>, badOffsetAt: Int? = null, claimStrings: Int? = null): ByteArray {
        val header = 0x70
        val idsOff = header
        val dataStart = idsOff + 4 * strings.size
        val data = ByteArrayOutputStream()
        val offsets = strings.map { s ->
            val off = dataStart + data.size()
            val bytes = s.toByteArray(Charsets.UTF_8)
            require(s.length < 128)
            data.write(s.length)
            data.write(bytes)
            data.write(0)
            off
        }
        val size = dataStart + data.size()
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("dex\n035\u0000".toByteArray(Charsets.US_ASCII))
        buf.putInt(0x20, size)
        buf.putInt(0x24, header)
        buf.putInt(0x28, 0x12345678)
        buf.putInt(0x38, claimStrings ?: strings.size)
        buf.putInt(0x3C, idsOff)
        offsets.forEachIndexed { i, off -> buf.putInt(idsOff + 4 * i, if (i == badOffsetAt) Int.MAX_VALUE else off) }
        buf.position(dataStart)
        buf.put(data.toByteArray())
        return buf.array()
    }

    private fun apk(name: String, entries: Map<String, ByteArray>): File {
        val f = tmp.newFile(name)
        ZipOutputStream(f.outputStream()).use { zip ->
            entries.forEach { (n, bytes) -> zip.putNextEntry(ZipEntry(n)); zip.write(bytes); zip.closeEntry() }
        }
        return f
    }

    private val signatures = SdkCodeSignatures(
        listOf(
            SdkCodeSignature("Meta (Facebook SDK)", listOf("com/facebook/appevents/"), "https://example.test/meta"),
            SdkCodeSignature("AppsFlyer", listOf("com/appsflyer/"), "https://example.test/af"),
            SdkCodeSignature("Adjust", listOf("com/adjust/sdk/"), "https://example.test/adjust"),
        ),
    )

    @Test
    fun `finds class descriptors by package prefix, not dotted strings or substrings`() {
        val bytes = dex(
            listOf(
                "Lcom/example/Main;", "Lcom/facebook/appevents/AppEventsLogger;", "com.adjust.sdk.Adjust",
                "Lorg/notcom/appsflyer/X;", "hello",
            ),
        )
        val found = DexStrings.prefixesFound(bytes, listOf("Lcom/facebook/appevents/", "Lcom/appsflyer/", "Lcom/adjust/sdk/"))
        assertEquals(setOf(0), found)
    }

    @Test
    fun `anything that is not a readable DEX gives no match and never throws`() {
        val prefixes = listOf("Lcom/facebook/appevents/")
        assertEquals(emptySet<Int>(), DexStrings.prefixesFound(ByteArray(0), prefixes))
        assertEquals(emptySet<Int>(), DexStrings.prefixesFound("PK\u0003\u0004 not a dex".toByteArray(), prefixes))
        assertEquals(emptySet<Int>(), DexStrings.prefixesFound(dex(listOf("Lcom/facebook/appevents/A;")).copyOf(0x50), prefixes))
        // A string offset past the end is skipped; the other strings are still read.
        val bad = dex(listOf("Lcom/facebook/appevents/A;", "Lcom/appsflyer/B;"), badOffsetAt = 0)
        assertEquals(setOf(1), DexStrings.prefixesFound(bad, listOf("Lcom/facebook/appevents/", "Lcom/appsflyer/")))
        // A header that claims more strings than the file holds stops at the end of the file.
        val claims = dex(listOf("Lcom/appsflyer/B;"), claimStrings = 1_000_000)
        assertEquals(setOf(0), DexStrings.prefixesFound(claims, listOf("Lcom/appsflyer/")))
    }

    @Test
    fun `scans every classes dex in the APK and nothing else`() {
        val apk = apk(
            "base.apk",
            mapOf(
                "classes.dex" to dex(listOf("Lcom/facebook/appevents/AppEventsLogger;")),
                "classes2.dex" to dex(listOf("Lcom/appsflyer/AppsFlyerLib;")),
                "assets/classes3.dex" to dex(listOf("Lcom/adjust/sdk/Adjust;")),
                "AndroidManifest.xml" to ByteArray(10),
            ),
        )
        val r = ApkCodeScanner.scan(listOf(apk), signatures)
        assertEquals(mapOf("Meta (Facebook SDK)" to listOf("com.facebook.appevents"), "AppsFlyer" to listOf("com.appsflyer")), r.found)
        assertEquals(2, r.dexFilesRead)
        assertEquals(0, r.dexFilesSkipped)
        assertTrue(r.complete)
    }

    @Test
    fun `size limits skip a DEX instead of reading it, and the result says it is incomplete`() {
        val apk = apk("big.apk", mapOf("classes.dex" to dex(listOf("Lcom/facebook/appevents/AppEventsLogger;"))))
        val r = ApkCodeScanner.scan(listOf(apk), signatures, CodeScanLimits(maxDexBytes = 64))
        assertTrue(r.found.isEmpty())
        assertEquals(0, r.dexFilesRead)
        assertEquals(1, r.dexFilesSkipped)
        assertFalse(r.complete)
    }

    @Test
    fun `an unreadable APK is reported, not thrown`() {
        val notZip = tmp.newFile("broken.apk").apply { writeText("not a zip") }
        val missing = File(tmp.root, "missing.apk")
        val r = ApkCodeScanner.scan(listOf(notZip, missing), signatures)
        assertEquals(2, r.apksUnreadable)
        assertTrue(r.found.isEmpty())
        assertFalse(r.complete)
    }

    @Test
    fun `the bundled signatures name catalogue services and cite a source`() {
        val catalog = TrackerCatalog.loadDefault()
        val sigs = SdkCodeSignatures.loadDefault().entries
        assertTrue(sigs.size >= 10)
        sigs.forEach { s ->
            assertTrue("${s.entity} is not a catalogue entity", catalog.entryFor(s.entity) != null)
            assertTrue(s.source, s.source.startsWith("https://"))
            assertTrue(s.entity, s.classPrefixes.isNotEmpty())
            s.classPrefixes.forEach { p ->
                assertTrue(p, p.contains('/') && (p.endsWith("/") || p.endsWith(";")) && !p.startsWith("L") && !p.contains('.'))
            }
        }
        // Google's advertising-ID helper is used by many SDKs, so it must not count as AdMob.
        val adPrefixes = sigs.filter { it.entity == "Google Ads / AdMob" }.flatMap { it.classPrefixes }
        assertTrue(adPrefixes.none { "com/google/android/gms/ads/identifier/".startsWith(it) })
    }
}
