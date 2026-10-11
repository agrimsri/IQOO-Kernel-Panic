package com.dpdpxray.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.dpdpxray.app.quarantine.QuarantineController
import com.dpdpxray.app.ui.XrayViewModel
import com.dpdpxray.app.ui.components.CollapsibleCard
import com.dpdpxray.app.ui.components.Note
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.system.GroupDivider
import com.dpdpxray.app.ui.system.ListRow
import com.dpdpxray.app.ui.system.StatusDot
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.Phase
import com.dpdpxray.core.netcapture.CleartextPersonalData
import com.dpdpxray.core.netcapture.ConnectionFacts
import com.dpdpxray.core.netcapture.ConnectionSummary
import com.dpdpxray.core.netcapture.FlowProtocol
import com.dpdpxray.core.netcapture.FlowRecord
import com.dpdpxray.core.netcapture.TlsStatus
import com.dpdpxray.core.quarantine.QuarantineReport
import com.dpdpxray.core.trackers.TrackerCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Plain wording for connection lists on Results ("Where it connects"): one row per destination (name or address, port,
 * protocol), never a request value. Pure, so the wording is unit-tested.
 */
object ConnectionListText {
    data class Row(val title: String, val subtitle: String, val cleartext: Boolean)

    const val MAX_ROWS = 40

    fun protocol(f: FlowRecord): String = when {
        f.tls == TlsStatus.CLEARTEXT -> "plain HTTP"
        f.protocol == FlowProtocol.QUIC -> "QUIC"
        f.tls == TlsStatus.SNI_ONLY || f.tls == TlsStatus.ECH_HIDDEN -> "TLS"
        f.protocol == FlowProtocol.UDP -> "UDP"
        else -> "TCP"
    }

    fun phase(p: Phase): String = when (p) {
        Phase.BEFORE_CONSENT -> "before the choice"
        Phase.AFTER_ACCEPT -> "after Accept"
        Phase.AFTER_REJECT -> "after Reject"
        Phase.NO_CONSENT_SEEN -> "no consent screen seen"
    }

    /**
     * Groups [flows] by destination and protocol, earliest first. [whenText] says when the first of a group happened (the
     * consent phase for an audit, the time into the run for quarantine); [noLookupNote] is added when a named connection
     * had no lookup TraceVeil saw.
     */
    fun rows(flows: List<FlowRecord>, catalog: TrackerCatalog?, whenText: (FlowRecord) -> String, noLookupNote: String = "no lookup seen"): List<Row> =
        flows.sortedBy { it.startNanos }.groupBy { Triple(it.host?.lowercase() ?: it.dstIp, it.dstPort, protocol(it)) }.map { (key, group) ->
            val first = group.first()
            val parts = mutableListOf("port ${key.second}", key.third, whenText(first))
            if (group.size > 1) parts += "${group.size} connections"
            if (first.host != null && group.any { it.noLookup }) parts += noLookupNote
            if (group.any { it.tls == TlsStatus.ECH_HIDDEN }) parts += "Encrypted Client Hello: the name may not be the real server"
            if (first.host == null && first.protocol == FlowProtocol.QUIC) parts += "name not read"
            catalog?.let { c -> first.host?.let { c.classify(it) } }?.takeIf { it.entity != null && it.category.isTracking }
                ?.let { parts += "catalogued as ${it.entity} (${it.category.name.lowercase().replace('_', ' ')})" }
            val request = group.flatMap { it.http }.firstOrNull()
            if (request != null) {
                parts += "${request.method} ${request.target}"
                val hits = CleartextPersonalData.scan(request)
                if (hits.isNotEmpty()) parts += "personal-data fields: ${CleartextPersonalData.describe(hits)}"
            }
            Row(key.first, parts.joinToString(" · "), group.any { it.tls == TlsStatus.CLEARTEXT })
        }

    /** The card summary for an audit, from [ConnectionSummary]. */
    fun auditSummary(n: ConnectionSummary): String = buildString {
        append("${n.connections} connections, ${n.named} named by the connection itself")
        if (n.cleartextRequests > 0) append(" · ${n.cleartextRequests} plain-HTTP requests")
        if (n.namesWithoutLookup.isNotEmpty()) append(" · ${n.namesWithoutLookup.size} names without a lookup")
    }

    fun quarantineSummary(report: QuarantineReport, dateText: String): String =
        "From the quarantine run on $dateText: ${report.observations.connections.size} connections answered on the phone"

    const val AUDIT_NOTE = "Forwarded unchanged to the servers the app chose. Names come from the connection itself (TLS or " +
        "QUIC hello, HTTP Host) or from the app's own lookups; HTTPS contents are not readable, and plain-HTTP values are " +
        "replaced by their kind."

    const val QUARANTINE_NOTE = "A separate run with this app's internet blocked: TraceVeil answered each connection on the " +
        "phone and closed it after the first message, so nothing was sent. Not part of this check's score."

    fun secondsInto(f: FlowRecord, startNanos: Long): String = String.format(Locale.US, "+%.1f s into the run", (f.startNanos - startNanos) / 1e9)
}

/** The connections this audit's full-relay capture saw. Shown only when a plan captured connections. */
@Composable
fun AuditConnectionsCard(sessions: List<AuditSession>, vm: XrayViewModel, modifier: Modifier = Modifier) {
    val summary = remember(sessions) { ConnectionSummary.of(sessions) }?.takeIf { it.captured } ?: return
    val settings by vm.settings.collectAsState()
    val catalog = remember(vm, settings.firstPartyDomains) { vm.graph.catalog() }
    val rows = remember(sessions, catalog) {
        val flows = sessions.filter { it.captureDepth == ConnectionFacts.FULL_RELAY }.flatMap { it.flows }
        ConnectionListText.rows(flows, catalog, { ConnectionListText.phase(it.phase) })
    }
    CollapsibleCard("Connections", modifier, summary = ConnectionListText.auditSummary(summary), glyph = Glyph.PRIVATE_DNS) {
        Note(ConnectionListText.AUDIT_NOTE)
        ConnectionRows(rows)
    }
}

/** The latest saved quarantine run's answered connections for [packageName], when that run recorded them. */
@Composable
fun QuarantineConnectionsCard(packageName: String, vm: XrayViewModel, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val report by produceState<QuarantineReport?>(null, packageName) {
        value = withContext(Dispatchers.IO) { runCatching { QuarantineController.store(ctx).latestFor(packageName) }.getOrNull() }
    }
    val r = report?.takeIf { it.observations.connectionsRead == true } ?: return
    val settings by vm.settings.collectAsState()
    val catalog = remember(vm, settings.firstPartyDomains) { vm.graph.catalog() }
    val rows = remember(r.id, catalog) {
        ConnectionListText.rows(r.observations.connections, catalog, { ConnectionListText.secondsInto(it, r.startedAtNanos) }, noLookupNote = "never looked up")
    }
    val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(r.startedAtEpochMs))
    CollapsibleCard("Connections (quarantine)", modifier, summary = ConnectionListText.quarantineSummary(r, date), glyph = Glyph.VPN) {
        Note(ConnectionListText.QUARANTINE_NOTE)
        if (rows.isEmpty()) Note("The app opened no connections during that run.") else ConnectionRows(rows)
    }
}

@Composable
private fun ConnectionRows(rows: List<ConnectionListText.Row>) {
    Column(Modifier.fillMaxWidth()) {
        rows.take(ConnectionListText.MAX_ROWS).forEachIndexed { i, row ->
            if (i > 0) GroupDivider()
            ListRow(
                row.title, subtitle = row.subtitle, subtitleMaxLines = 4,
                leading = if (row.cleartext) ({ StatusDot(Film.leak) }) else null,
            )
        }
        if (rows.size > ConnectionListText.MAX_ROWS) Note("${rows.size - ConnectionListText.MAX_ROWS} more destinations are in the exported report.")
    }
}
