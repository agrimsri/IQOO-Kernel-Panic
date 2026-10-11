package com.dpdpxray.core.rules

import com.dpdpxray.core.model.AuditResult
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.BackgroundWatches
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.StartMode
import java.util.Locale

/**
 * Why an audit captured too few lookups to judge the app (check V0), from what the phone recorded around each plan
 * ([AuditSession.conditions], capture health, Private DNS, the installed manifest). It turns a bare "Inconclusive" into
 * causes a person can act on, each with its evidence and the one action most likely to help.
 *
 * It never adds a score or a finding of its own: the result stays inconclusive. A cause is named only from something
 * recorded; when nothing recorded explains the gap, it says so ([Cause.UNEXPLAINED]) instead of guessing. Pure and
 * deterministic, so the app, the reports and both CLIs word the same causes the same way.
 *
 * An app that works in the background (periodic sync, polls) often goes online only every few minutes, and its screens
 * may be gated: for an app left running, one that stayed quiet, a short watch or a sign-in or passcode gate, the action
 * offered is a background watch ([Action.WATCH_LONGER]). A watch that itself saw almost nothing is named as what it is,
 * an observation of the watch ([Cause.QUIET_WATCH]); a locked phone or an app left running is expected there.
 */
object VisibilityDiagnosis {
    /** Ordered by what has to be fixed first: nothing else helps while another VPN holds the slot. */
    enum class Cause {
        OTHER_VPN, CAPTURE_STOPPED, NO_NETWORK, PRIVATE_DNS, PHONE_LOCKED, APP_CRASHED, NO_INTERNET_PERMISSION,
        ALREADY_RUNNING, OWN_DNS, APP_QUIET, WINDOW_TOO_SHORT, UNEXPLAINED,

        /** A sign-in, passcode or similar screen stood before the consent screen and was not passed. */
        GATED,

        /** A background watch saw the app make almost no connections in its whole length. */
        QUIET_WATCH,
    }

    /** What one tap on the result offers for a cause. NONE: nothing on the phone would change the outcome. */
    enum class Action {
        CHECK_AGAIN_WITH_RESTART, TURN_OFF_PRIVATE_DNS, TURN_OFF_OTHER_VPN, CONNECT_TO_INTERNET, CHECK_AGAIN,
        RUN_WITH_INTERNET_CUT_OFF, NONE,

        /** Watch the app in the background for longer (see [BackgroundWatches.nextMinutes]); no launch, restart or tap. */
        WATCH_LONGER,
    }

    /** [evidence]: what was recorded, per plan. [sentence]: the plain-language cause, for reports and the result. */
    data class Item(val cause: Cause, val evidence: List<String>, val sentence: String, val action: Action)

    /** Less captured time than this across the audit is too short a watch to expect launch-time lookups to settle. */
    const val SHORT_WATCH_NANOS = 20_000_000_000L

    /** The diagnosis behind [result]'s V0 finding; empty when the result has none (enough was captured). */
    fun forResult(result: AuditResult, sessions: List<AuditSession>, connections: ConnectionSource = ConnectionSource.NONE): List<Item> =
        if (result.findings.any { it.checkId == CheckId.V0 }) diagnose(sessions, connections) else emptyList()

    /** The first action that can help, or [Action.NONE]. */
    fun primaryAction(items: List<Item>): Action = items.firstOrNull { it.action != Action.NONE }?.action ?: Action.NONE

    /**
     * The causes for an audit whose lookups were below the visibility threshold, most important first. Never empty: when
     * nothing recorded explains the gap, the single item says that the cause is unknown. [connections] adds what a
     * capture deeper than DNS saw (see [ConnectionSource]); without it, the app's own network use comes only from
     * [com.dpdpxray.core.model.PlanConditions.appTraffic].
     */
    fun diagnose(sessions: List<AuditSession>, connections: ConnectionSource = ConnectionSource.NONE): List<Item> {
        val out = mutableListOf<Item>()
        val seen = sessions.associateWith { connections.connections(it) }
        fun lookups(s: AuditSession) = s.netEvents.count { !it.fromConnection }
        fun add(cause: Cause, evidence: List<String>, sentence: String, action: Action) {
            if (evidence.isNotEmpty()) out += Item(cause, evidence.distinct(), sentence, action)
        }

        add(Cause.OTHER_VPN, sessions.filter { it.conditions?.otherVpnAtEnd == true }.map { "${it.plan.label}: another app's VPN was on when the plan ended." },
            "Another VPN was on during the check. Android runs one VPN at a time, so TraceVeil could not see the app's lookups.",
            Action.TURN_OFF_OTHER_VPN)

        add(Cause.CAPTURE_STOPPED, sessions.flatMap { s ->
            s.captureHealth?.notes.orEmpty().filter { n -> STOP_NOTES.any { n.startsWith(it) } }.map { "${s.plan.label}: $it" }
        }, "TraceVeil's capture stopped during the check (for example the VPN was turned off), so later lookups were not seen.",
            Action.CHECK_AGAIN)

        add(Cause.NO_NETWORK, sessions.flatMap { s ->
            val c = s.conditions
            val h = s.captureHealth
            buildList {
                if (c?.networkAtStart == false || c?.networkAtEnd == false) {
                    add("${s.plan.label}: the phone had no working internet connection at the " +
                        listOfNotNull("start".takeIf { c.networkAtStart == false }, "end".takeIf { c.networkAtEnd == false }).joinToString(" and ") + " of the plan.")
                }
                if (h?.dnsRelayFailures?.let { it > 0 } == true && h.dnsResponsesRelayed == 0L) {
                    add("${s.plan.label}: all ${h.dnsRelayFailures} lookups failed upstream (no answer from any resolver).")
                }
            }
        }, "The phone had no working internet connection during the check, so the app could not reach anything.",
            Action.CONNECT_TO_INTERNET)

        add(Cause.PRIVATE_DNS, sessions.flatMap { s ->
            val c = s.conditions
            buildList {
                when {
                    c?.privateDnsAtStart == false && c.privateDnsAtEnd == true -> add("${s.plan.label}: Private DNS was turned on during the plan.")
                    c?.privateDnsAtStart == true && c.privateDnsAtEnd == false -> add("${s.plan.label}: Private DNS was on when the plan started and turned off during it.")
                    s.privateDnsActive == true || c?.privateDnsAtStart == true || c?.privateDnsAtEnd == true -> add("${s.plan.label}: Private DNS was on.")
                }
                s.captureHealth?.hiddenDnsPackets?.takeIf { it > 0 }?.let { add("${s.plan.label}: $it packets used DNS over TLS or TCP, which TraceVeil cannot read.") }
            }
        }, "Private DNS was on, so Android can send the app's lookups encrypted, past TraceVeil. Turn it off in Settings and check again.",
            Action.TURN_OFF_PRIVATE_DNS)

        // A watch runs while the phone is used as usual or locked: locking is expected there, never a problem.
        add(Cause.PHONE_LOCKED, sessions.filter { it.watch == null && it.conditions?.phoneLocked == true }.map { "${it.plan.label}: the phone was locked or its screen was off during the plan." },
            "The phone was locked or its screen went off during the check, so the app may have paused. Keep the phone unlocked and check again.",
            Action.CHECK_AGAIN)

        add(Cause.APP_CRASHED, sessions.filter { it.conditions?.appCrashed == true }.map { "${it.plan.label}: Android showed that the app stopped or was not responding." },
            "The app stopped with an error during the check (Android showed its \"keeps stopping\" or \"isn't responding\" message), so there was little to see.",
            Action.CHECK_AGAIN)

        val noInternet = sessions.filter { it.manifestFacts?.requestsInternet == false }
        add(Cause.NO_INTERNET_PERMISSION, noInternet.map { "${it.plan.label}: the installed app does not request Android's INTERNET permission." }.take(1),
            "The app does not ask Android for internet access, so it cannot go online by itself. There was nothing for TraceVeil to see.",
            Action.NONE)

        // A watch leaves the app running on purpose: that is the point of it, not a cause.
        val notRestarted = sessions.filter { it.watch == null && mayHaveBeenRunning(it) }
        if (notRestarted.isNotEmpty()) {
            val certain = notRestarted.any { it.conditions?.runningBeforeStart == true }
            // Left running on purpose: a restart would have switched off something the app runs (an accessibility service...).
            val chosen = notRestarted.all { leftRunning(it) }
            add(Cause.ALREADY_RUNNING, notRestarted.map { s ->
                val skipped = seen[s]?.count { it.noLookup } ?: 0
                notRestartedLine(s) + if (skipped > 0) " It opened ${count(skipped, "connection")} without a lookup TraceVeil saw." else ""
            },
                when {
                    chosen -> "TraceVeil left the app running, so as not to switch off something it runs (see the check's notes). " +
                        (if (certain) "It was already running, so it could reuse addresses it had looked up before the check. "
                        else "If it was already running, it may have reused addresses it looked up before the check. ") +
                        "Restarting is your choice: your data stays, but you would switch that on again afterwards."
                    certain -> "The app was already running and TraceVeil could not restart it, so it could reuse addresses it had looked up before the check. Check again with a restart: your data stays."
                    else -> "TraceVeil did not restart the app first. If it was already running, it may have reused addresses it looked up before the check. Check again with a restart: your data stays."
                } + " " + WATCH_NEEDS_NO_RESTART,
                Action.WATCH_LONGER)
        }

        // A sign-in or passcode gate: the check could not get further into the app; a watch needs no sign-in.
        val gated = sessions.filter { s -> s.watch == null && s.conditions?.blocker?.let { it.kind != "PERMISSION" && !it.consentReached } == true }
        add(Cause.GATED, gated.map(::gateLine),
            "A sign-in, passcode or similar screen stood before the app's consent screen and was not passed, so the check could not " +
                "get further into the app. Such apps may go online only in the background, every few minutes, which a background " +
                "watch can see without anyone signing in.",
            Action.WATCH_LONGER)

        // Only plans that started fresh can say anything about the app's own DNS: a kept process may simply reuse old lookups.
        val fresh = sessions.filter { it.conditions?.startMode == StartMode.RESTARTED || it.conditions?.startMode == StartMode.RESET }
        val connected = fresh.filter { lookups(it) < RulesEngine.MIN_EVENTS && !seen[it].isNullOrEmpty() }
        val busy = fresh.filter { lookups(it) < RulesEngine.MIN_EVENTS && it.conditions?.appTraffic?.active == true && it !in connected }
        add(Cause.OWN_DNS,
            connected.map { s ->
                val c = seen[s].orEmpty()
                "${s.plan.label}: the app opened ${count(c.size, "connection")} (${c.count { it.noLookup }} to addresses it never looked up), but only ${lookups(s)} lookups."
            } + busy.map { "${it.plan.label}: ${trafficLine(it)}, but only ${lookups(it)} lookups." },
            if (connected.isNotEmpty()) "The app connected to servers without a lookup TraceVeil could see, so it uses its own DNS (for example DNS over HTTPS) " +
                "or fixed server addresses. The connections themselves are listed with this check."
            else "The app used the network but made almost no lookups TraceVeil could see, so it may use its own encrypted DNS (DNS over HTTPS) " +
                "or fixed server addresses. Running it with the internet cut off lists the addresses it tries.",
            Action.RUN_WITH_INTERNET_CUT_OFF)

        val explained = out.isNotEmpty()
        val quiet = fresh.filter { seen[it]?.isEmpty() == true || (seen[it] == null && it.conditions?.appTraffic?.active == false) }
        if (!explained) add(Cause.APP_QUIET, quiet.map { s -> "${s.plan.label}: restarted, and " + (if (seen[s] != null) "the app opened no connections" else trafficLine(s)) + "." },
            "TraceVeil restarted the app and it did not use the network during the check, so there was nothing to judge. " +
                "An app that works in the background may go online only every few minutes.",
            Action.WATCH_LONGER)

        // A watch stopped early is named by its own cause below, with its length.
        val watched = sessions.sumOf(::watchedNanos)
        if (sessions.isNotEmpty() && !BackgroundWatches.isWatch(sessions) && watched in 1 until SHORT_WATCH_NANOS) {
            add(Cause.WINDOW_TOO_SHORT, listOf("Watched for ${secs(watched)} s in all."),
                "TraceVeil watched for only ${secs(watched)} s, so lookups the app makes a little later were not seen. A normal Quick check watches longer.",
                Action.WATCH_LONGER)
        }

        // A background watch that saw almost nothing: an observation of the watch, worded as one.
        val watches = sessions.filter { it.watch != null }
        if (watches.isNotEmpty()) {
            val busy = watches.filter { s -> (connectionCount(s, seen[s]) ?: 0) >= RulesEngine.MIN_EVENTS }
            add(Cause.QUIET_WATCH, watches.map { watchLine(it, seen[it]) }, quietWatchSentence(watches, seen, busy.isNotEmpty()),
                if (busy.isEmpty() && BackgroundWatches.nextMinutes(watches) != null) Action.WATCH_LONGER else Action.NONE)
        }

        if (out.isEmpty()) {
            val restarted = fresh.isNotEmpty()
            add(Cause.UNEXPLAINED, sessions.map { "${it.plan.label}: ${lookups(it)} lookups; " + (if (it in fresh) "started fresh; " else "") + trafficLine(it) + "." },
                (if (restarted) "TraceVeil restarted the app and nothing it can see blocked the capture, yet it saw almost no lookups. "
                else "Nothing TraceVeil recorded explains why it saw almost no lookups. ") +
                    "The app may not go online when it opens, or may use its own DNS over HTTPS or fixed addresses; whether it used the network is unknown on this phone.",
                Action.RUN_WITH_INTERNET_CUT_OFF)
        }
        return out
    }

    /** Added to the "already running" cause: the action offered there leaves the app as it is. */
    private const val WATCH_NEEDS_NO_RESTART = "Watching it in the background for longer needs no restart."

    /** A short phrase for a cause, for one-line outputs such as the command-line tools ("likely cause: ..."). */
    fun phrase(c: Cause): String = when (c) {
        Cause.OTHER_VPN -> "another VPN was on"
        Cause.CAPTURE_STOPPED -> "the capture stopped during the check"
        Cause.NO_NETWORK -> "the phone had no working internet"
        Cause.PRIVATE_DNS -> "Private DNS was on"
        Cause.PHONE_LOCKED -> "the phone was locked during the check"
        Cause.APP_CRASHED -> "the app stopped with an error"
        Cause.NO_INTERNET_PERMISSION -> "the app does not ask for internet access"
        Cause.ALREADY_RUNNING -> "the app was already running (not restarted)"
        Cause.OWN_DNS -> "the app may use its own DNS or fixed addresses"
        Cause.APP_QUIET -> "the app did not use the network"
        Cause.WINDOW_TOO_SHORT -> "the watch was too short"
        Cause.UNEXPLAINED -> "not known from what was recorded"
        Cause.GATED -> "a sign-in or passcode screen was in the way"
        Cause.QUIET_WATCH -> "the app made almost no connections while it was watched in the background"
    }

    /** What to do about an [Action], in a few words, for one-line outputs. */
    fun hint(a: Action): String = when (a) {
        Action.CHECK_AGAIN_WITH_RESTART -> "check again with a restart (the app keeps its data)"
        Action.TURN_OFF_PRIVATE_DNS -> "turn Private DNS off, then check again"
        Action.TURN_OFF_OTHER_VPN -> "turn the other VPN off, then check again"
        Action.CONNECT_TO_INTERNET -> "connect to the internet, then check again"
        Action.CHECK_AGAIN -> "check again"
        Action.RUN_WITH_INTERNET_CUT_OFF -> "run it with the internet cut off to list the addresses it tries"
        Action.NONE -> "nothing on the phone would change this result"
        Action.WATCH_LONGER -> "watch it in the background for ${BackgroundWatches.DEFAULT_MINUTES} minutes or longer (TraceVeil does not open, restart or tap it; its data stays)"
    }

    /** [hint] with the watch length to offer next after [sessions] (10 minutes, or 30 after a 10-minute watch). */
    fun hint(a: Action, sessions: List<AuditSession>): String {
        val next = BackgroundWatches.nextMinutes(sessions)
        return if (a == Action.WATCH_LONGER && next != null && next != BackgroundWatches.DEFAULT_MINUTES)
            "watch it in the background for $next minutes (TraceVeil does not open, restart or tap it; its data stays)"
        else hint(a)
    }

    /** One sentence per cause, for the V0 explanation in reports. */
    fun explanation(items: List<Item>): String = items.joinToString(" ") { it.sentence }

    /** Plans that were opened without a recorded restart, and may therefore have found the app already running. */
    private fun mayHaveBeenRunning(s: AuditSession): Boolean {
        val c = s.conditions ?: return s.freshState != true // older audit: kept data and no restart step existed
        return c.startMode == StartMode.NOT_RESTARTED && c.runningBeforeStart != false
    }

    private fun notRestartedLine(s: AuditSession): String {
        val c = s.conditions ?: return "${s.plan.label}: this check did not restart the app first (it was saved before TraceVeil did that), so it may have been running already."
        val why = c.restartProblem?.let { " ($it)" } ?: ""
        val running = when (c.runningBeforeStart) {
            true -> "it was already running"
            else -> "whether it was already running is unknown"
        }
        val open = c.appTraffic?.openConnections?.takeIf { it > 0 }?.let { "; it had $it open connections" } ?: ""
        val did = if (leftRunning(s)) "did not" else "could not"
        return "${s.plan.label}: TraceVeil $did restart the app first$why; $running$open."
    }

    /** Not restarted on purpose (by default or the person's choice) because a restart would switch something off. */
    private fun leftRunning(s: AuditSession): Boolean = s.conditions?.restartChoice.let {
        it == com.dpdpxray.core.model.RestartChoice.SKIPPED_BY_DEFAULT || it == com.dpdpxray.core.model.RestartChoice.SKIPPED_BY_PERSON
    }

    private fun trafficLine(s: AuditSession): String {
        val t = s.conditions?.appTraffic ?: return "the app's own network use was not readable"
        val parts = listOfNotNull(
            t.openConnections?.let { "$it open connections" },
            t.rxBytes?.let { "${it} bytes received" },
            t.txBytes?.let { "${it} bytes sent" },
        )
        return if (parts.isEmpty()) "the app's own network use was not readable" else "the app had " + parts.joinToString(", ")
    }

    /** "Plan B · Reject: a sign-in screen appeared before any consent screen and was not passed (you were asked to complete it)." */
    private fun gateLine(s: AuditSession): String {
        val b = s.conditions?.blocker ?: return "${s.plan.label}: a blocking screen appeared before any consent screen."
        val kind = when (b.kind) {
            "SIGN_IN" -> "sign-in"
            "OTP" -> "verification-code"
            "CAPTCHA" -> "CAPTCHA"
            "BIOMETRIC" -> "fingerprint, PIN or passcode"
            "FORM" -> "details-form"
            else -> "blocking"
        }
        return "${s.plan.label}: a $kind screen appeared before any consent screen and was not passed" +
            (if (b.handedOff) " (you were asked to complete it)" else "") + "."
    }

    /** Connections a plan recorded: the deeper capture's when given, else its own full-relay flows; null when not captured. */
    private fun connectionCount(s: AuditSession, seen: List<SeenConnection>?): Int? =
        seen?.size ?: s.flows.size.takeIf { s.captureDepth == FULL_RELAY }

    /** "Background watch: watched 10 minutes; 1 lookup, 1 connection; it was running when the watch began." */
    private fun watchLine(s: AuditSession, seen: List<SeenConnection>?): String {
        val w = s.watch ?: return "${s.plan.label}: ${s.netEvents.size} lookups."
        val lookups = s.netEvents.count { !it.fromConnection }
        val conns = connectionCount(s, seen)?.let { count(it, "connection") } ?: "connections were not captured"
        val running = when (s.conditions?.runningBeforeStart) {
            true -> "; it was running when the watch began"
            false -> "; it was not running when the watch began"
            null -> ""
        }
        return "${BackgroundWatches.LABEL}: watched ${BackgroundWatches.length(w)}; ${count(lookups, "lookup")}, $conns$running."
    }

    private fun quietWatchSentence(watches: List<AuditSession>, seen: Map<AuditSession, List<SeenConnection>?>, busy: Boolean): String {
        val first = watches.first()
        val w = first.watch!!
        val lookups = watches.sumOf { s -> s.netEvents.count { !it.fromConnection } }
        val conns = watches.mapNotNull { connectionCount(it, seen[it]) }.takeIf { it.size == watches.size }?.sum()
        val counts = count(lookups, "lookup") + (conns?.let { ", " + count(it, "connection") } ?: "")
        val head = "TraceVeil watched ${first.targetLabel} in the background for ${BackgroundWatches.length(w)}"
        if (busy) return "$head: it connected ${conns ?: "several"} times but made only ${count(lookups, "lookup")} TraceVeil could see, " +
            "so it reused addresses it looked up before the watch or uses its own DNS. The connections are in the check's evidence."
        val notRunning = if (watches.all { it.conditions?.runningBeforeStart == false }) " It was not running when the watch began." else ""
        return "$head and saw it make almost no connections ($counts). That is what this watch observed: the app may go online " +
            "less often than that, or keep using a connection it opened before the watch began, which TraceVeil cannot see.$notRunning"
    }

    private const val FULL_RELAY = "full-relay"

    /** Captured time for one plan: the capture's own window when recorded, else the plan's length. */
    private fun watchedNanos(s: AuditSession): Long {
        val h = s.captureHealth
        val start = h?.captureStartedNanos
        val end = h?.captureEndedNanos
        val capture = if (start != null && end != null && end > start) end - start else null
        return capture ?: (s.endedAtNanos - s.startedAtNanos).coerceAtLeast(0)
    }

    private val STOP_NOTES = listOf("Capture stopped", "Capture was not running", "Capture restarted after")

    private fun secs(nanos: Long) = String.format(Locale.US, "%.1f", nanos / 1e9)

    private fun count(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"
}
