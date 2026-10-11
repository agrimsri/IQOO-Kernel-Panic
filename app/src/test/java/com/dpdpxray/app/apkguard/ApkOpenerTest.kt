package com.dpdpxray.app.apkguard

import com.dpdpxray.app.ui.guide.GuideLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which app Android uses when the person taps an APK file, so APK Guard can say whether TraceVeil checks it first. */
class ApkOpenerTest {
    private val own = "com.dpdpxray.app"
    private val installer = "com.android.packageinstaller"
    private val handlers = setOf(own, installer)

    @Test fun `TraceVeil chosen with Always checks every tapped APK first`() {
        assertEquals(ApkOpener.State.TRACEVEIL, ApkOpener.state(own, handlers, own))
    }

    @Test fun `a resolver that is not one of the handlers means Android asks each time`() {
        assertEquals(ApkOpener.State.ASK_EACH_TIME, ApkOpener.state("android", handlers, own))
        assertEquals(ApkOpener.State.ASK_EACH_TIME, ApkOpener.state("com.android.intentresolver", handlers, own))
    }

    @Test fun `another handler set as default opens APKs directly`() {
        assertEquals(ApkOpener.State.OTHER_APP, ApkOpener.state(installer, handlers, own))
    }

    @Test fun `nothing reported, or TraceVeil not even offered, is said plainly`() {
        assertEquals(ApkOpener.State.UNKNOWN, ApkOpener.state(null, handlers, own))
        assertEquals(ApkOpener.State.NOT_OFFERED, ApkOpener.state("android", setOf(installer), own))
    }

    @Test fun `every state has a line in both languages that tells the person what to do`() {
        ApkOpener.State.entries.forEach { state ->
            val en = ApkOpener.summary(state, "Package installer").pick(GuideLang.EN)
            val hi = ApkOpener.summary(state, "Package installer").pick(GuideLang.HI)
            assertTrue(state.name, en.isNotBlank() && hi.isNotBlank())
        }
        assertTrue(ApkOpener.summary(ApkOpener.State.ASK_EACH_TIME, null).en.contains("Always"))
        assertTrue(ApkOpener.summary(ApkOpener.State.OTHER_APP, "Package installer").en.contains("Package installer"))
    }
}
