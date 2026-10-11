package com.dpdpxray.app.webcheck

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * After a website check, asks this phone's own DNS resolver for the addresses of the servers the site contacted, so the
 * server map can place them. Only host names the site already contacted during the check are asked, at most [MAX_HOSTS],
 * once each, for at most [TOTAL_MS]; nothing else is sent. The browser may have used a different address (servers answer
 * by region and time), so these are addresses TraceVeil looked up after the check, not proof of where data went.
 */
object WebHostResolver {
    const val MAX_HOSTS = 120
    private const val TOTAL_MS = 15_000L
    private const val PARALLEL = 8
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun resolve(hosts: Collection<String>): Map<String, List<String>> {
        val found = ConcurrentHashMap<String, List<String>>()
        val gate = Semaphore(PARALLEL)
        val jobs = hosts.distinct().take(MAX_HOSTS).map { host ->
            scope.launch {
                gate.withPermit {
                    runCatching { InetAddress.getAllByName(host).mapNotNull { it.hostAddress }.distinct() }.getOrNull()
                        ?.takeIf { it.isNotEmpty() }?.let { found[host] = it }
                }
            }
        }
        // A slow name is left behind rather than holding the check: what arrived in time is used.
        withTimeoutOrNull(TOTAL_MS) { jobs.joinAll() }
        return HashMap(found)
    }
}
