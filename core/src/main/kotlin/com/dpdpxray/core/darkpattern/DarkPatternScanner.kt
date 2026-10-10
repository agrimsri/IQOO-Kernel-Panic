package com.dpdpxray.core.darkpattern

import com.dpdpxray.core.consent.ConsentLexicon
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.AuditSource
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.EvidenceType
import com.dpdpxray.core.model.RejectOption
import com.dpdpxray.core.model.UiNode
import kotlinx.serialization.Serializable
import java.util.Locale

/*
 * Source (official): Central Consumer Protection Authority, "Guidelines for Prevention and Regulation of Dark Patterns,
 * 2023", Gazette of India Extraordinary Part III Sec. 4, F. No. CCPA-1/1/2023-CCPA-(Reg), New Delhi, 30 November 2023
 * (gazette ID CG-DL-E-30112023-250339). PDF read 11 Oct 2026 from
 * https://ccpa.doca.gov.in/files/The%20Guidelines%20for%20Prevention%20and%20Regulation%20of%20Dark%20Patterns,%202023_1732707717.pdf
 *
 * Annexure 1 opens: "The dark pattern practices and illustrations specified below provide only guidance and shall not be
 * construed as an interpretation of law or as a binding opinion or decision as different facts or conditions may entail
 * different interpretations". TraceVeil therefore reports SIGNALS that resemble a listed pattern, never a finding that a
 * screen "is" one, and none of them enters the score. The quotes in [CcpaPattern.definition] are verbatim (shortened
 * with "…"); docs/DARK_PATTERNS.md carries the full text of each one used.
 */

/** The 13 specified dark patterns of Annexure 1, in its order. [checkable] = can be seen at a consent screen. */
@Serializable
enum class CcpaPattern(val number: Int, val title: String, val checkable: Boolean, val definition: String) {
    FALSE_URGENCY(
        1, "False urgency", true,
        "\"falsely stating or implying the sense of urgency or scarcity so as to mislead a user into making an immediate " +
            "purchase or taking an immediate action, which may lead to a purchase\"",
    ),
    BASKET_SNEAKING(2, "Basket sneaking", false, "A checkout pattern: extra items added at checkout without the user's consent."),
    CONFIRM_SHAMING(
        3, "Confirm shaming", true,
        "\"using a phrase, video, audio or any other means to create a sense of fear or shame or ridicule or guilt in the mind " +
            "of the user so as to nudge the user to act in a certain way…\"",
    ),
    FORCED_ACTION(
        4, "Forced action", true,
        "\"forcing a user into taking an action that would require the user to … share personal information in order to buy " +
            "or subscribe to the product or service originally intended by the user\"",
    ),
    SUBSCRIPTION_TRAP(5, "Subscription trap", false, "A billing pattern: cancelling a paid subscription made impossible, hidden or complex."),
    INTERFACE_INTERFERENCE(
        6, "Interface interference", true,
        "\"a design element that manipulates the user interface in ways that (a) highlights certain specific information; and " +
            "(b) obscures other relevant information relative to the other information; to misdirect a user from taking an action as desired\"",
    ),
    BAIT_AND_SWITCH(7, "Bait and switch", false, "A sales pattern: one outcome advertised, another served."),
    DRIP_PRICING(8, "Drip pricing", false, "A pricing pattern: price elements not revealed upfront."),
    DISGUISED_ADVERTISEMENT(9, "Disguised advertisement", false, "An advertising pattern: ads posed as other content."),
    NAGGING(
        10, "Nagging", true,
        "\"a dark pattern practice due to which a user is disrupted and annoyed by repeated and persistent interactions, in the " +
            "form of requests, information, options, or interruptions, to effectuate a transaction and make some commercial gains, " +
            "unless specifically permitted by the user\"",
    ),
    TRICK_QUESTION(
        11, "Trick question", true,
        "\"the deliberate use of confusing or vague language like confusing wording, double negatives, or other similar tricks, " +
            "in order to misguide or misdirect a user from taking desired action…\"",
    ),
    SAAS_BILLING(12, "SaaS billing", false, "A billing pattern: recurring payments collected surreptitiously."),
    ROGUE_MALWARES(13, "Rogue malwares", false, "A malware pattern: ransomware or scareware posing as protection.");

    /** Short citation used on every signal. */
    val source: String get() = "CCPA India 2023 Annexure 1 #$number"

    companion object {
        /** The six patterns TraceVeil can check on a consent screen, in Annexure order. */
        val CHECKABLE: List<CcpaPattern> = entries.filter { it.checkable }
    }
}

/**
 * One dark-pattern signal: the consent screen or the events around it resemble [pattern] as described in Annexure 1.
 * OBSERVED = read from the screen tree or the recorded taps by fixed rules; INFERRED = the on-device AI's reading only.
 * A signal for review, never a legal finding, and never part of the score.
 */
@Serializable
data class DarkPatternSignal(
    val pattern: CcpaPattern,
    val evidence: List<String>,
    val evidenceType: EvidenceType,
    val plans: List<AuditPlan> = emptyList(),
    val source: String = pattern.source,
)

/**
 * Deterministic dark-pattern checks at the consent moment, read from saved sessions only (consent-screen trees, the
 * screen shown again after a choice, and the recorded taps). Pure: it never changes sessions, findings or the score.
 */
object DarkPatternScanner {
    /** A re-shown screen this soon after the tap can still be the closing dialog, so it is not counted as nagging. */
    private const val MIN_REPROMPT_GAP_NANOS = 1_000_000_000L

    /** A refuse path under half the area of the accept control counts as much less prominent. */
    private const val AREA_RATIO = 0.5

    /** Longer clickable text is a container (a whole card), not a single option. */
    private const val MAX_CONTROL_LABEL = 80

    private val SCREEN_KINDS = setOf(ConsentKind.SCREEN_APPEARED, ConsentKind.MANUAL_MARK, ConsentKind.REPROMPT)

    /** How many consent-screen trees were available to check (0 = nothing could be checked). */
    fun screensChecked(sessions: List<AuditSession>): Int = sessions.sumOf { s -> screens(s).size }

    fun scan(sessions: List<AuditSession>): List<DarkPatternSignal> {
        val out = Collector()
        for (s in sessions) {
            for (screen in screens(s)) {
                val controls = controls(screen.tree)
                interfaceInterference(controls).forEach { out.add(CcpaPattern.INTERFACE_INTERFERENCE, it, s.plan) }
                controls.filter { DarkPatternCues.isShaming(it.label) }
                    .forEach { out.add(CcpaPattern.CONFIRM_SHAMING, "The decline option reads ${q(it.label)}.", s.plan) }
                forcedAction(controls)?.let { out.add(CcpaPattern.FORCED_ACTION, it, s.plan) }
                val labels = screen.tree.flatten().map { it.label }.filter { it.isNotBlank() }.distinct()
                labels.filter { DarkPatternCues.isUrgency(it) }.forEach { out.add(CcpaPattern.FALSE_URGENCY, "Consent-screen text: ${q(it)}.", s.plan) }
                labels.filter { DarkPatternCues.isDoubleNegative(it) }.forEach { out.add(CcpaPattern.TRICK_QUESTION, "Double-negative wording: ${q(it)}.", s.plan) }
            }
            nagging(s)?.let { out.add(CcpaPattern.NAGGING, it, s.plan) }
        }
        tapCost(sessions)?.let { (line, plans) -> plans.forEach { out.add(CcpaPattern.INTERFACE_INTERFERENCE, line, it) } }
        sessions.forEach { s -> inferred(s).forEach { (p, line) -> out.addInferred(p, line, s.plan) } }
        return out.signals()
    }

    // --- Screens and controls ----------------------------------------------------------------------------------------------

    private data class Screen(val event: ConsentEvent, val tree: UiNode)

    private fun screens(s: AuditSession): List<Screen> = s.consentEvents
        .filter { it.kind in SCREEN_KINDS }
        .mapNotNull { e -> e.tree?.let { Screen(e, it) } }
        .filter { ConsentLexicon.isConsentScreen(it.tree.flatten().map { n -> n.label }) }

    private enum class Role { ACCEPT, REFUSE, SETTINGS, CLOSE }

    private data class Control(val node: UiNode, val label: String, val role: Role) {
        /** Shown as a button: a Button class on the node or (Compose) a Button-role child. Otherwise plain text. */
        val isButton: Boolean = node.flatten().any { it.className.endsWith("Button") }
        val area: Long? = node.bounds?.let { b -> (b.right - b.left).toLong() * (b.bottom - b.top) }?.takeIf { it > 0 }
        val size: String? = node.bounds?.let { b -> "${b.right - b.left}×${b.bottom - b.top} px" }
    }

    private fun controls(tree: UiNode): List<Control> = tree.flatten()
        .filter { it.isClickable }
        .mapNotNull { n ->
            val label = buttonLabel(n).trim()
            if (label.isEmpty() || label.length > MAX_CONTROL_LABEL) return@mapNotNull null
            role(label)?.let { Control(n, label, it) }
        }

    /** Same rule as the screen auditor: the node's own label, else (Compose) its descendants' text. */
    private fun buttonLabel(n: UiNode): String =
        n.label.ifBlank { n.flatten().drop(1).map { it.label }.filter { it.isNotBlank() }.joinToString(" ") }

    private fun role(label: String): Role? {
        if (DarkPatternCues.isShaming(label)) return Role.REFUSE
        if (DarkPatternCues.isClose(label)) return Role.CLOSE
        return when (ConsentLexicon.classifyButton(label)) {
            ConsentKind.REJECT -> Role.REFUSE
            ConsentKind.SETTINGS -> Role.SETTINGS
            ConsentKind.ACCEPT -> Role.ACCEPT
            else -> if (DarkPatternCues.isPlainDecline(label)) Role.REFUSE else null
        }
    }

    // --- Rules ---------------------------------------------------------------------------------------------------------------

    /** #6: the way to refuse is plain text next to an accept button, or much smaller than it. */
    private fun interfaceInterference(controls: List<Control>): List<String> {
        val accept = controls.filter { it.role == Role.ACCEPT }.maxByOrNull { it.area ?: 0L } ?: return emptyList()
        val path = controls.filter { it.role == Role.REFUSE }.ifEmpty { controls.filter { it.role == Role.SETTINGS } }
        // The most prominent way to refuse is compared, so a large Reject elsewhere on the screen prevents a signal.
        val refuse = path.maxWithOrNull(compareBy<Control>({ it.isButton }, { it.area ?: 0L })) ?: return emptyList()
        val lines = mutableListOf<String>()
        if (accept.isButton && !refuse.isButton) {
            lines += "${q(accept.label)} is shown as a button; the way to refuse (${q(refuse.label)}) is plain text."
        }
        val a = accept.area
        val r = refuse.area
        if (a != null && r != null && r < AREA_RATIO * a) {
            val ratio = String.format(Locale.US, "%.1f", a.toDouble() / r)
            lines += "${q(accept.label)} covers $ratio× the screen area of ${q(refuse.label)} (${accept.size} vs ${refuse.size})."
        }
        return lines
    }

    /** #4: only an accept option, with no refuse, manage or close control on the consent screen. */
    private fun forcedAction(controls: List<Control>): String? {
        val accept = controls.firstOrNull { it.role == Role.ACCEPT } ?: return null
        if (controls.any { it.role != Role.ACCEPT }) return null
        return "Only ${q(accept.label)} was offered: no Reject, Manage or close option was found on the consent screen."
    }

    /** #10: the consent screen came back, asking again, after the plan's recorded Reject. */
    private fun nagging(s: AuditSession): String? {
        val choice = s.consentChoice?.takeIf { it.kind == ConsentKind.REJECT } ?: return null
        val again = s.consentEvents
            .filter { it.kind == ConsentKind.REPROMPT && it.tNanos - choice.tNanos >= MIN_REPROMPT_GAP_NANOS }
            .sortedBy { it.tNanos }
            .firstOrNull { e ->
                val tree = e.tree ?: return@firstOrNull false
                ConsentLexicon.isConsentScreen(tree.flatten().map { it.label }) && controls(tree).any { it.role == Role.ACCEPT }
            } ?: return null
        val secs = String.format(Locale.US, "%.1f", (again.tNanos - choice.tNanos) / 1e9)
        return "The consent popup came back $secs s after the recorded Reject: ${q(title(again.tree!!))}."
    }

    /** #6 (tap cost): refusing took more recorded taps than accepting. */
    private fun tapCost(sessions: List<AuditSession>): Pair<String, List<AuditPlan>>? {
        val refusal = sessions.firstNotNullOfOrNull { s -> taps(s, ConsentKind.REJECT)?.let { s to it } } ?: return null
        val accepted = sessions.firstNotNullOfOrNull { s -> taps(s, ConsentKind.ACCEPT)?.let { s to it } }
        val acceptTaps: List<String> = accepted?.second
            ?: sessions.asSequence().flatMap { screens(it) }.flatMap { controls(it.tree) }.firstOrNull { it.role == Role.ACCEPT }?.let { listOf(it.label) }
            ?: return null
        val refuseTaps = refusal.second
        if (refuseTaps.size <= acceptTaps.size) return null
        val line = "Refusing took ${refuseTaps.size} taps (${refuseTaps.joinToString(" → ") { q(it) }}); " +
            "accepting took ${acceptTaps.size} (${acceptTaps.joinToString(" → ") { q(it) }})."
        return line to listOfNotNull(refusal.first.plan, accepted?.first?.plan).distinct()
    }

    /** The recorded taps up to and including this plan's first choice, when that choice is [kind]. */
    private fun taps(s: AuditSession, kind: ConsentKind): List<String>? {
        val choice = s.consentChoice?.takeIf { it.kind == kind } ?: return null
        val steps = s.consentEvents.filter { it.kind == ConsentKind.SETTINGS && it.tNanos < choice.tNanos }.sortedBy { it.tNanos }
        return (steps + choice).map { it.nodeText?.takeIf { t -> t.isNotBlank() } ?: it.kind.name.lowercase() }
    }

    /** The on-device AI's own words, mapped to a pattern when they name one. Added only where no rule observed it. */
    private fun inferred(s: AuditSession): List<Pair<CcpaPattern, String>> {
        val audit = s.screenAudit ?: return emptyList()
        if (audit.source == AuditSource.DETERMINISTIC) return emptyList()
        val out = audit.darkPatterns.mapNotNull { said ->
            DarkPatternCues.aiPattern(said)?.let { it to "On-device AI reading of the consent screen: ${q(said)}. Not confirmed from the screen tree." }
        }
        val forced = if (audit.rejectOption == RejectOption.NONE && "rejectOption" !in audit.observedFields) {
            listOf(CcpaPattern.FORCED_ACTION to "On-device AI reading: no way to refuse was seen on the consent screen. Not confirmed from the screen tree.")
        } else emptyList()
        return out + forced
    }

    // --- Helpers -------------------------------------------------------------------------------------------------------------

    /** The screen's first text that is not one of its options (a dialog's title). */
    private fun title(tree: UiNode): String {
        val options = controls(tree).map { it.label }.toSet()
        val labels = tree.flatten().filter { !it.isClickable }.map { it.label.trim() }.filter { it.isNotBlank() && it !in options }
        return short(labels.firstOrNull() ?: "")
    }

    private fun q(text: String) = "\"${short(text)}\""

    private fun short(text: String): String {
        val t = text.replace(Regex("\\s+"), " ").trim()
        return if (t.length <= 90) t else t.take(89).trimEnd() + "…"
    }

    /** One signal per pattern: observed evidence lines in first-seen order, plans in session order; inferred only if no observed. */
    private class Collector {
        private val observed = linkedMapOf<CcpaPattern, Pair<LinkedHashSet<String>, LinkedHashSet<AuditPlan>>>()
        private val inferred = linkedMapOf<CcpaPattern, Pair<LinkedHashSet<String>, LinkedHashSet<AuditPlan>>>()

        fun add(p: CcpaPattern, line: String, plan: AuditPlan) = put(observed, p, line, plan)
        fun addInferred(p: CcpaPattern, line: String, plan: AuditPlan) = put(inferred, p, line, plan)

        private fun put(m: MutableMap<CcpaPattern, Pair<LinkedHashSet<String>, LinkedHashSet<AuditPlan>>>, p: CcpaPattern, line: String, plan: AuditPlan) {
            val (lines, plans) = m.getOrPut(p) { linkedSetOf<String>() to linkedSetOf() }
            lines += line
            plans += plan
        }

        fun signals(): List<DarkPatternSignal> {
            val obs = observed.map { (p, v) -> DarkPatternSignal(p, v.first.toList(), EvidenceType.OBSERVED, v.second.toList()) }
            val inf = inferred.filterKeys { it !in observed }.map { (p, v) -> DarkPatternSignal(p, v.first.toList(), EvidenceType.INFERRED, v.second.toList()) }
            return (obs + inf).sortedBy { it.pattern.number }
        }
    }
}
