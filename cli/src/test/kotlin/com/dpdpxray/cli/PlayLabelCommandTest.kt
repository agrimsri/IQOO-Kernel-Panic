package com.dpdpxray.cli

import com.dpdpxray.cli.CliHarness.run
import com.dpdpxray.cli.CliHarness.samples
import com.dpdpxray.cli.CliHarness.writeText
import com.dpdpxray.cli.samples.SampleBundles
import com.dpdpxray.core.datasafety.DemoLabels
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** `traceveil-ci check --play-label label.txt` on the synthetic sample bundles, with the LeakyShop demo label. */
class PlayLabelCommandTest {
    @get:Rule val temp = TemporaryFolder()

    private val dir: File by lazy { samples(temp.newFolder("bundles")).also { writeText(it, "label.txt", DemoLabels.LEAKYSHOP) } }

    @Test
    fun `leaky sample prints the points to review and keeps the policy exit code`() {
        val plain = run(dir, "check", SampleBundles.LEAKY)
        val r = run(dir, "check", SampleBundles.LEAKY, "--play-label", "label.txt")
        assertEquals(r.toString(), plain.exit, r.exit)
        assertTrue(r.out, r.out.startsWith(plain.out))
        assertTrue(r.out, r.out.contains("Play Data safety label vs what this check saw (label.txt)"))
        assertTrue(r.out, r.out.contains("Separate from the score and the policy result"))
        assertTrue(r.out, r.out.contains("Sharing: No data shared with third parties"))
        assertTrue(r.out, r.out.contains("Points to review (4):"))
        assertTrue(r.out, r.out.contains("1. Label does not list Device or other IDs among collected or shared data"))
        assertTrue(r.out, r.out.contains("4. Label says no data shared with third parties"))
        assertTrue(r.out, r.out.contains("https://dev.appsflyer.com/hc/docs/android-sdk-reference-appsflyerlib"))
    }

    @Test
    fun `fixed sample has nothing to set against the label`() {
        val r = run(dir, "check", SampleBundles.FIXED, "--play-label", "label.txt")
        assertTrue(r.out, r.out.contains("No points to review in this comparison."))
        assertTrue(r.out, r.out.contains("no lookups to catalogued third-party SDK hosts"))
    }

    @Test
    fun `json output carries the comparison per bundle`() {
        val r = run(dir, "check", SampleBundles.LEAKY, "--play-label", "label.txt", "--format", "json")
        val root = Json.parseToJsonElement(r.out).jsonObject
        val label = root.getValue("playLabel").jsonObject
        assertEquals("label.txt", label.getValue("file").jsonPrimitive.content)
        val check = label.getValue("bundles").jsonArray.single().jsonObject.getValue("check").jsonObject
        assertEquals(4, check.getValue("points").jsonArray.size)
        assertTrue(root.getValue("exitCode").jsonPrimitive.int == r.exit)
    }

    @Test
    fun `an unreadable label says so instead of guessing`() {
        writeText(dir, "notes.txt", "Great app, five stars.")
        val r = run(dir, "check", SampleBundles.LEAKY, "--play-label", "notes.txt")
        assertTrue(r.out, r.out.contains("Could not find a Data safety statement"))
        assertFalse(r.out, r.out.contains("Points to review"))
    }

    @Test
    fun `a missing label file is a usage error`() {
        val r = run(dir, "check", SampleBundles.LEAKY, "--play-label", "nope.txt")
        assertEquals(r.toString(), Exit.USAGE, r.exit)
        assertTrue(r.err, r.err.contains("Play label file not found: nope.txt"))
    }

    @Test
    fun `usage mentions the option`() {
        assertTrue(TraceVeilCi.USAGE.contains("--play-label label.txt"))
    }
}
