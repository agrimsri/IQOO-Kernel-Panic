package com.dpdpxray.app.ui.onboarding

import com.dpdpxray.app.data.Audience
import com.dpdpxray.app.ui.guide.Bi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingCopyTest {
    private fun hasHindi(b: Bi) = b.hi.any { it in 'ऀ'..'ॿ' }

    @Test fun `five pages in the agreed order, the mode choice second`() {
        assertEquals(listOf("promise", "audience", "how", "needs", "try"), OnboardingCopy.pages.map { it.key })
        assertEquals("Apps promise.\nTraceVeil checks.", OnboardingCopy.pages.first().title.en)
        assertEquals("How will you use TraceVeil?", OnboardingCopy.pages[1].title.en)
        Audience.entries.forEach { a -> assertEquals(OnboardingCopy.pages.map { it.key }, OnboardingCopy.pagesFor(a).map { it.key }) }
        assertEquals(OnboardingCopy.pages, OnboardingCopy.pagesFor(Audience.DEVELOPER))
    }

    @Test fun `every page is written in both languages and is short to hear`() {
        Audience.entries.flatMap { OnboardingCopy.pagesFor(it) }.forEach { p ->
            listOf(p.title, p.body, p.spoken).forEach { assertTrue(p.key, it.en.isNotBlank() && hasHindi(it)) }
            assertTrue("${p.key} spoken is too long", p.spoken.en.length <= 160)
            assertFalse("${p.key} spoken names an app", p.spoken.en.contains("LeakyShop") || p.spoken.hi.contains("LeakyShop"))
        }
        (OnboardingCopy.needs.map { it.body } + OnboardingCopy.privacy + OnboardingCopy.exampleCaption +
            listOf(OnboardingCopy.sample, OnboardingCopy.demo, OnboardingCopy.apk).flatMap { listOf(it.title, it.detail) } +
            OnboardingCopy.audiences.flatMap { listOf(it.title, it.tagline, it.detail) } +
            listOf(OnboardingCopy.skip, OnboardingCopy.next, OnboardingCopy.back, OnboardingCopy.done, OnboardingCopy.readAloud, OnboardingCopy.pickOne))
            .forEach { assertTrue(it.en, hasHindi(it)) }
    }

    @Test fun `the two front doors say who they are for`() {
        assertEquals(listOf(Audience.USER, Audience.DEVELOPER), OnboardingCopy.audiences.map { it.audience })
        val (user, dev) = OnboardingCopy.audiences
        assertEquals("I use apps", user.title.en)
        assertEquals("Protect my phone", user.tagline.en)
        assertTrue(user.detail.en.contains("No"))
        assertEquals("I build apps", dev.title.en)
        assertEquals("Test my app", dev.tagline.en)
        assertTrue(dev.detail.en.contains("CI"))
    }

    @Test fun `vpn and watcher are required, shizuku is optional`() {
        assertEquals(mapOf("vpn" to true, "watcher" to true, "shizuku" to false), OnboardingCopy.needs.associate { it.key to it.required })
        assertEquals(OnboardingCopy.needs, OnboardingCopy.needsFor(Audience.DEVELOPER))
    }

    @Test fun `everyday onboarding skips developer jargon and leads with the file check`() {
        assertEquals(listOf("vpn", "watcher"), OnboardingCopy.needsFor(Audience.USER).map { it.key })
        val user = OnboardingCopy.pagesFor(Audience.USER).filter { it.key == "needs" || it.key == "try" }
            .flatMap { listOf(it.title.en, it.body.en, it.spoken.en) }.joinToString(" ").lowercase()
        listOf("shizuku", "safe", "malware").forEach { assertFalse(it, user.contains(it)) }
        assertEquals(listOf("apk", "sample", "demo"), OnboardingCopy.choicesFor(Audience.USER).map { it.key })
        assertEquals(listOf("sample", "demo", "apk"), OnboardingCopy.choicesFor(Audience.DEVELOPER).map { it.key })
    }

    @Test fun `privacy wording is qualified and makes no safety promise`() {
        assertTrue(OnboardingCopy.privacy.en.contains("by default"))
        val all = (Audience.entries.flatMap { OnboardingCopy.pagesFor(it) }.flatMap { listOf(it.title.en, it.body.en, it.spoken.en) } +
            OnboardingCopy.needs.map { it.body.en } + OnboardingCopy.audiences.flatMap { listOf(it.title.en, it.tagline.en, it.detail.en) })
            .joinToString(" ").lowercase()
        listOf("is safe", "compliant", "certified", "malicious", "malware", "fraud", "illegal", "dpdp", "₹").forEach { assertFalse(it, all.contains(it)) }
        assertTrue(OnboardingCopy.exampleCaption.en.contains("not proof"))
    }
}
