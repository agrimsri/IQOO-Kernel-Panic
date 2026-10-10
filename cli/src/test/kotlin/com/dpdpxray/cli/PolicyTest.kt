package com.dpdpxray.cli

import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.CheckId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PolicyTest {
    @Test
    fun `defaults fail on the critical checks, require fresh full captures and reject inconclusive results`() {
        val p = Policy()
        assertEquals(listOf(CheckId.C2, CheckId.C9), p.failOn)
        assertNull(p.minScore)
        assertNull(p.maxHigh)
        assertTrue(p.requireFreshState)
        assertEquals(false, p.allowInconclusive)
        assertEquals(false, p.allowNotEstablished)
        assertEquals(listOf(AuditPlan.ACCEPT, AuditPlan.REJECT, AuditPlan.SILENT), p.requirePlans)
        assertEquals(p, Policy.parse("{}"))
    }

    @Test
    fun `the documented example policy parses`() {
        val p = Policy.parse("""{"failOn": ["C2","C9"], "minScore": 85, "maxHigh": 0, "requireFreshState": true, "allowInconclusive": false}""")
        assertEquals(Policy(failOn = listOf(CheckId.C2, CheckId.C9), minScore = 85, maxHigh = 0), p)
    }

    @Test
    fun `missing keys keep their defaults and names are case-insensitive`() {
        val p = Policy.parse("""{"failOn": ["c1", "nc", "C1"], "requirePlans": ["silent"], "_comment": "kept-data smoke test", "${'$'}schema": "x"}""")
        assertEquals(listOf(CheckId.C1, CheckId.NC), p.failOn)
        assertEquals(listOf(AuditPlan.SILENT), p.requirePlans)
        assertTrue(p.requireFreshState)
        assertNull(p.minScore)
        assertEquals(Policy(minScore = null), Policy.parse("""{"minScore": null}"""))
    }

    @Test
    fun `effective policy round-trips through its JSON`() {
        val p = Policy(
            failOn = listOf(CheckId.C1), minScore = 60, maxHigh = 2, requireFreshState = false, allowInconclusive = true,
            allowNotEstablished = true, requirePlans = emptyList(),
        )
        assertEquals(p, Policy.parse(p.toJson().toString()))
    }

    @Test
    fun `unusable policies are usage errors with a reason`() {
        fun bad(text: String, fragment: String) {
            try {
                Policy.parse(text)
                fail("expected an error for $text")
            } catch (e: UsageException) {
                assertTrue("'${e.message}' should mention '$fragment'", e.message!!.contains(fragment))
            }
        }
        bad("""{"minscore": 85}""", "unknown policy key \"minscore\"")
        bad("""{"failOn": ["C99"]}""", "unknown check \"C99\"")
        bad("""{"failOn": "C2"}""", "failOn must be a list")
        bad("""{"minScore": 101}""", "between 0 and 100")
        bad("""{"minScore": "85"}""", "whole number")
        bad("""{"maxHigh": -1}""", "maxHigh must be between 0")
        bad("""{"requireFreshState": "yes"}""", "true or false")
        bad("""{"allowNotEstablished": 1}""", "allowNotEstablished must be true or false")
        bad("""{"requirePlans": ["LATER"]}""", "unknown plan \"LATER\"")
        bad("""[1]""", "JSON object")
        bad("""{broken""", "not valid JSON")
    }
}
