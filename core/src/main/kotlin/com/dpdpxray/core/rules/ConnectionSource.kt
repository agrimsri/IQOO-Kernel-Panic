package com.dpdpxray.core.rules

import com.dpdpxray.core.model.AuditSession

/**
 * A connection the app opened, as seen by a capture deeper than DNS (for example a full-relay capture's flows), reduced
 * to what the rules and the visibility diagnosis need. Nothing here says what was sent.
 */
data class SeenConnection(
    val tNanos: Long,
    /** Server name read from the connection itself (TLS SNI or an HTTP Host header); null when none was readable. */
    val serverName: String?,
    /** No lookup TraceVeil saw answered with this connection's address (fixed address, own DNS, or an older cache). */
    val noLookup: Boolean,
)

/**
 * Where the rules read connections from. The default [NONE] is today's DNS-only capture, and every rule then behaves
 * exactly as before. A deeper capture plugs in here without the rules depending on its types, for example:
 *
 * ```
 * ConnectionSource { s -> if (s.captureDepth != "full-relay") null
 *     else s.flows.filterNot { it.failed }.map { SeenConnection(it.startNanos, it.sni ?: it.http.firstOrNull()?.host, it.noLookup) } }
 * ```
 *
 * When a plan saw too few lookups, connections with a readable server name stand in for them (the evidence says
 * "connected", not "looked up"), so a plan whose app skips visible DNS can still be scored; connections without a name
 * only explain the gap (see [VisibilityDiagnosis]).
 */
fun interface ConnectionSource {
    /** The plan's connections, or null when this plan was not captured that deeply (unknown, never "none"). */
    fun connections(session: AuditSession): List<SeenConnection>?

    companion object {
        val NONE = ConnectionSource { null }
    }
}
