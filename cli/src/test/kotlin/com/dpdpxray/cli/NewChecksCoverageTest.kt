package com.dpdpxray.cli

import com.dpdpxray.cli.samples.SampleBundles
import com.dpdpxray.core.model.AppManifestFacts
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.CaptureHealth
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.model.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A gate on C11–C15 or V1 must not pass by absence when the capture did not record that check's evidence. */
class NewChecksCoverageTest {
    private val s = 1_000_000_000L
    private val old = SampleBundles.leakySessions // device-shaped, recorded before the new evidence existed

    @Test
    fun `older captures leave the new checks not established, each with its reason`() {
        assertEquals("no DNS replies were recorded, so aliases (CNAME chains) could not be read", Coverage.untested(CheckId.C11, old))
        assertEquals("no background step was recorded (older build, Fast Preview, or the app was not confirmed off screen)", Coverage.untested(CheckId.C12, old))
        assertEquals("the app's installed manifest was not read in this capture", Coverage.untested(CheckId.C13, old))
        assertEquals("Android permission dialogs were not watched in this capture (older build)", Coverage.untested(CheckId.C14, old))
        assertEquals("no consent-screen accessibility tree was captured", Coverage.untested(CheckId.C15, old))
        assertEquals("capture health and the Private DNS setting were not recorded", Coverage.untested(CheckId.V1, old))
    }

    @Test
    fun `captures with the evidence can be gated on the new checks`() {
        val tree = UiNode("android.widget.FrameLayout", children = listOf(UiNode("android.widget.TextView", "We value your privacy")))
        val recorded = AuditSession(
            id = "a", targetPackage = "com.shop.app", targetLabel = "Shop", plan = AuditPlan.REJECT,
            netEvents = listOf(NetEvent(1 * s, "api.shop.app", cnameChain = listOf("api.shop.app", "edge.cdn.example"), rcode = 0)),
            consentEvents = listOf(ConsentEvent(2 * s, ConsentKind.SCREEN_APPEARED, tree = tree)),
            backgroundStartedNanos = 30 * s, permissionDialogsWatched = true,
            manifestFacts = AppManifestFacts(partsRead = listOf(AppManifestFacts.PART_META_DATA)),
            captureHealth = CaptureHealth(hiddenDnsPackets = 0), privateDnsActive = false,
        )
        listOf(CheckId.C11, CheckId.C12, CheckId.C13, CheckId.C14, CheckId.C15, CheckId.V1)
            .forEach { assertNull(it.code, Coverage.untested(it, listOf(recorded))) }
    }
}
