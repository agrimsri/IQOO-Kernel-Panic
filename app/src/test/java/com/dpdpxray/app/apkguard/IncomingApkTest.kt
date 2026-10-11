package com.dpdpxray.app.apkguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How an APK handed to TraceVeil by another app (Chrome, Files, WhatsApp) is accepted, refused or ignored. */
class IncomingApkTest {
    private val apk = IncomingApk.APK_MIME
    private val view = "android.intent.action.VIEW"
    private val send = "android.intent.action.SEND"
    private val install = "android.intent.action.INSTALL_PACKAGE"

    private fun check(uri: String) = IncomingApk.Parsed.Check(uri)

    @Test fun `an opened APK from Chrome downloads, Files or a chat app is checked`() {
        assertEquals(check("content://com.android.chrome.FileProvider/downloads/app.apk"),
            IncomingApk.parse(view, apk, "content://com.android.chrome.FileProvider/downloads/app.apk", null))
        assertEquals(check("content://com.android.providers.downloads.documents/document/42"),
            IncomingApk.parse(view, apk, "content://com.android.providers.downloads.documents/document/42", null))
        assertEquals(check("content://media/external/downloads/1234"), IncomingApk.parse(view, apk, "content://media/external/downloads/1234", null))
    }

    @Test fun `a shared APK is read from the stream, not the data`() {
        assertEquals(check("content://com.whatsapp.provider.media/item/7"), IncomingApk.parse(send, apk, null, "content://com.whatsapp.provider.media/item/7"))
        assertEquals(check("content://files/2"), IncomingApk.parse(send, apk, "content://ignored/1", "content://files/2"))
    }

    @Test fun `file managers that still send INSTALL_PACKAGE are treated like an open`() {
        assertEquals(check("content://com.mi.android.globalFileexplorer.myprovider/x.apk"),
            IncomingApk.parse(install, apk, "content://com.mi.android.globalFileexplorer.myprovider/x.apk", null))
    }

    @Test fun `the MIME type and scheme are matched without regard to case, and surrounding spaces are ignored`() {
        assertEquals(check("CONTENT://x/1"), IncomingApk.parse(view, "Application/Vnd.Android.Package-Archive", "  CONTENT://x/1 ", null))
    }

    @Test fun `a file uri is refused with a reason, because Android no longer lets apps share files that way`() {
        val parsed = IncomingApk.parse(view, apk, "file:///sdcard/Download/x.apk", null)
        assertTrue(parsed is IncomingApk.Parsed.Unreadable)
        assertTrue((parsed as IncomingApk.Parsed.Unreadable).reason.contains("Files"))
        assertTrue(IncomingApk.parse(send, apk, null, "file:///sdcard/x.apk") is IncomingApk.Parsed.Unreadable)
    }

    @Test fun `an APK request without a file, or with a web or empty address, is refused with a reason`() {
        assertTrue(IncomingApk.parse(send, apk, null, null) is IncomingApk.Parsed.Unreadable)
        assertTrue(IncomingApk.parse(view, apk, "   ", null) is IncomingApk.Parsed.Unreadable)
        assertTrue(IncomingApk.parse(view, apk, "content://", null) is IncomingApk.Parsed.Unreadable)
        assertTrue(IncomingApk.parse(send, apk, null, "https://example.com/app.apk") is IncomingApk.Parsed.Unreadable)
    }

    @Test fun `anything that is not an APK request is left to the normal app start`() {
        assertNull(IncomingApk.parse(view, "image/png", "content://x/1", null))
        assertNull(IncomingApk.parse(view, null, "content://x/1.apk", null))
        assertNull(IncomingApk.parse("android.intent.action.MAIN", apk, "content://x/1", null))
        assertNull(IncomingApk.parse(null, null, null, null))
        assertNull(IncomingApk.parse("android.intent.action.SEND_MULTIPLE", apk, null, "content://x/1"))
    }

    @Test fun `pickUri keeps returning only readable content uris`() {
        assertEquals("content://wa/1", IncomingApk.pickUri(view, apk, "content://wa/1", null))
        assertNull(IncomingApk.pickUri(view, apk, "file:///sdcard/x.apk", null))
        assertNull(IncomingApk.pickUri(send, apk, null, null))
    }
}
