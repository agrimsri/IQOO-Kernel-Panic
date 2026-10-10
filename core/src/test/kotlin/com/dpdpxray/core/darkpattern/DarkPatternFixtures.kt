package com.dpdpxray.core.darkpattern

import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.Bounds
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.UiNode

/**
 * Consent-screen trees for the dark-pattern tests.
 *
 * [leakyDialog] and [fixedDialog] copy the node classes, labels and bounds of the trees the phone recorded for the
 * LeakyShop builds (audit-1791595268053 Plan B and audit-1791595440002 Plan C, 10 Oct 2026). [nagPopup] is the second
 * popup the leaky build now shows 5 s after "Reject all" (leakyshop Shop.kt); its bounds are a synthetic layout, not a
 * device reading. Test data only.
 */
object DarkPatternFixtures {
    private const val S = 1_000_000_000L

    fun b(l: Int, t: Int, r: Int, bt: Int) = Bounds(l, t, r, bt)

    fun text(s: String, bounds: Bounds, clickable: Boolean = false) =
        UiNode(className = "android.widget.TextView", text = s, isClickable = clickable, bounds = bounds)

    /** A Compose Material button as Android reports it: a clickable View with the label and a Button-role child. */
    fun composeButton(label: String, outer: Bounds, textBounds: Bounds, inner: Bounds) = UiNode(
        className = "android.view.View", isClickable = true, bounds = outer,
        children = listOf(UiNode(className = "android.widget.TextView", text = label, bounds = textBounds), UiNode(className = "android.widget.Button", bounds = inner)),
    )

    /** The window and layout wrappers Compose puts around an AlertDialog's content. */
    fun dialog(bounds: Bounds, vararg content: UiNode): UiNode {
        var node = UiNode(className = "android.view.View", bounds = bounds, children = content.toList())
        repeat(3) { node = UiNode(className = "android.view.View", bounds = bounds, children = listOf(node)) }
        node = UiNode(className = "android.view.ViewGroup", bounds = bounds, children = listOf(node))
        repeat(3) { node = UiNode(className = "android.widget.FrameLayout", bounds = bounds, children = listOf(node)) }
        return node
    }

    /** LeakyShop leaky, first popup (unchanged by the nag): Accept is a button, the only refusal path is "Manage" text. */
    val leakyDialog = dialog(
        b(370, 1117, 1511, 2123),
        text("We value your privacy", b(455, 1202, 1279, 1317)),
        text("We use your data to personalise offers, measure the app and show relevant ads.", b(455, 1374, 1426, 1516)),
        UiNode(className = "android.widget.CheckBox", isCheckable = true, isChecked = true, isClickable = true, bounds = b(456, 1517, 628, 1689)),
        text("Share usage data to improve offers", b(627, 1567, 1426, 1638)),
        text("Manage", b(449, 1649, 620, 1820), clickable = true),
        composeButton("Accept & Continue", b(839, 1867, 1426, 2038), b(924, 1917, 1340, 1988), b(839, 1881, 1426, 2024)),
    )

    /** LeakyShop fixed, as recorded: Reject and Accept are buttons of about the same size. */
    val fixedDialog = dialog(
        b(418, 887, 1561, 2353),
        text("Your privacy choices", b(504, 973, 1289, 1087)),
        text("We collect your email, phone number and order history to deliver orders. The choices below are optional.", b(504, 1144, 1475, 1358)),
        text("Analytics to improve the app", b(504, 1409, 1289, 1480)),
        UiNode(className = "android.view.View", isCheckable = true, isClickable = true, bounds = b(1289, 1358, 1475, 1530)),
        text("Personalised offers", b(504, 1580, 1289, 1652)),
        UiNode(className = "android.view.View", isCheckable = true, isClickable = true, bounds = b(1289, 1530, 1475, 1701)),
        text("You can withdraw consent anytime in Settings › Privacy, or complain to the Data Protection Board.", b(504, 1701, 1475, 1915)),
        text("English", b(503, 1888, 671, 2059), clickable = true),
        text("हिन्दी", b(671, 1888, 843, 2059), clickable = true),
        text("ಕನ್ನಡ", b(840, 1937, 949, 2011)),
        composeButton("Reject", b(804, 2096, 1118, 2268), b(890, 2147, 1032, 2218), b(804, 2111, 1118, 2253)),
        composeButton("Accept", b(1146, 2096, 1475, 2268), b(1232, 2147, 1389, 2218), b(1146, 2111, 1475, 2253)),
    )

    /** LeakyShop leaky 1.0, the nag shown 5 s after "Reject all" (synthetic bounds; labels as in Shop.kt). */
    val nagPopup = dialog(
        b(370, 1000, 1511, 2200),
        text("Wait! Get 10% off by allowing personalised offers", b(455, 1085, 1426, 1300)),
        text("Offer ends in 09:59", b(455, 1340, 1100, 1420)),
        text("Share data with our partners to unlock your discount.", b(455, 1440, 1426, 1580)),
        composeButton("Allow & save", b(455, 1620, 1426, 1791), b(800, 1670, 1080, 1741), b(455, 1634, 1426, 1777)),
        text("No, I prefer paying more", b(690, 1800, 1190, 1971), clickable = true),
    )

    private val base = AuditSession(id = "", targetPackage = "com.leakyshop.demo", targetLabel = "LeakyShop", plan = AuditPlan.SILENT)

    fun shown(tree: UiNode, at: Double = 2.1) = ConsentEvent((at * S).toLong(), ConsentKind.SCREEN_APPEARED, tree = tree)

    /**
     * The three plans of a Full audit of a LeakyShop build, in the order the phone runs them, with the consent events the
     * watcher records. [nag] adds the re-shown popup 5.2 s after "Reject all" in Plan B.
     */
    fun leakySessions(nag: Boolean = true, tree: UiNode = leakyDialog): List<AuditSession> = listOf(
        base.copy(id = "c", plan = AuditPlan.SILENT, endedAtNanos = 30 * S, consentEvents = listOf(shown(tree))),
        base.copy(
            id = "b", plan = AuditPlan.REJECT, endedAtNanos = 35 * S,
            consentEvents = listOfNotNull(
                shown(tree), ConsentEvent(6 * S, ConsentKind.SETTINGS, "Manage"), ConsentEvent((8.3 * S).toLong(), ConsentKind.REJECT, "Reject all"),
                ConsentEvent((13.5 * S).toLong(), ConsentKind.REPROMPT, tree = nagPopup).takeIf { nag },
            ),
        ),
        base.copy(
            id = "a", plan = AuditPlan.ACCEPT, endedAtNanos = 30 * S,
            consentEvents = listOf(shown(tree), ConsentEvent((4.9 * S).toLong(), ConsentKind.ACCEPT, "Accept & Continue")),
        ),
    )

    /** The fixed build: Reject on the first screen, one tap each way, no second popup. */
    fun fixedSessions(): List<AuditSession> = listOf(
        base.copy(id = "c", targetPackage = "com.leakyshop.demo.fixed", plan = AuditPlan.SILENT, consentEvents = listOf(shown(fixedDialog))),
        base.copy(
            id = "b", targetPackage = "com.leakyshop.demo.fixed", plan = AuditPlan.REJECT,
            consentEvents = listOf(shown(fixedDialog), ConsentEvent((5.0 * S).toLong(), ConsentKind.REJECT, "Reject")),
        ),
        base.copy(
            id = "a", targetPackage = "com.leakyshop.demo.fixed", plan = AuditPlan.ACCEPT,
            consentEvents = listOf(shown(fixedDialog), ConsentEvent((4.9 * S).toLong(), ConsentKind.ACCEPT, "Accept")),
        ),
    )
}
