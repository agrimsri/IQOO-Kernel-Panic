package com.dpdpxray.cli

import com.dpdpxray.cli.CliHarness.run
import com.dpdpxray.cli.CliHarness.samples
import com.dpdpxray.cli.samples.SampleBundles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** SARIF 2.1.0 as GitHub code scanning reads it: one result per finding, located in the bundle file. */
class SarifTest {
    @get:Rule val temp = TemporaryFolder()

    private val dir: File by lazy { samples(temp.newFolder("bundles")) }

    private fun sarif(vararg files: String): Pair<CliRun, JsonObject> {
        val r = run(dir, "check", *files, "--format", "sarif")
        return r to Json.parseToJsonElement(r.out).jsonObject
    }

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content
    private fun JsonObject.obj(key: String) = this[key]!!.jsonObject

    @Test
    fun `leaky sample has the required SARIF fields and one result per finding`() {
        val (r, root) = sarif(SampleBundles.LEAKY)
        assertEquals(Exit.VIOLATION, r.exit)
        assertEquals("2.1.0", root.str("version"))
        assertEquals(CheckSarif.SCHEMA_URI, root.str("\$schema"))
        val run = root["runs"]!!.jsonArray.single().jsonObject
        val driver = run.obj("tool").obj("driver")
        assertEquals("TraceVeil CI", driver.str("name"))
        val rules = driver["rules"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("C1", "C2", "C3", "C4", "C10"), rules.map { it.str("id") })
        rules.forEach { rule ->
            assertTrue(rule.obj("shortDescription").str("text").isNotBlank())
            assertTrue(rule.obj("fullDescription").str("text").contains("not proof that personal data was sent"))
            assertTrue(rule.obj("defaultConfiguration").str("level") in setOf("error", "warning", "note"))
        }
        val results = run["results"]!!.jsonArray.map { it.jsonObject }
        assertEquals(5, results.size)
        val bundleText = File(dir, SampleBundles.LEAKY).readText()
        for (res in results) {
            val id = res.str("ruleId")
            assertEquals(id, rules[res["ruleIndex"]!!.jsonPrimitive.int].str("id"))
            val message = res.obj("message").str("text")
            assertTrue(message, message.startsWith("[synthetic sample] $id "))
            assertTrue(message, message.contains("OBSERVED evidence") || message.contains("INFERRED evidence"))
            assertTrue(message, message.contains("A DNS lookup is an attempt to contact a host, not proof that personal data was sent."))
            val loc = res["locations"]!!.jsonArray.single().jsonObject.obj("physicalLocation")
            assertEquals(SampleBundles.LEAKY, loc.obj("artifactLocation").str("uri"))
            val line = loc.obj("region")["startLine"]!!.jsonPrimitive.int
            assertTrue("startLine points at the stored finding", bundleText.lines()[line - 1].contains("\"checkId\": \"$id\""))
            assertEquals("com.leakyshop.demo/$id", res.obj("partialFingerprints").str("traceveilFinding/v1"))
        }
        val byId = results.associateBy { it.str("ruleId") }
        assertEquals("error", byId.getValue("C2").str("level"))
        assertEquals("error", byId.getValue("C1").str("level"))
        assertEquals("warning", byId.getValue("C10").str("level"))
        assertTrue(byId.getValue("C10").obj("message").str("text").contains("INFERRED evidence: derived from SDK documentation"))
        assertTrue(byId.getValue("C2").obj("message").str("text").contains("Critical, OBSERVED evidence"))
        val invocation = run["invocations"]!!.jsonArray.single().jsonObject
        assertEquals("false", invocation.obj("properties").str("policyPassed"))
    }

    @Test
    fun `info findings are notes`() {
        val (_, root) = sarif(SampleBundles.KEPT_DATA)
        val results = root["runs"]!!.jsonArray.single().jsonObject["results"]!!.jsonArray.map { it.jsonObject }
        assertEquals(setOf("U1", "S1"), results.map { it.str("ruleId") }.toSet())
        assertTrue(results.all { it.str("level") == "note" })
    }

    @Test
    fun `a clean bundle gives a valid run with no results`() {
        val (r, root) = sarif(SampleBundles.FIXED)
        assertEquals(Exit.PASS, r.exit)
        val run = root["runs"]!!.jsonArray.single().jsonObject
        assertTrue(run["results"]!!.jsonArray.isEmpty())
        assertTrue(run.obj("tool").obj("driver")["rules"]!!.jsonArray.isEmpty())
        assertEquals("true", run["invocations"]!!.jsonArray.single().jsonObject.obj("properties").str("policyPassed"))
    }

    @Test
    fun `results from several bundles point at their own files`() {
        val (_, root) = sarif(SampleBundles.LEAKY, SampleBundles.KEPT_DATA)
        val uris = root["runs"]!!.jsonArray.single().jsonObject["results"]!!.jsonArray
            .map { it.jsonObject["locations"]!!.jsonArray.single().jsonObject.obj("physicalLocation").obj("artifactLocation").str("uri") }
        assertEquals(setOf(SampleBundles.LEAKY, SampleBundles.KEPT_DATA), uris.toSet())
        assertEquals(7, uris.size)
    }

    @Test
    fun `a bundle outside the working directory gets a file URI`() {
        val elsewhere = temp.newFolder("elsewhere")
        val r = run(elsewhere, "check", File(dir, SampleBundles.LEAKY).absolutePath, "--format", "sarif")
        val uri = Json.parseToJsonElement(r.out).jsonObject["runs"]!!.jsonArray.single().jsonObject["results"]!!.jsonArray.first().jsonObject
            .get("locations")!!.jsonArray.single().jsonObject.obj("physicalLocation").obj("artifactLocation").str("uri")
        assertTrue(uri, uri.startsWith("file:/") && uri.endsWith("/bundles/${SampleBundles.LEAKY}"))
    }

    @Test
    fun `line lookup falls back to the first line`() {
        assertEquals(3, CheckSarif.lineOf("{\n  \"x\": 1,\n  \"checkId\": \"C2\"\n}", "C2"))
        assertEquals(1, CheckSarif.lineOf("{\"checkId\": \"C1\"}", "C9"))
        assertEquals(1, CheckSarif.lineOf("{\"checkId\":\"C1\"}", "C1"))
    }
}
