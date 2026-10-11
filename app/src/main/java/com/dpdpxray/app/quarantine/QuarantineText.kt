package com.dpdpxray.app.quarantine

import com.dpdpxray.core.netcapture.CleartextPersonalData
import com.dpdpxray.core.netcapture.FlowProtocol
import com.dpdpxray.core.netcapture.FlowRecord
import com.dpdpxray.core.netcapture.TlsStatus
import com.dpdpxray.core.quarantine.AddressScope
import com.dpdpxray.core.quarantine.QuarantineReport
import java.util.Locale

/** Plain-language wording for quarantine screens. Describes attempts only; never a verdict on the app. */
object QuarantineText {
    const val NOT_A_VERDICT = "Network blocked · not a malware verdict · not a full sandbox"

    const val EXPLAINER = "TraceVeil cuts this one app off from the internet, opens it, and records what it tries to do: " +
        "the names it looks up, the addresses it tries to connect to (including addresses built into the app), the " +
        "server each connection names in its first message, and the Android permission dialogs it shows on first run. " +
        "While the run lasts, nothing can leave through the app's own internet connections."

    const val ONLY_INTERNET = "Only internet is blocked. SMS, calls and requests handed to other apps or Android " +
        "services are not. Do not grant SMS, Accessibility or \"Display over other apps\" to an app you do not trust."

    val STEPS = listOf(
        "If the app is already open, swipe it away from Recents first, so you see its first run.",
        "Tap Start. TraceVeil cuts the app's internet, then opens it.",
        "Use it as a new user would. On permission dialogs, tap \"Don't allow\" unless you deliberately want to see what comes next. TraceVeil never taps anything here.",
        "Come back and tap Stop, use \"Stop quarantine\" in the notification, or wait for the timer. The app's internet then returns.",
    )

    fun duration(ms: Long): String = if (ms < 120_000) "${ms / 1000} s" else "${ms / 60_000} min"

    fun seconds(ms: Long): String = String.format(Locale.US, "%.1f s", ms / 1000.0)

    fun headline(report: QuarantineReport): String {
        val s = report.summary
        if (s.nothingObserved) {
            return "Nothing was seen in ${seconds(report.observedMs)}. That is not proof the app does nothing: it may " +
                "wait longer, need a login, or use paths this run cannot see."
        }
        val parts = mutableListOf<String>()
        if (s.uniqueDomains > 0) parts += "tried to look up ${plural(s.uniqueDomains, "name")}"
        if (s.uniqueIps > 0) parts += "tried to reach ${plural(s.uniqueIps, "address", "addresses")} (${plural(s.blockedPackets.toInt(), "blocked packet")})"
        else if (s.blockedPackets > 0) parts += "sent ${plural(s.blockedPackets.toInt(), "blocked packet")} to the phone's own resolver"
        if (s.connectionsAnswered > 0) parts += "opened ${plural(s.connectionsAnswered, "connection")} that TraceVeil answered on the phone"
        if (s.permissionDialogs > 0) parts += "showed ${plural(s.permissionDialogs, "permission dialog")}"
        val did = if (parts.isEmpty()) "made no lookups or connection attempts" else andList(parts)
        val settings = if (s.settingsScreens.isEmpty()) "" else
            " ${andList(s.settingsScreens.map { it.title }).replaceFirstChar { it.uppercase() }} appeared during the run."
        return "In ${seconds(report.observedMs)}, ${report.targetLabel} $did.$settings Nothing it tried could leave through its own connections."
    }

    private fun andList(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }

    fun bypassExplanation(addresses: List<String>): String =
        "Every lookup was answered \"does not exist\", so these addresses were built into the app, cached from before " +
            "the run, or obtained another way: ${addresses.joinToString(", ")}. A signal worth a closer look, not proof of harm."

    fun namesWithoutLookupExplanation(names: List<String>): String =
        "These names were read from the connections themselves (TLS hello or HTTP Host), but the app never looked them " +
            "up during the run: ${names.joinToString(", ")}. It reached their addresses another way: its own DNS (for " +
            "example DNS over HTTPS), addresses cached before the run, or addresses built into the app. A signal worth a " +
            "closer look, not proof of harm."

    fun cleartextExplanation(lines: List<String>): String =
        "Plain HTTP can be read by anyone on the network path, such as the same Wi-Fi: ${lines.joinToString("; ")}. " +
            "Only field names and the kind of value were kept, never the values. TraceVeil answered on the phone, so " +
            "nothing was sent; outside quarantine the app would send this unencrypted."

    /** "TLS", "HTTP", "QUIC" or "TCP": what the first message of an answered connection was. */
    fun connectionLabel(f: FlowRecord): String = when {
        f.tls == TlsStatus.CLEARTEXT -> "HTTP"
        f.protocol == FlowProtocol.QUIC -> "QUIC"
        f.tls == TlsStatus.SNI_ONLY || f.tls == TlsStatus.ECH_HIDDEN -> "TLS"
        else -> "TCP"
    }

    /** Endpoint, what its first message said, and the catalogue match for the name ([tracker]), if any. */
    fun connectionNote(f: FlowRecord, tracker: String?): String {
        val what = when {
            f.tls == TlsStatus.CLEARTEXT -> f.http.firstOrNull()?.let { h ->
                val hits = CleartextPersonalData.scan(h)
                "plain HTTP: ${h.method} ${h.target}" + if (hits.isEmpty()) "" else " · personal-data fields: ${CleartextPersonalData.describe(hits)}"
            } ?: "plain HTTP"
            f.tls == TlsStatus.ECH_HIDDEN -> "offered Encrypted Client Hello: the name may not be the real server"
            f.protocol == FlowProtocol.QUIC && f.sni != null -> "name read from the QUIC hello"
            f.protocol == FlowProtocol.QUIC -> "QUIC, name not read"
            f.sni != null -> "name read from the TLS hello" + if (f.alpn.isEmpty()) "" else " · ${f.alpn.joinToString()}"
            f.bytesUp > 0 -> "${f.bytesUp} bytes that are not TLS or HTTP"
            else -> "nothing sent before it was closed"
        }
        return listOfNotNull("${f.dstIp}:${f.dstPort}", what, tracker?.let { "catalogued as $it" }).joinToString(" · ")
    }

    /** Short note for non-public destinations; null for ordinary public addresses. */
    fun scopeNote(scope: AddressScope): String? = when (scope) {
        AddressScope.PUBLIC -> null
        AddressScope.PRIVATE -> "local or private network"
        AddressScope.MULTICAST -> "local discovery (multicast)"
        AddressScope.BROADCAST -> "broadcast"
        AddressScope.LINK_LOCAL -> "link-local"
        AddressScope.LOOPBACK -> "this phone"
        AddressScope.UNSPECIFIED, AddressScope.RESERVED -> "reserved address"
    }

    fun plural(n: Int, one: String, many: String = one + "s"): String = "$n ${if (n == 1) one else many}"
}
