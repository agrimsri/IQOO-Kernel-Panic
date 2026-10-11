package com.dpdpxray.core.quarantine

import com.dpdpxray.core.netcapture.FlowRecord
import kotlinx.serialization.Serializable

/** OBSERVED = seen on this phone. INFERRED = reasoned from what was seen. NOT_ESTABLISHED = this run cannot tell. */
@Serializable
enum class QuarantineEvidence { OBSERVED, INFERRED, NOT_ESTABLISHED }

/** Whose destination a blocked packet was addressed to. */
@Serializable
enum class DestinationRole {
    /** Any address the app tried to reach. */
    APP_DESTINATION,

    /** The phone's own resolver inside the tunnel, reached by something other than plain UDP DNS (e.g. DNS over TLS). */
    PHONE_RESOLVER,
}

/** Sensitive Android settings screens that banking-trojan style apps commonly ask people to open. */
@Serializable
enum class SensitiveSettings(val title: String) {
    ACCESSIBILITY("Accessibility settings"),
    DISPLAY_OVER_OTHER_APPS("Display over other apps"),
}

/** Every lookup of one name during the run. All were answered locally with NXDOMAIN; none was forwarded. */
@Serializable
data class DomainAttempt(
    val hostname: String,
    val queryTypes: List<Int>,
    /** Addresses the queries were sent to; anything but the phone's tunnel resolver means the app picked its own. */
    val resolvers: List<String>,
    val firstNanos: Long,
    val lastNanos: Long,
    val count: Long,
)

/** Packets to one destination (protocol, address, port) that were dropped. Counts include automatic retries. */
@Serializable
data class BlockedAttempt(
    val protocol: AttemptProtocol,
    val protocolNumber: Int,
    val ipVersion: Int,
    val address: String,
    val port: Int?,
    val scope: AddressScope,
    val role: DestinationRole,
    val firstNanos: Long,
    val lastNanos: Long,
    val packets: Long,
) {
    /** "203.0.113.9:443", "[2001:db8::1]:443" or just the address when there is no port. */
    val endpoint: String
        get() = when {
            port == null -> address
            ipVersion == 6 -> "[$address]:$port"
            else -> "$address:$port"
        }
}

/** A system permission dialog shown during the run: only its visible text is kept. */
@Serializable
data class PermissionDialogSeen(
    val packageName: String,
    val text: String,
    /** The dialog text names the quarantined app (by label or package). */
    val namesTarget: Boolean,
    val firstNanos: Long,
    val lastNanos: Long,
    val timesShown: Int,
)

/** A sensitive settings screen became visible during the run. Who opened it (the app or the person) is not known. */
@Serializable
data class SettingsScreenSeen(
    val kind: SensitiveSettings,
    val title: String,
    val firstNanos: Long,
    val lastNanos: Long,
    val timesShown: Int,
)

/** Everything one quarantine run recorded. Times are `SystemClock.elapsedRealtimeNanos` values. */
@Serializable
data class QuarantineObservations(
    val domains: List<DomainAttempt> = emptyList(),
    val blocked: List<BlockedAttempt> = emptyList(),
    val permissionDialogs: List<PermissionDialogSeen> = emptyList(),
    val settingsScreens: List<SettingsScreenSeen> = emptyList(),
    /** First accessibility event from the app's own window, if any. */
    val targetOnScreenNanos: Long? = null,
    val dnsQueries: Long = 0,
    val blockedPackets: Long = 0,
    val networkControlPackets: Long = 0,
    val unreadablePackets: Long = 0,
    /** Lookups of new names after the domain list was full (still answered NXDOMAIN and counted in [dnsQueries]). */
    val domainsOverflow: Long = 0,
    /** Packets to new destinations after the destination list was full (still dropped and counted). */
    val destinationsOverflow: Long = 0,
    /** New dialog texts or settings screens after their lists were full. */
    val uiEventsOverflow: Long = 0,
    /**
     * Connections the app opened that TraceVeil answered on the phone (IPv4 TCP, and QUIC on UDP 443; see
     * [QuarantineSinkhole]), each with what its first message said: the TLS or QUIC server name, or the redacted head of
     * a plain-HTTP request. Nothing was forwarded. Their packets also appear in [blocked].
     */
    val connections: List<FlowRecord> = emptyList(),
    /** true when this run recorded [connections]; null for reports from builds that did not (unknown, not "none"). */
    val connectionsRead: Boolean? = null,
)

/**
 * Thread-safe, bounded accumulator for one quarantine run. Repeats are merged into counts: one row per name, per
 * (protocol, address, port), per dialog text and per settings screen kind. When a list is full, new keys are counted
 * as overflow instead of growing memory.
 */
class QuarantineRecorder(
    private val resolverAddresses: Set<String>,
    private val maxDomains: Int = 1_000,
    private val maxDestinations: Int = 1_000,
    private val maxUiEvents: Int = 100,
) {
    private val lock = Any()
    private val domains = LinkedHashMap<String, DomainAttempt>()
    private val blocked = LinkedHashMap<Triple<AttemptProtocol, String, Int?>, BlockedAttempt>()
    private val dialogs = LinkedHashMap<String, PermissionDialogSeen>()
    private val settings = LinkedHashMap<SensitiveSettings, SettingsScreenSeen>()
    private var targetOnScreen: Long? = null
    private var dnsQueries = 0L
    private var blockedPackets = 0L
    private var controlPackets = 0L
    private var unreadable = 0L
    private var domainsOverflow = 0L
    private var destinationsOverflow = 0L
    private var uiOverflow = 0L

    fun recordDns(tNanos: Long, hostname: String, queryType: Int, resolver: String) = synchronized(lock) {
        dnsQueries++
        val old = domains[hostname]
        if (old == null) {
            if (domains.size >= maxDomains) { domainsOverflow++; return@synchronized }
            domains[hostname] = DomainAttempt(hostname, listOf(queryType), listOf(resolver), tNanos, tNanos, 1)
        } else {
            domains[hostname] = old.copy(
                queryTypes = if (queryType in old.queryTypes || old.queryTypes.size >= MAX_SMALL_SET) old.queryTypes else old.queryTypes + queryType,
                resolvers = if (resolver in old.resolvers || old.resolvers.size >= MAX_SMALL_SET) old.resolvers else old.resolvers + resolver,
                firstNanos = minOf(old.firstNanos, tNanos),
                lastNanos = maxOf(old.lastNanos, tNanos),
                count = old.count + 1,
            )
        }
    }

    /** A dropped tunnel packet; null when its headers could not be read. */
    fun recordPacket(tNanos: Long, header: PacketHeader?) = synchronized(lock) {
        when {
            header == null -> unreadable++
            header.networkControl -> controlPackets++
            else -> {
                blockedPackets++
                val key = Triple(header.protocol, header.destination, header.destinationPort)
                val old = blocked[key]
                if (old == null) {
                    if (blocked.size >= maxDestinations) { destinationsOverflow++; return@synchronized }
                    val role = if (header.destination in resolverAddresses) DestinationRole.PHONE_RESOLVER else DestinationRole.APP_DESTINATION
                    blocked[key] = BlockedAttempt(
                        header.protocol, header.protocolNumber, header.ipVersion, header.destination, header.destinationPort,
                        header.scope, role, tNanos, tNanos, 1,
                    )
                } else {
                    blocked[key] = old.copy(firstNanos = minOf(old.firstNanos, tNanos), lastNanos = maxOf(old.lastNanos, tNanos), packets = old.packets + 1)
                }
            }
        }
    }

    /** Returns true when this dialog text is new to the run. */
    fun recordPermissionDialog(tNanos: Long, packageName: String, text: String, namesTarget: Boolean): Boolean = synchronized(lock) {
        val old = dialogs[text]
        if (old != null) {
            dialogs[text] = old.copy(lastNanos = maxOf(old.lastNanos, tNanos), timesShown = old.timesShown + 1)
            return@synchronized false
        }
        if (dialogs.size >= maxUiEvents) { uiOverflow++; return@synchronized false }
        dialogs[text] = PermissionDialogSeen(packageName, text, namesTarget, tNanos, tNanos, 1)
        true
    }

    /** Returns true when this kind of settings screen is new to the run. */
    fun recordSettingsScreen(tNanos: Long, kind: SensitiveSettings, title: String): Boolean = synchronized(lock) {
        val old = settings[kind]
        if (old != null) {
            settings[kind] = old.copy(lastNanos = maxOf(old.lastNanos, tNanos), timesShown = old.timesShown + 1)
            return@synchronized false
        }
        if (settings.size >= maxUiEvents) { uiOverflow++; return@synchronized false }
        settings[kind] = SettingsScreenSeen(kind, title, tNanos, tNanos, 1)
        true
    }

    fun recordTargetOnScreen(tNanos: Long) = synchronized(lock) {
        targetOnScreen = minOf(targetOnScreen ?: tNanos, tNanos)
    }

    fun snapshot(): QuarantineObservations = synchronized(lock) {
        QuarantineObservations(
            domains = domains.values.toList(),
            blocked = blocked.values.toList(),
            permissionDialogs = dialogs.values.toList(),
            settingsScreens = settings.values.toList(),
            targetOnScreenNanos = targetOnScreen,
            dnsQueries = dnsQueries,
            blockedPackets = blockedPackets,
            networkControlPackets = controlPackets,
            unreadablePackets = unreadable,
            domainsOverflow = domainsOverflow,
            destinationsOverflow = destinationsOverflow,
            uiEventsOverflow = uiOverflow,
        )
    }

    private companion object {
        const val MAX_SMALL_SET = 8
    }
}
