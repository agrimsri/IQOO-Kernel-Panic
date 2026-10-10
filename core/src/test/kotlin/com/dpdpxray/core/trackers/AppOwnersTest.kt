package com.dpdpxray.core.trackers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** G1: who publishes the audited app, from a small cited list. Unknown apps have an unknown owner. */
class AppOwnersTest {
    private val owners = AppOwners.loadDefault()
    private val catalog = TrackerCatalog.loadDefault()

    @Test
    fun `well-known meta and google apps resolve to their company`() {
        listOf(
            "com.instagram.android", "com.facebook.katana", "com.facebook.orca", "com.facebook.lite",
            "com.whatsapp", "com.instagram.barcelona",
        ).forEach { assertEquals(it, "Meta", owners.ownerOf(it)?.company) }
        listOf("com.google.android.youtube", "com.google.android.gm", "com.google.android.apps.maps", "com.android.chrome")
            .forEach { assertEquals(it, "Google", owners.ownerOf(it)?.company) }
    }

    @Test
    fun `unknown and look-alike packages have no owner`() {
        listOf("com.leakyshop.demo", "com.block.juggle", "com.instagram.android.fake", "com.instagram", "com.whatsapp.w4b", "")
            .forEach { assertNull(it, owners.ownerOf(it)) }
    }

    @Test
    fun `every default record is cited by the store listing of exactly that package`() {
        val all = owners.all()
        assertTrue(all.size >= 10)
        assertEquals("one record per package", all.map { it.packageName }.toSet().size, all.size)
        all.forEach { o ->
            assertEquals(o.packageName, "https://play.google.com/store/apps/details?id=${o.packageName}", o.source)
            assertTrue(o.packageName, o.asOf.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
            assertTrue("${o.packageName}: the quoted developer names the company", o.quote.contains(o.company))
            assertFalse(o.app.isBlank())
        }
    }

    @Test
    fun `every owner company is a company group in the tracker catalogue`() {
        val groups = catalog.entries().mapNotNull { it.company }.toSet()
        owners.all().forEach { assertTrue("${it.company} not in $groups", it.company in groups) }
    }

    private fun record(pkg: String, extra: String = "") =
        """[{"package": "$pkg", "app": "X", "company": "X Corp", "quote": "X Corp", "source": "https://example.com/x", "asOf": "2026-10-10"$extra}]"""

    @Test
    fun `an explicit wildcard covers child packages on a dot boundary only`() {
        val o = AppOwners.fromJson(record("com.example.*"))
        assertEquals("X Corp", o.ownerOf("com.example.app")?.company)
        assertEquals("X Corp", o.ownerOf("com.example.app.lite")?.company)
        assertNull(o.ownerOf("com.example"))
        assertNull(o.ownerOf("com.examplex.app"))
    }

    @Test
    fun `an exact record beats a wildcard`() {
        val text = """[
            {"package": "com.example.*", "app": "Any", "company": "Parent", "quote": "Parent", "source": "https://example.com/a", "asOf": "2026-10-10"},
            {"package": "com.example.app", "app": "App", "company": "Child", "quote": "Child", "source": "https://example.com/b", "asOf": "2026-10-10"}
        ]"""
        assertEquals("Child", AppOwners.fromJson(text).ownerOf("com.example.app")?.company)
        assertEquals("Parent", AppOwners.fromJson(text).ownerOf("com.example.other")?.company)
    }

    @Test
    fun `records without a citation fail the load and name the package`() {
        val bad = listOf(
            record("com.example.app").replace("https://example.com/x", "http://example.com/x"),
            record("com.example.app").replace("2026-10-10", "10 Oct 2026"),
            record("com.example.app").replace("\"quote\": \"X Corp\"", "\"quote\": \" \""),
            record("com.example.app").replace("\"company\": \"X Corp\"", "\"company\": \"\""),
            record("com.example.app").replace("\"quote\": \"X Corp\"", "\"quote\": \"${"q".repeat(401)}\""),
        )
        bad.forEach { text ->
            val e = assertThrows(text, IllegalArgumentException::class.java) { AppOwners.fromJson(text) }
            assertTrue(e.message, e.message!!.contains("com.example.app"))
        }
        listOf("not a package", "com..x", "com.example.*.app", "*").forEach { pkg ->
            assertThrows(pkg, IllegalArgumentException::class.java) { AppOwners.fromJson(record(pkg)) }
        }
    }
}
