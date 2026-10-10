package com.dpdpxray.core.apk

import com.dpdpxray.core.ml.PermissionRiskModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkModelSignalTest {
    private val model = PermissionRiskModel.loadDefault()
    private val play = InstallOrigin(InstallSource.PLAY_STORE, "com.android.vending", "Google Play")
    private val apkFile = InstallOrigin(InstallSource.NOT_INSTALLED)

    private fun p(name: String) = "android.permission.$name"
    private fun facts(permissions: Set<String>, origin: InstallOrigin = play) = ApkFacts(
        packageName = "org.example.notes", label = "Notes", versionName = "1.0", versionCode = 1, targetSdk = 35,
        permissions = permissions, origin = origin, certSha256 = listOf("AB".repeat(32)), signerSubjects = listOf("CN=Example"),
        hasLauncherIcon = true,
    )

    /** Elevated in the shipped model (same set as PermissionRiskModelTest's "high" case). */
    private val elevatedSet = setOf(p("READ_PHONE_STATE"), p("SEND_SMS"), p("RECEIVE_SMS"))
    private val lowSet = setOf(p("INTERNET"), "com.google.android.c2dm.permission.RECEIVE", "com.android.vending.BILLING")

    /** Declared permissions of the TEST Challan demo app (riskdemo), as Android lists them for its APK. */
    private val riskDemo = setOf(
        p("INTERNET"), p("READ_SMS"), p("RECEIVE_SMS"), p("READ_CONTACTS"), p("SYSTEM_ALERT_WINDOW"),
        "com.traceveil.riskdemo.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
    )

    private fun card(report: ApkGuardReport) = report.signals.single { it.id == ApkModelSignal.ID }

    @Test fun `the model card is inferred and worth knowing only when the score is elevated`() {
        val high = ApkModelSignal.attach(ApkRiskRules.evaluate(facts(elevatedSet)), model)
        val low = ApkModelSignal.attach(ApkRiskRules.evaluate(facts(lowSet)), model)
        assertTrue(model.score(elevatedSet).elevated)
        assertFalse(model.score(lowSet).elevated)
        assertEquals(ApkEvidence.INFERRED, card(high).evidence)
        assertEquals(ApkEvidence.INFERRED, card(low).evidence)
        assertEquals(ApkSeverity.WORTH_KNOWING, card(high).severity)
        assertEquals(ApkSeverity.INFO, card(low).severity)
        assertEquals("Permission profile model", card(high).title.en)
    }

    @Test fun `the model card is never review carefully, whatever the permissions`() {
        val sets = listOf(emptySet(), elevatedSet, lowSet, riskDemo, model.weights.keys, model.weights.filterValues { it > 0 }.keys)
        for (perms in sets) for (origin in listOf(play, apkFile)) {
            val c = card(ApkModelSignal.attach(ApkRiskRules.evaluate(facts(perms, origin)), model))
            assertNotEquals(ApkSeverity.REVIEW_CAREFULLY, c.severity)
            assertEquals(if (model.score(perms).elevated) ApkSeverity.WORTH_KNOWING else ApkSeverity.INFO, c.severity)
        }
    }

    @Test fun `adding the model card changes no rule signal and not the rules' summary`() {
        for (perms in listOf(elevatedSet, lowSet, riskDemo, emptySet())) for (origin in listOf(play, apkFile)) {
            val rules = ApkRiskRules.evaluate(facts(perms, origin))
            val withModel = ApkModelSignal.attach(rules, model)
            assertEquals(rules.signals, withModel.signals.filter { it.id != ApkModelSignal.ID })
            assertEquals(rules.summary, withModel.summary)
            assertEquals(rules.facts, withModel.facts)
            val order = withModel.signals.map { it.severity.ordinal }
            assertEquals(order.sorted(), order)
        }
    }

    @Test fun `the card shows the model summary, the top three raising permissions with weights and the coverage`() {
        val score = model.score(elevatedSet)
        val c = card(ApkModelSignal.attach(ApkRiskRules.evaluate(facts(elevatedSet)), model))
        assertEquals(score.summary(), c.detail.en)
        val raising = score.raising.take(3)
        assertEquals(raising.size + 1, c.proof.size)
        raising.forEachIndexed { i, r ->
            assertTrue(c.proof[i], c.proof[i].startsWith(r.permission))
            assertTrue(c.proof[i], c.proof[i].contains(ApkModelSignal.weight(r.weight)))
        }
        assertEquals("Based on ${score.recognised} of 85 recognised permissions", c.proof.last())
        assertTrue(c.detail.hi.contains("मॉडल स्कोर अनुमान है, मैलवेयर का फ़ैसला नहीं"))
    }

    @Test fun `a low score says it does not clear the app, in both languages`() {
        val c = card(ApkModelSignal.attach(ApkRiskRules.evaluate(facts(lowSet)), model))
        assertTrue(c.detail.en.contains("A low score does not clear the app"))
        assertTrue(c.detail.hi.contains("कम स्कोर"))
        assertTrue(c.detail.hi.contains("मॉडल स्कोर अनुमान है, मैलवेयर का फ़ैसला नहीं"))
    }

    @Test fun `the TEST Challan demo app gets an info card from its declared permissions`() {
        val c = card(ApkModelSignal.attach(ApkRiskRules.evaluate(facts(riskDemo, apkFile)), model))
        val score = model.score(riskDemo)
        assertEquals(5, score.recognised)
        assertEquals(if (score.elevated) ApkSeverity.WORTH_KNOWING else ApkSeverity.INFO, c.severity)
        // Printed into the JUnit XML so the exact card text can be quoted in demo notes.
        println("TEST Challan model card [${c.severity.label.en} · ${c.evidence.label.en.uppercase()}]\n${c.title.en}\n${c.detail.en}\n${c.proof.joinToString("\n")}\n${c.detail.hi}\nBasis: ${c.basis}")
    }

    @Test fun `model card text passes the APK Guard wording rules`() {
        val cards = listOf(elevatedSet, lowSet, riskDemo, emptySet()).map { card(ApkModelSignal.attach(ApkRiskRules.evaluate(facts(it)), model)) }
        val devanagari = Regex("[\\u0900-\\u097F]")
        for (c in cards) {
            val texts = listOfNotNull(c.title, c.detail, c.advice, c.state, c.severity.label, c.evidence.label)
            texts.forEach { t ->
                assertTrue("blank English in $t", t.en.isNotBlank())
                assertTrue("Hindi missing Devanagari: ${t.hi}", devanagari.containsMatchIn(t.hi))
            }
            val english = texts.map { it.en } + c.proof + listOfNotNull(c.basis)
            // The model card may name its training class only in these two phrases; it never calls the app malware.
            val allowed = listOf("malware samples", "malware verdict")
            english.forEach { raw ->
                val t = allowed.fold(raw.lowercase()) { acc, phrase -> acc.replace(phrase, "") }
                APK_FORBIDDEN_EN.forEach { w -> assertFalse("'$w' in: $raw", t.contains(w)) }
            }
            texts.forEach { t -> APK_FORBIDDEN_HI.forEach { w -> assertFalse("'$w' in: ${t.hi}", t.hi.contains(w)) } }
        }
    }
}
