package com.dpdpxray.core.apk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallSourcesTest {
    @Test fun `google play installer is play store`() {
        val o = InstallSources.classify("com.android.vending")
        assertEquals(InstallSource.PLAY_STORE, o.source)
        assertEquals("Google Play", o.storeName)
        assertEquals("com.android.vending", o.installerPackage)
    }

    @Test fun `known other stores are named`() {
        val o = InstallSources.classify("com.sec.android.app.samsungapps")
        assertEquals(InstallSource.OTHER_STORE, o.source)
        assertEquals("Samsung Galaxy Store", o.storeName)
        assertEquals(InstallSource.OTHER_STORE, InstallSources.classify("com.bbk.appstore").source)
    }

    @Test fun `android package installers mean an APK file`() {
        listOf("com.google.android.packageinstaller", "com.android.packageinstaller", "com.samsung.android.packageinstaller", "com.android.shell").forEach {
            assertEquals(it, InstallSource.FILE, InstallSources.classify(it).source)
        }
    }

    @Test fun `android's own file hint wins over an unrecognised installer`() {
        assertEquals(InstallSource.FILE, InstallSources.classify("com.vendor.installer", PackageSourceHint.DOWNLOADED_FILE).source)
        assertEquals(InstallSource.FILE, InstallSources.classify("com.vendor.installer", PackageSourceHint.LOCAL_FILE).source)
    }

    @Test fun `an unknown installing app is another app, never assumed to be a store`() {
        val o = InstallSources.classify("com.some.dropper", PackageSourceHint.STORE)
        assertEquals(InstallSource.OTHER_APP, o.source)
        assertNull(o.storeName)
    }

    @Test fun `no installer is preinstalled for system apps and unknown otherwise`() {
        assertEquals(InstallSource.PREINSTALLED, InstallSources.classify(null, isSystemApp = true).source)
        assertEquals(InstallSource.UNKNOWN, InstallSources.classify(null).source)
        assertEquals(InstallSource.UNKNOWN, InstallSources.classify("  ").source)
    }

    @Test fun `a system app updated through play is play`() {
        assertEquals(InstallSource.PLAY_STORE, InstallSources.classify("com.android.vending", isSystemApp = true).source)
    }

    @Test fun `only play and preinstalled count as on play`() {
        assertFalse(InstallSource.PLAY_STORE.offPlay)
        assertFalse(InstallSource.PREINSTALLED.offPlay)
        listOf(InstallSource.OTHER_STORE, InstallSource.FILE, InstallSource.OTHER_APP, InstallSource.UNKNOWN, InstallSource.NOT_INSTALLED)
            .forEach { assertTrue(it.name, it.offPlay) }
    }
}
