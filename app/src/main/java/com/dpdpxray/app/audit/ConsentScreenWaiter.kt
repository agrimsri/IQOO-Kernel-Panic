package com.dpdpxray.app.audit

import com.dpdpxray.core.model.ConsentEvent

internal class ConsentScreenWaiter(
    private val probe: suspend () -> ConsentEvent?,
    private val clockMs: () -> Long,
    private val pause: suspend (Long) -> Unit,
    private val intervalMs: Long = 500,
) {
    suspend fun await(timeoutMs: Long): ConsentEvent? {
        require(timeoutMs >= 0 && intervalMs > 0)
        val started = clockMs()
        while (true) {
            probe()?.let { return it }
            val remaining = timeoutMs - (clockMs() - started)
            if (remaining <= 0) return null
            pause(minOf(intervalMs, remaining))
        }
    }
}
