package com.dpdpxray.core.web

import com.dpdpxray.core.consent.ConsentLexicon
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSource
import com.dpdpxray.core.model.Bounds
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.RejectOption
import com.dpdpxray.core.model.ScreenAudit
import com.dpdpxray.core.model.UiNode
import com.dpdpxray.core.screen.DeterministicScreenAuditor
import kotlin.math.roundToInt

/** What a website plan does next with the banner it found. */
sealed interface WebStep {
    /** Tap [control]; it records the plan's consent choice [kind] (ACCEPT or REJECT). */
    data class Choose(val control: WebControl, val kind: ConsentKind) : WebStep

    /** Tap [control] to open the banner's settings layer, then read the page again. */
    data class OpenSettings(val control: WebControl) : WebStep

    /** The plan's choice cannot be made from what TraceVeil can read; [reason] says why, in plain words. */
    data class Unavailable(val reason: String) : WebStep
}

/**
 * Turns a banner read from a page into the shapes the rest of TraceVeil already understands: each control's consent role,
 * an accessibility-style tree (so the screen checks, the privacy-link check and the dark-pattern signals read a website
 * banner exactly as they read an app's consent screen), the screen review, and which control a plan taps.
 */
object WebBanners {
    /**
     * The consent role of a control: the consent tool's own element decides when TraceVeil knows it (OneTrust's reject
     * button is a refusal whatever its words); otherwise the shared consent wording list ([ConsentLexicon]). "save" and
     * "close" are neither accept nor refuse.
     */
    fun kindOf(c: WebControl): ConsentKind? = when (c.cmpRole) {
        "accept" -> ConsentKind.ACCEPT
        "reject" -> ConsentKind.REJECT
        "settings" -> ConsentKind.SETTINGS
        "save", "close" -> null
        else -> ConsentLexicon.classifyButton(c.text)
    }

    /** Controls a person can see and tap right now (first layer, or the settings layer once it is open). */
    fun shown(b: WebBanner): List<WebControl> = b.controls.filter { it.visible }

    /**
     * The banner as an accessibility-style tree: its text lines, the controls shown on screen (buttons as Button nodes,
     * links as clickable views, with their size in physical pixels) and its checkboxes. A checkbox that is ticked and
     * cannot be changed (strictly necessary cookies) is plain text, never a pre-ticked choice. Controls of a settings
     * layer that is not shown are left out, so the tree holds what the person sees.
     */
    fun tree(b: WebBanner): UiNode {
        val shown = shown(b)
        val labels = shown.map { it.text }.toSet()
        val dpr = b.devicePixelRatio?.takeIf { it > 0 } ?: 1.0
        val textNodes = b.texts.filter { it !in labels }.map { UiNode(className = "android.widget.TextView", text = it) }
        val controlNodes = shown.map { c ->
            UiNode(
                className = if (c.isButton) "android.widget.Button" else "android.view.View",
                text = c.text, isClickable = true, bounds = c.bounds?.let { scale(it, dpr) },
            )
        }
        val boxNodes = b.checkboxes.map { box ->
            val where = if (box.secondLayer) " (settings layer)" else ""
            if (box.disabled) UiNode(className = "android.widget.TextView", text = box.label + (if (box.checked) " (always on)" else " (cannot be changed)") + where)
            else UiNode(className = "android.widget.CheckBox", text = box.label + where, isCheckable = true, isChecked = box.checked)
        }
        return UiNode(
            className = "web.ConsentBanner",
            contentDescription = b.cmp?.let { "$it consent banner" } ?: "Consent banner",
            children = textNodes + controlNodes + boxNodes,
        )
    }

    /** Checkboxes that are ticked before any choice and can be unticked (a choice made for the person). */
    fun preTicked(b: WebBanner): List<WebCheckbox> = b.checkboxes.filter { it.checked && !it.disabled }

    /**
     * The screen review of a banner. Every field set here is OBSERVED (read from the page): the reject option from the
     * controls shown (Reject on screen = visible; only behind Settings, or only in a hidden settings layer = hidden; only
     * Accept = none), pre-ticked from the changeable checkboxes, and the wording facts from [DeterministicScreenAuditor].
     * A banner inside another site's frame is recorded as a consent screen whose contents are unknown.
     */
    fun screenAudit(b: WebBanner): ScreenAudit {
        if (b.detection == WebBannerDetection.FRAME_ONLY) {
            return ScreenAudit(
                isConsentScreen = true, rejectOption = RejectOption.UNKNOWN, source = AuditSource.DETERMINISTIC,
                summary = "A consent tool's frame" + (b.frameHost?.let { " from $it" } ?: "") + " was found on the page; " +
                    "its text and buttons are inside that frame and could not be read.",
            )
        }
        val det = DeterministicScreenAuditor.audit(tree(b))
        val shownKinds = shown(b).mapNotNull(::kindOf)
        val hiddenReject = b.controls.any { !it.visible && kindOf(it) == ConsentKind.REJECT }
        val reject = when {
            ConsentKind.REJECT in shownKinds -> RejectOption.VISIBLE
            ConsentKind.SETTINGS in shownKinds || hiddenReject -> RejectOption.HIDDEN
            ConsentKind.ACCEPT in shownKinds -> RejectOption.NONE
            else -> RejectOption.UNKNOWN
        }
        val observed = det.observedFields + "preTicked" + (if (reject != RejectOption.UNKNOWN) setOf("rejectOption") else emptySet())
        return det.copy(
            isConsentScreen = true,
            preTicked = preTicked(b).isNotEmpty(),
            rejectOption = reject,
            source = AuditSource.DETERMINISTIC,
            observedFields = observed,
            summary = "Read from the page's " + (b.cmp?.let { "$it " } ?: "") + "consent banner by TraceVeil's in-page script.",
        )
    }

    /**
     * Which control [plan] taps on [b]. Accept: an accept control on screen. Reject: a refuse control on screen; if there is
     * none, the settings control once ([settingsOpened] false), then a refuse control in the layer it opened. A banner
     * inside another site's frame cannot be tapped by TraceVeil. Silent never taps.
     */
    fun stepFor(plan: AuditPlan, b: WebBanner, settingsOpened: Boolean): WebStep {
        if (plan == AuditPlan.SILENT) return WebStep.Unavailable("The no-interaction plan never taps the banner.")
        if (b.detection == WebBannerDetection.FRAME_ONLY) {
            return WebStep.Unavailable(
                "The banner is inside a frame" + (b.frameHost?.let { " from $it" } ?: "") + ", which TraceVeil cannot read or tap. You can tap it yourself.",
            )
        }
        val shown = shown(b)
        fun best(kind: ConsentKind) = shown.filter { kindOf(it) == kind }
            .sortedWith(compareByDescending<WebControl> { it.cmpRole != null }.thenByDescending { it.isButton }.thenBy { it.secondLayer }.thenBy { it.index })
            .firstOrNull()
        return when (plan) {
            AuditPlan.ACCEPT -> best(ConsentKind.ACCEPT)?.let { WebStep.Choose(it, ConsentKind.ACCEPT) }
                ?: WebStep.Unavailable("No Accept button was found on the banner.")
            AuditPlan.REJECT -> best(ConsentKind.REJECT)?.let { WebStep.Choose(it, ConsentKind.REJECT) }
                ?: best(ConsentKind.SETTINGS)?.takeIf { !settingsOpened }?.let { WebStep.OpenSettings(it) }
                ?: WebStep.Unavailable(
                    if (settingsOpened) "No Reject option was found, even after opening the banner's settings."
                    else "No Reject option was found on the banner.",
                )
            AuditPlan.SILENT -> WebStep.Unavailable("The no-interaction plan never taps the banner.")
        }
    }

    private fun scale(b: Bounds, dpr: Double) = Bounds(
        (b.left * dpr).roundToInt(), (b.top * dpr).roundToInt(), (b.right * dpr).roundToInt(), (b.bottom * dpr).roundToInt(),
    )
}
