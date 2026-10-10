package com.dpdpxray.cli

import com.dpdpxray.cli.CliHarness.run
import com.dpdpxray.cli.CliHarness.samples
import com.dpdpxray.cli.CliHarness.write
import com.dpdpxray.cli.samples.SampleBundles
import kotlinx.serialization.json.Json
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

/** `traceveil-ci check --profile`: the legal lens is reported, never changes the policy result. */
class LensCommandTest {
    @get:Rule val temp = TemporaryFolder()

    private val dir: File by lazy { samples(temp.newFolder("bundles")) }
    private val leaky get() = SampleBundles.LEAKY

    @Test
    fun `without a profile the global baseline is used and named as the default`() {
        val r = run(dir, "check", leaky)
        assertEquals(Exit.VIOLATION, r.exit)
        assertTrue(r.out, r.out.contains("Lens:     Global baseline (GLOBAL_BASELINE, default: the bundle names no lens)"))
        assertTrue(r.out, r.out.contains("Good practice: C2, C1, C3, C4, C10 · Context: none · Not applicable: none"))
        assertTrue(r.out, r.out.contains("Under this lens: 21/100 · below 60 (not-applicable findings skipped). A reading of the findings under this lens, not a legal verdict."))
    }

    @Test
    fun `profile option reads the findings under that law without changing the gate`() {
        val base = run(dir, "check", leaky)
        val coppa = run(dir, "check", leaky, "--profile", "us-children")
        assertEquals(base.exit, coppa.exit)
        assertTrue(coppa.out, coppa.out.contains("Lens:     US children (COPPA) (US_CHILDREN, from --profile)"))
        assertTrue(coppa.out, coppa.out.contains("Not applicable: C3, C4"))
        assertTrue(coppa.out, coppa.out.contains("Under this lens: 51/100 · below 60"))
        assertTrue(coppa.out, coppa.out.contains("Score:    21/100 · below 60 (re-evaluated; same as stored in the bundle)"))
        val ca = run(dir, "check", leaky, "--profile=US_CALIFORNIA")
        assertTrue(ca.out, ca.out.contains("Required: C2 · Context: C1, C3, C4, C10 · Not applicable: none"))
        listOf(base, coppa, ca).forEach { assertFalse(it.out.contains("₹") || it.out.contains("crore") || it.out.contains("DPDP-ready")) }
    }

    @Test
    fun `a bundle's own legal profile is used unless the option overrides it`() {
        write(dir, "eu.json", SampleBundles.leaky().copy(legalProfile = "EU"))
        val fromBundle = run(dir, "check", "eu.json")
        assertTrue(fromBundle.out, fromBundle.out.contains("Lens:     EU / EEA (EU, from the bundle)"))
        val overridden = run(dir, "check", "eu.json", "--profile", "india")
        assertTrue(overridden.out, overridden.out.contains("Lens:     India (INDIA, from --profile)"))

        write(dir, "mars.json", SampleBundles.leaky().copy(legalProfile = "MARS"))
        val unknown = run(dir, "check", "mars.json")
        assertTrue(unknown.out, unknown.out.contains("Warning:  the bundle names legal lens \"MARS\", which this version does not know; using GLOBAL_BASELINE"))
    }

    @Test
    fun `json output carries the lens, its relations and score`() {
        val r = run(dir, "check", leaky, "--profile", "us_california", "--format", "json")
        val lens = Json.parseToJsonElement(r.out).jsonObject["bundles"]!!.jsonArray[0].jsonObject["legalLens"]!!.jsonObject
        assertEquals("US_CALIFORNIA", lens["profile"]!!.jsonPrimitive.content)
        assertEquals("option", lens["source"]!!.jsonPrimitive.content)
        assertEquals("21", lens["score"]!!.jsonPrimitive.content)
        val rel = lens["relations"]!!.jsonArray.associate { it.jsonObject["checkId"]!!.jsonPrimitive.content to it.jsonObject["relation"]!!.jsonPrimitive.content }
        assertEquals("REQUIRED", rel["C2"])
        assertEquals("CONTEXT", rel["C1"])
        assertTrue(lens["caveat"]!!.jsonPrimitive.content.contains("not a legal verdict"))
    }

    @Test
    fun `an unknown profile is a usage error`() {
        val r = run(dir, "check", leaky, "--profile", "gdpr")
        assertEquals(Exit.USAGE, r.exit)
        assertTrue(r.err, r.err.contains("unknown legal profile \"gdpr\""))
        assertTrue(r.err, r.err.contains("US_CALIFORNIA"))
        assertTrue(run(dir, "--help").out.contains("--profile"))
    }
}
