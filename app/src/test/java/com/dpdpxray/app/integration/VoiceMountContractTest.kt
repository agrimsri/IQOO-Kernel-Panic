package com.dpdpxray.app.integration

import com.dpdpxray.app.ui.AuditRoutes
import com.dpdpxray.app.voice.MicAvailability
import com.dpdpxray.app.voice.MicState
import com.dpdpxray.core.copilot.CopilotLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class VoiceMountContractTest {
    @Test fun `saved audit route selects that exact audit`() {
        assertEquals("voice/sample-1791582328055", AuditRoutes.voice("sample-1791582328055"))
        assertEquals("voice/audit-123_REJECT", AuditRoutes.voice("audit-123_REJECT"))
    }

    @Test fun `empty or path shaped audit ids cannot select another destination`() {
        listOf("", "../settings", "audit/123", "audit?123").forEach {
            assertThrows(IllegalArgumentException::class.java) { AuditRoutes.voice(it) }
        }
    }

    @Test fun `manifest lets offline mic request permission instead of disabling the feature`() {
        val file = File("src/main/AndroidManifest.xml").takeIf { it.exists() } ?: File("app/src/main/AndroidManifest.xml")
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val nodes = factory.newDocumentBuilder().parse(file).getElementsByTagName("uses-permission")
        val permissions = (0 until nodes.length).map {
            (nodes.item(it) as org.w3c.dom.Element).getAttributeNS("http://schemas.android.com/apk/res/android", "name")
        }.toSet()
        assertEquals(MicState.NeedsPermission, MicAvailability.evaluate(
            sdkInt = 36, permissionDeclared = "android.permission.RECORD_AUDIO" in permissions,
            permissionGranted = false, onDeviceAvailable = true, installedLanguages = listOf("en-US"), language = CopilotLanguage.EN,
        ))
    }
}
