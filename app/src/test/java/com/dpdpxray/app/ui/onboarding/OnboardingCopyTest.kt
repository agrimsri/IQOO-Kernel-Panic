package com.dpdpxray.app.ui.onboarding

import com.dpdpxray.app.ui.guide.Bi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingCopyTest {
    private fun hasHindi(b: Bi) = b.hi.any { it in 'ऀ'..'ॿ' }

    @Test fun `four pages in the agreed order`() {
        assertEquals(listOf("promise", "how", "needs", "try"), OnboardingCopy.pages.map { it.key })
        assertEquals("Apps promise.\nTraceVeil checks.", OnboardingCopy.pages.first().title.en)
    }

    @Test fun `every page is written in both languages and is short to hear`() {
        OnboardingCopy.pages.forEach { p ->
            listOf(p.title, p.body, p.spoken).forEach { assertTrue(p.key, it.en.isNotBlank() && hasHindi(it)) }
            assertTrue("${p.key} spoken is too long", p.spoken.en.length <= 160)
        }
        (OnboardingCopy.needs.map { it.body } + OnboardingCopy.privacy + OnboardingCopy.exampleCaption +
            listOf(OnboardingCopy.sample, OnboardingCopy.demo, OnboardingCopy.apk).flatMap { listOf(it.title, it.detail) } +
            listOf(OnboardingCopy.skip, OnboardingCopy.next, OnboardingCopy.back, OnboardingCopy.done, OnboardingCopy.readAloud))
            .forEach { assertTrue(it.en, hasHindi(it)) }
    }

    @Test fun `vpn and watcher are required, shizuku is optional`() {
        assertEquals(mapOf("vpn" to true, "watcher" to true, "shizuku" to false), OnboardingCopy.needs.associate { it.key to it.required })
    }

    @Test fun `privacy wording is qualified and makes no safety promise`() {
        assertTrue(OnboardingCopy.privacy.en.contains("by default"))
        val all = (OnboardingCopy.pages.flatMap { listOf(it.title.en, it.body.en, it.spoken.en) } + OnboardingCopy.needs.map { it.body.en }).joinToString(" ").lowercase()
        listOf("is safe", "compliant", "certified", "malicious", "fraud", "illegal").forEach { assertFalse(it, all.contains(it)) }
        assertTrue(OnboardingCopy.exampleCaption.en.contains("not proof"))
    }
}
