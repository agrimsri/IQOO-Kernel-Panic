package com.dpdpxray.core.netcapture

import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.Category
import com.dpdpxray.core.model.Phase
import com.dpdpxray.core.trackers.TrackerCatalog
import kotlinx.serialization.Serializable

@Serializable enum class RiskLevel { CLEAN, LOW, MEDIUM, HIGH, UNKNOWN }

@Serializable enum class NetFindingKind {
    TRACKING_AFTER_REJECT, TRACKING_BEFORE_CHOICE, SENSITIVE_IN_REQUEST, CLEARTEXT_TO_TRACKER,
    ENCRYPTED_DNS_BYPASS, UNLOOKED_UP_DESTINATION, UNKNOWN_DESTINATION_UPLOAD, NO_DROP_AFTER_REJECT,
}

@Serializable enum class NetConfidence { LOW, MEDIUM, HIGH }

@Serializable enum class NetAction(val label: String) {
    REVIEW_CONSENT("Review the consent choice"),
    INVESTIGATE("Investigate this destination"),
    RESTRICT("Restrict the app's network access"),
    CONTACT_DEVELOPER("Ask the developer to explain"),
}

/** Where a finding's wording came from. Facts and evidence always come from [RULE]; a model may only reword and rank. */
@Serializable enum class FindingSource { RULE, MODEL_ASSISTED }

/**
 * One actionable network finding. [whatHappened] and [evidence] are observed facts; [whyItMatters] is interpretation;
 * [limitation] states what could not be inspected. Nothing here claims personal data was sent unless [evidence] shows it.
 */
@Serializable
data class NetFinding(
    val id: String,
    val kind: NetFindingKind,
    val severity: RiskLevel,
    val title: String,
    val destination: String,
    val entity: String? = null,
    val category: Category = Category.UNKNOWN,
    val phase: Phase,
    val tNanos: Long,
    val whatHappened: String,
    val whyItMatters: String,
    val evidence: List<String>,
    val confidence: NetConfidence,
    val limitation: String,
    val action: NetAction,
    val bytesUp: Long = 0,
    val bytesDown: Long = 0,
    val source: FindingSource = FindingSource.RULE,
)

@Serializable
data class NetAssessment(
    val level: RiskLevel,
    val findings: List<NetFinding>,
    /** Things this capture could not see, shown beside the result so silence is never read as "clean". */
    val limitations: List<String>,
    val traffic: List<PhaseTraffic>,
)

/** Deterministic rules over the relay's flows. Each rule needs observed evidence; none uses a model. */
object NetFindings {
    private val DOH_NAMES = setOf("dns.google", "cloudflare-dns.com", "mozilla.cloudflare-dns.com", "dns.quad9.net", "one.one.one.one", "dns.adguard.com", "doh.opendns.com")
    private val PUBLIC_RESOLVERS = setOf("8.8.8.8", "8.8.4.4", "1.1.1.1", "1.0.0.1", "9.9.9.9", "149.112.112.112")
    private const val UPLOAD_MIN_BYTES = 4096L

    fun assess(session: AuditSession, catalog: TrackerCatalog): NetAssessment {
        val clock = PhaseClock.from(session.consentEvents)
        val traffic = FlowPhases.traffic(session.flows, clock)
        val limits = limitations(session)
        if (session.captureDepth != "full-relay") return NetAssessment(RiskLevel.UNKNOWN, emptyList(), limits, traffic)

        val choiceNanos = session.consentChoice?.tNanos
        val out = ArrayList<NetFinding>()
        val byHost = session.flows.groupBy { hostOf(it) ?: it.dstIp }

        for ((host, flows) in byHost) {
            val info = catalog.classify(host, session.netEvents.firstOrNull { it.hostname == host }?.cnameChain.orEmpty())
            val tracking = info.category.isTracking
            val first = flows.minBy { it.startNanos }
            val up = flows.sumOf { it.bytesUp }; val down = flows.sumOf { it.bytesDown }
            val phase = flows.map { it.phase }.let { ph -> if (Phase.AFTER_REJECT in ph) Phase.AFTER_REJECT else first.phase }
            val delay = choiceNanos?.let { (first.startNanos - it).coerceAtLeast(0) / 1_000_000_000.0 }
            val posts = flows.flatMap { it.http }.filter { it.method in setOf("POST", "PUT") }
            val cleartext = flows.any { it.tls == TlsStatus.CLEARTEXT }
            val inspect = if (flows.all { it.tls == TlsStatus.SNI_ONLY || it.tls == TlsStatus.ECH_HIDDEN || it.tls == TlsStatus.OPAQUE }) "The request contents were not readable (encrypted), so what was sent is unknown." else "Cleartext request heads were readable; bodies are never read."

            fun base(kind: NetFindingKind, sev: RiskLevel, title: String, what: String, why: String, ev: List<String>, conf: NetConfidence, action: NetAction) =
                NetFinding("${kind.name}:$host", kind, sev, title, host, info.entity, info.category, phase, first.startNanos, what, why, ev, conf, inspect, action, up, down)

            val evidence = buildList {
                add("First seen ${rel(first.startNanos, session)} · ${flows.size} connection(s) · $up B sent, $down B received")
                if (delay != null && phase == Phase.AFTER_REJECT) add("${"%.1f".format(delay)} s after Reject")
                posts.firstOrNull()?.let { add("${it.method} ${it.target}") }
            }

            if (tracking && phase == Phase.AFTER_REJECT) {
                val conf = when { posts.isNotEmpty() -> NetConfidence.HIGH; up > 0 -> NetConfidence.MEDIUM; else -> NetConfidence.LOW }
                out += base(
                    NetFindingKind.TRACKING_AFTER_REJECT, if (up > 0) RiskLevel.HIGH else RiskLevel.MEDIUM,
                    "Tracking connection after consent rejection",
                    "${info.entity ?: host} (${info.category.name.lowercase()}) was contacted after you rejected consent${if (posts.isNotEmpty()) "; a ${posts.first().method} request was observed" else ""}.",
                    "This destination is catalogued as ${info.category.name.lowercase()}, and the connection happened after the Reject choice.",
                    evidence, conf, NetAction.REVIEW_CONSENT,
                )
            } else if (tracking && phase == Phase.BEFORE_CONSENT && up > 0) {
                out += base(
                    NetFindingKind.TRACKING_BEFORE_CHOICE, RiskLevel.MEDIUM, "Tracker contacted before you chose",
                    "${info.entity ?: host} received $up bytes before any consent choice was recorded.",
                    "Consent is normally expected before analytics or advertising starts.", evidence,
                    if (cleartext || posts.isNotEmpty()) NetConfidence.HIGH else NetConfidence.MEDIUM, NetAction.REVIEW_CONSENT,
                )
            }

            val sensitive = flows.flatMap { it.http }.filter { it.sensitive.isNotEmpty() }
            if (sensitive.isNotEmpty() && info.category != Category.FIRST_PARTY) {
                val kinds = sensitive.flatMap { it.sensitive }.toSortedSet().joinToString { it.label }
                out += base(
                    NetFindingKind.SENSITIVE_IN_REQUEST, RiskLevel.HIGH, "Sensitive value in an unencrypted request",
                    "A plain-HTTP request to $host carried: $kinds.",
                    "Unencrypted requests can be read by anyone on the network path, and the value was visible in the URL or headers.",
                    evidence + sensitive.take(2).map { "${it.method} ${it.target}" }, NetConfidence.HIGH, NetAction.CONTACT_DEVELOPER,
                )
            } else if (tracking && cleartext) {
                out += base(
                    NetFindingKind.CLEARTEXT_TO_TRACKER, RiskLevel.MEDIUM, "Tracker reached over unencrypted HTTP",
                    "$host was contacted without TLS.", "Tracker traffic sent in the clear exposes whatever it carries.", evidence, NetConfidence.HIGH, NetAction.INVESTIGATE,
                )
            }

            val doh = flows.any { (it.protocol == FlowProtocol.TCP && it.dstPort == 853) || it.sni in DOH_NAMES || (it.dstIp in PUBLIC_RESOLVERS && it.dstPort == 443) }
            if (doh) {
                out += base(
                    NetFindingKind.ENCRYPTED_DNS_BYPASS, RiskLevel.MEDIUM, "App resolves names outside the observable DNS",
                    "The app connected to an encrypted-DNS resolver ($host).", "Lookups made this way are invisible to DNS-based audits, so other destinations may be hidden.",
                    evidence, NetConfidence.MEDIUM, NetAction.INVESTIGATE,
                )
            } else if (!tracking && info.category == Category.UNKNOWN && flows.any { it.noLookup } && up > 0 && flows.none { it.dnsName != null }) {
                out += base(
                    NetFindingKind.UNLOOKED_UP_DESTINATION, RiskLevel.LOW, "Connection to an address the app never looked up",
                    "The app sent $up bytes to ${first.dstIp}, an address no DNS lookup resolved to.",
                    "Hard-coded addresses or hidden lookups can bypass name-based blocking and audits.", evidence, NetConfidence.LOW, NetAction.INVESTIGATE,
                )
            }

            if (!tracking && info.category == Category.UNKNOWN && up >= UPLOAD_MIN_BYTES && up > 3 * down && phase != Phase.NO_CONSENT_SEEN && !doh) {
                out += base(
                    NetFindingKind.UNKNOWN_DESTINATION_UPLOAD, RiskLevel.LOW, "Upload to an unrecognised destination",
                    "$host is not in the tracker catalogue and received $up bytes while sending back only $down.",
                    "Mostly-outbound traffic to an unknown server is worth a look; it may be the app's own backend.", evidence, NetConfidence.LOW, NetAction.INVESTIGATE,
                )
            }
        }

        out += noDrop(session, catalog, traffic)
        val ranked = out.distinctBy { it.id }.sortedWith(compareByDescending<NetFinding> { it.severity.ordinal }.thenByDescending { it.confidence.ordinal }.thenByDescending { it.bytesUp })
        val level = when {
            ranked.any { it.severity == RiskLevel.HIGH } -> RiskLevel.HIGH
            ranked.any { it.severity == RiskLevel.MEDIUM } -> RiskLevel.MEDIUM
            ranked.isNotEmpty() -> RiskLevel.LOW
            session.flows.isEmpty() -> RiskLevel.UNKNOWN
            else -> RiskLevel.CLEAN
        }
        return NetAssessment(level, ranked, limits, traffic)
    }

    /** Tracker volume that did not fall after Reject: an inference from two observed totals. */
    private fun noDrop(session: AuditSession, catalog: TrackerCatalog, traffic: List<PhaseTraffic>): List<NetFinding> {
        val clock = PhaseClock.from(session.consentEvents)
        val tracker = session.flows.filter { catalog.classify(hostOf(it) ?: it.dstIp).category.isTracking }
        fun sum(p: Phase) = tracker.filter { clock.at(it.startNanos) == p }.sumOf { it.bytesUp }
        val before = sum(Phase.BEFORE_CONSENT); val after = sum(Phase.AFTER_REJECT)
        if (before <= 0 || after <= 0 || after * 2 < before) return emptyList()
        return listOf(
            NetFinding(
                id = "NO_DROP_AFTER_REJECT", kind = NetFindingKind.NO_DROP_AFTER_REJECT, severity = RiskLevel.MEDIUM,
                title = "Tracker traffic did not stop after Reject", destination = "trackers (all)", phase = Phase.AFTER_REJECT,
                tNanos = session.consentChoice?.tNanos ?: 0,
                whatHappened = "Tracker connections sent $after bytes after Reject, compared with $before bytes before any choice.",
                whyItMatters = "If rejecting consent worked, tracker traffic would be expected to fall sharply.",
                evidence = listOf("Before choice: $before B · After Reject: $after B (connections that opened in each phase)"),
                confidence = NetConfidence.MEDIUM, limitation = "Compares totals only; encrypted contents are not readable.",
                action = NetAction.REVIEW_CONSENT, bytesUp = after,
            ),
        )
    }

    private fun limitations(session: AuditSession): List<String> = buildList {
        if (session.captureDepth != "full-relay") add("This audit ran DNS-only: connections and traffic volume were not observed.")
        else {
            add("Only IPv4 was captured; IPv6 traffic bypasses this view.")
            if (session.flows.any { it.tls == TlsStatus.SNI_ONLY || it.tls == TlsStatus.ECH_HIDDEN }) add("Encrypted (HTTPS) request contents are not readable; only the server name, timing and byte counts.")
            if (session.flows.any { it.protocol == FlowProtocol.QUIC }) add("QUIC (HTTP/3) connections are visible by address and size only.")
            if (session.flows.any { it.tls == TlsStatus.ECH_HIDDEN }) add("Some connections hide their server name (Encrypted Client Hello).")
        }
    }

    private fun hostOf(f: FlowRecord) = f.host?.lowercase()

    private fun rel(t: Long, s: AuditSession) = "+%.1f s into the audit".format((t - s.startedAtNanos).coerceAtLeast(0) / 1e9)
}
