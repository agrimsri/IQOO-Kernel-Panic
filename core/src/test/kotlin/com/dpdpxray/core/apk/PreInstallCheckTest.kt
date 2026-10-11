package com.dpdpxray.core.apk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreInstallCheckTest {
    private val a = "AB".repeat(32)
    private val b = "CD".repeat(32)

    private fun facts(
        certs: List<String> = listOf(a),
        twin: InstalledTwin? = null,
        version: Long? = 20,
        targetSdk: Int? = 35,
        permissions: Set<String> = emptySet(),
    ) = ApkFacts(
        packageName = "com.whatsapp", label = "WhatsApp Update", versionName = "2.2", versionCode = version, targetSdk = targetSdk,
        permissions = permissions, origin = InstallOrigin(InstallSource.NOT_INSTALLED), certSha256 = certs, installedTwin = twin,
    )

    private fun advise(f: ApkFacts, deviceSdk: Int = 36) = PreInstallCheck.advise(ApkRiskRules.evaluate(f), deviceSdk)

    @Test fun `a fresh install with nothing to review offers Install anyway first`() {
        val advice = advise(facts())
        assertEquals(InstallRelation.NEW_INSTALL, advice.relation)
        assertNull(advice.block)
        assertNull(advice.banner)
        assertFalse(advice.leanDontInstall)
    }

    @Test fun `something to review carefully puts Don't install first, but still allows the install`() {
        val advice = advise(facts(permissions = setOf("android.permission.READ_SMS")))
        assertTrue(advice.leanDontInstall)
        assertNull(advice.block)
    }

    @Test fun `a different signer than the installed app blocks the install with a red-flag banner`() {
        val advice = advise(facts(certs = listOf(b), twin = InstalledTwin("WhatsApp", listOf(a), versionCode = 10)))
        assertEquals(InstallRelation.DIFFERENT_SIGNER, advice.relation)
        assertEquals(InstallBlock.DIFFERENT_SIGNER, advice.block)
        assertTrue(advice.leanDontInstall)
        assertNotNull(advice.banner)
        val banner = advice.banner!!
        assertEquals(BannerTone.RISK, banner.tone)
        assertTrue(banner.text.en, banner.text.en.contains("different key than your installed \"WhatsApp\""))
        assertTrue(banner.text.en, banner.text.en.contains("Do not uninstall"))
        assertTrue(banner.text.hi.isNotBlank())
    }

    @Test fun `an older version than the installed app is blocked, because Android refuses downgrades`() {
        val advice = advise(facts(version = 9, twin = InstalledTwin("WhatsApp", listOf(a), versionCode = 10, versionName = "2.1")))
        assertEquals(InstallRelation.SAME_SIGNER, advice.relation)
        assertEquals(InstallBlock.OLDER_VERSION, advice.block)
        assertTrue(advice.banner!!.text.en, advice.banner!!.text.en.contains("older version"))
    }

    @Test fun `a same-signer update is announced calmly and is not blocked`() {
        val advice = advise(facts(twin = InstalledTwin("WhatsApp", listOf(a), versionCode = 10)))
        assertEquals(InstallRelation.SAME_SIGNER, advice.relation)
        assertNull(advice.block)
        assertEquals(BannerTone.INFO, advice.banner!!.tone)
        assertTrue(advice.banner!!.text.en, advice.banner!!.text.en.contains("update"))
    }

    @Test fun `an unreadable signer is not established and not blocked`() {
        val advice = advise(facts(certs = emptyList(), twin = InstalledTwin("WhatsApp", listOf(a), versionCode = 10)))
        assertEquals(InstallRelation.SIGNER_UNKNOWN, advice.relation)
        assertNull(advice.block)
        assertTrue(advice.banner!!.text.en.contains("not established"))
    }

    @Test fun `apps built for very old Android versions are blocked where Android refuses them`() {
        assertEquals(InstallBlock.TOO_OLD_FOR_ANDROID, advise(facts(targetSdk = 23), deviceSdk = 36).block) // Android 15+: below 24
        assertNull(advise(facts(targetSdk = 23), deviceSdk = 34).block) // Android 14: below 23 only
        assertEquals(InstallBlock.TOO_OLD_FOR_ANDROID, advise(facts(targetSdk = 22), deviceSdk = 34).block)
        assertNull(advise(facts(targetSdk = 22), deviceSdk = 33).block)
        assertNull(advise(facts(targetSdk = null), deviceSdk = 36).block)
        assertEquals(24, PreInstallCheck.minimumTargetSdk(35))
        assertEquals(23, PreInstallCheck.minimumTargetSdk(34))
        assertNull(PreInstallCheck.minimumTargetSdk(33))
    }

    @Test fun `banners never call the file fake, malicious or safe`() {
        val cases = listOf(
            advise(facts(certs = listOf(b), twin = InstalledTwin("WhatsApp", listOf(a), versionCode = 10))),
            advise(facts(version = 9, twin = InstalledTwin("WhatsApp", listOf(a), versionCode = 10))),
            advise(facts(twin = InstalledTwin("WhatsApp", listOf(a), versionCode = 10))),
            advise(facts(targetSdk = 20)),
        )
        cases.mapNotNull { it.banner }.forEach { banner ->
            (APK_FORBIDDEN_EN + "fake").forEach { assertFalse("$it in ${banner.text.en}", banner.text.en.lowercase().contains(it)) }
            APK_FORBIDDEN_HI.forEach { assertFalse("$it in ${banner.text.hi}", banner.text.hi.contains(it)) }
        }
    }
}
