package com.dpdpxray.core.quarantine

import kotlinx.serialization.Serializable

/**
 * What a quarantine run adds up to. Attempts are not proof that data was sent: nothing could leave through this
 * app's own network connections while the run lasted.
 */
@Serializable
data class QuarantineSummary(
    /** Unique names the app tried to look up, in first-seen order. */
    val domainsAttempted: List<String>,
    /** Unique addresses the app tried to reach (the phone's own resolver excluded), in first-seen order. */
    val ipsAttempted: List<String>,
    val uniqueDomains: Int,
    val uniqueIps: Int,
    /** Unique (address, port) pairs the app tried to reach. */
    val uniqueDestinations: Int,
    val dnsQueries: Long,
    val blockedPackets: Long,
    /**
     * Server-type addresses (public or private, not multicast/broadcast) the app tried to reach although no DNS answer
     * given during the run contained them, before the first attempt. In quarantine every answer is NXDOMAIN, so each
     * such address came from somewhere else: built into the app, cached from before the run, or obtained another way.
     */
    val addressesWithoutLookup: List<String>,
    /** INFERRED: at least one entry in [addressesWithoutLookup]. A hard-coded-address signal, not proof. */
    val dnsBypassSignal: Boolean,
    /** Resolvers other than the phone's own that the app sent plain DNS to directly. */
    val directResolvers: List<String>,
    /** Blocked destinations that look like DNS over TLS (port 853) or DNS over TCP (TCP port 53). */
    val encryptedDnsDestinations: List<String>,
    val permissionDialogs: Int,
    val settingsScreens: List<SensitiveSettings>,
    val appCameToScreen: Boolean,
    /** No lookups, blocked packets, dialogs or settings screens were seen. Not proof the app does nothing. */
    val nothingObserved: Boolean,
) {
    companion object {
        /**
         * [dnsAnswers] maps an address to the earliest time a DNS answer containing it was returned to the app during
         * the run. Quarantine answers every lookup with NXDOMAIN, so it passes an empty map.
         */
        fun of(
            observations: QuarantineObservations,
            resolverAddresses: Set<String>,
            dnsAnswers: Map<String, Long> = emptyMap(),
        ): QuarantineSummary {
            val appRows = observations.blocked.filter { it.role == DestinationRole.APP_DESTINATION }
            val serverScopes = setOf(AddressScope.PUBLIC, AddressScope.PRIVATE)
            val withoutLookup = appRows
                .filter { it.scope in serverScopes }
                .filter { row -> dnsAnswers[row.address]?.let { answeredAt -> answeredAt > row.firstNanos } ?: true }
                .map { it.address }
                .distinct()
            val encrypted = observations.blocked
                .filter { it.port == 853 || (it.port == 53 && it.protocol != AttemptProtocol.UDP) }
                .map { "${it.endpoint} (${it.protocol.label})" }
                .distinct()
            val ips = appRows.map { it.address }.distinct()
            return QuarantineSummary(
                domainsAttempted = observations.domains.map { it.hostname },
                ipsAttempted = ips,
                uniqueDomains = observations.domains.size,
                uniqueIps = ips.size,
                uniqueDestinations = appRows.map { it.address to it.port }.distinct().size,
                dnsQueries = observations.dnsQueries,
                blockedPackets = observations.blockedPackets,
                addressesWithoutLookup = withoutLookup,
                dnsBypassSignal = withoutLookup.isNotEmpty(),
                directResolvers = observations.domains.flatMap { it.resolvers }.filter { it !in resolverAddresses }.distinct(),
                encryptedDnsDestinations = encrypted,
                permissionDialogs = observations.permissionDialogs.size,
                settingsScreens = observations.settingsScreens.map { it.kind },
                appCameToScreen = observations.targetOnScreenNanos != null,
                nothingObserved = observations.dnsQueries == 0L && observations.blocked.isEmpty() &&
                    observations.permissionDialogs.isEmpty() && observations.settingsScreens.isEmpty(),
            )
        }
    }
}
