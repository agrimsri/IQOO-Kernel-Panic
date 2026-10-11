package com.dpdpxray.core.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * A background watch: one long capture of the audited app while it runs in the background (periodic sync, polls), the
 * person using the phone as usual or locking it. TraceVeil does not open, restart or tap the app for it, so no consent
 * screen is driven: a watch shows what the app sends on its own, never how its consent popup behaves. It is saved on its
 * one plan ([AuditSession.watch], an [AuditPlan.SILENT] plan) and judged by the same rules and score as any other plan.
 */
@Serializable
data class BackgroundWatch(
    /** The length the person chose, in milliseconds. */
    val plannedMs: Long,
    /** How long TraceVeil actually watched; shorter than [plannedMs] when the watch was stopped early. */
    val watchedMs: Long,
    /** The person ended the watch early (Stop in the notification or on screen); what was seen until then is kept. Written only when true. */
    @OptIn(ExperimentalSerializationApi::class) @EncodeDefault(EncodeDefault.Mode.NEVER)
    val stoppedEarly: Boolean = false,
    /**
     * When the app was on screen, or may have been (the screen could not be read), after it was first confirmed off screen;
     * on the [NetEvent.tNanos] clock. Lookups inside these spans are not counted as background. Written only when not empty.
     */
    @OptIn(ExperimentalSerializationApi::class) @EncodeDefault(EncodeDefault.Mode.NEVER)
    val onScreen: List<WatchSpan> = emptyList(),
) {
    /** The chosen length in whole minutes (5, 10, 30). */
    val plannedMinutes: Int get() = ((plannedMs + 30_000) / 60_000).toInt()

    /** Whether a moment of the watch lies outside every on-screen span. */
    fun offScreenAt(tNanos: Long): Boolean = onScreen.none { tNanos >= it.fromNanos && tNanos < it.toNanos }
}

/** A stretch of time on the [NetEvent.tNanos] clock, start included, end excluded. */
@Serializable
data class WatchSpan(val fromNanos: Long, val toNanos: Long)

/**
 * What every surface says about a background watch, so the app, the reports and both command-line tools word it the same
 * way. Pure and deterministic.
 */
object BackgroundWatches {
    /** Watch lengths the check sheet offers, in minutes. */
    val MINUTES = listOf(5, 10, 30)

    /** The length the result's one-tap watch and the sheet start with. */
    const val DEFAULT_MINUTES = 10

    /** The run's name wherever a plan is named, in place of "Plan C · No interaction". */
    const val LABEL = "Background watch"

    /** Method line for reports: what a watch can and cannot see. */
    const val LIMIT = "A background watch records the lookups and connections the app makes while it is watched. TraceVeil " +
        "does not open, restart or tap the app, so its consent screen is not tested, and connections the app opened before " +
        "the watch began are not seen."

    /** The watch of [sessions], or null when none of them is one. */
    fun of(sessions: List<AuditSession>): BackgroundWatch? = sessions.firstNotNullOfOrNull { it.watch }

    /** Every plan is a watch (a watch is always a check of its own). */
    fun isWatch(sessions: List<AuditSession>): Boolean = sessions.isNotEmpty() && sessions.all { it.watch != null }

    /** [s]'s name: "Background watch" for a watch, else its plan's label. */
    fun label(s: AuditSession): String = if (s.watch != null) LABEL else s.plan.label

    /** Where a moment sits in [s]: "into the background watch" for a watch, else "after launch". */
    fun since(s: AuditSession): String = if (s.watch != null) "into the background watch" else "after launch"

    /**
     * The length to offer next: [DEFAULT_MINUTES] after a check that was not a watch, the next longer option after a watch,
     * null after the longest one (watching longer is not offered again).
     */
    fun nextMinutes(sessions: List<AuditSession>): Int? {
        val w = of(sessions) ?: return DEFAULT_MINUTES
        return MINUTES.firstOrNull { it > w.plannedMinutes }
    }

    /** True when [t] lies in the part of [s] confirmed as background: after the app left the screen, outside on-screen spans. */
    fun inBackground(s: AuditSession, t: Long): Boolean {
        val bg = s.backgroundStartedNanos ?: return false
        return t >= bg && (s.watch?.offScreenAt(t) ?: true)
    }

    fun minutes(m: Int): String = if (m == 1) "1 minute" else "$m minutes"

    fun minutesHi(m: Int): String = "$m मिनट"

    /** "10 minutes", or "4 min 12 s of a planned 10 minutes (stopped early)". */
    fun length(w: BackgroundWatch): String =
        if (!w.stoppedEarly) minutes(w.plannedMinutes) else "${duration(w.watchedMs)} of a planned ${minutes(w.plannedMinutes)} (stopped early)"

    fun lengthHi(w: BackgroundWatch): String =
        if (!w.stoppedEarly) minutesHi(w.plannedMinutes) else "${durationHi(w.watchedMs)} (तय ${minutesHi(w.plannedMinutes)} में से, पहले रोका गया)"

    /** The result's and reports' plain statement of what a watch checked: English and Hindi. */
    fun whatWasChecked(app: String, w: BackgroundWatch): Pair<String, String> =
        "What was checked: what $app sends while it runs in the background, watched for ${length(w)}; its consent screen was not reached." to
            "क्या जाँचा गया: $app बैकग्राउंड में चलते हुए क्या भेजता है, ${lengthHi(w)} तक देखा गया; इसकी सहमति स्क्रीन तक नहीं पहुँचा गया।"

    /** "4 min 12 s", "45 s", "10 min". */
    fun duration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val m = total / 60
        val s = total % 60
        return when {
            m == 0L -> "$s s"
            s == 0L -> "$m min"
            else -> "$m min $s s"
        }
    }

    fun durationHi(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val m = total / 60
        val s = total % 60
        return when {
            m == 0L -> "$s सेकंड"
            s == 0L -> "$m मिनट"
            else -> "$m मिनट $s सेकंड"
        }
    }
}
