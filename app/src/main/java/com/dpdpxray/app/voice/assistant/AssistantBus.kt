package com.dpdpxray.app.voice.assistant

import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.withTimeoutOrNull

/** What happened to screen work handed to TraceVeil's screen. */
sealed interface UiOutcome {
    data object Done : UiOutcome
    data class Failed(val reason: String?) : UiOutcome

    /** The screen is not part of this build. */
    data object Unavailable : UiOutcome

    /** TraceVeil was not on screen in time; the request waits briefly for the user to open it. */
    data class NotReached(val locked: Boolean = false) : UiOutcome

    /** An optional action was dropped because TraceVeil was not on screen. */
    data object Skipped : UiOutcome

    /** Another check, of [app], was already running, so nothing was started. */
    data class CheckRunning(val app: String) : UiOutcome
}

/**
 * One piece of screen work; [hindi] is the language the user spoke and [speak] whether replies are spoken, both for
 * the follow-up when a check this starts ends.
 */
class UiRequest internal constructor(val action: AssistantAction, val hindi: Boolean, val speak: Boolean, val createdAtMs: Long) {
    internal val result = CompletableDeferred<UiOutcome>()
}

/**
 * Hands screen work from the assistant (which may run in the wake-word service, in the background) to the host in
 * MainActivity, which performs it only while TraceVeil is started. At most one request waits at a time; a request
 * nobody performs within [PENDING_TTL_MS] is dropped, so an old command never runs by surprise later.
 */
object AssistantBus {
    const val PENDING_TTL_MS = 2 * 60_000L

    private val _pending = MutableStateFlow<UiRequest?>(null)
    internal val pending: StateFlow<UiRequest?> = _pending.asStateFlow()

    private val _routes = MutableStateFlow<Set<String>>(emptySet())
    /** Optional routes this build's navigation graph contains, published by the host. */
    val routes: StateFlow<Set<String>> = _routes.asStateFlow()

    /** True while the host is started, i.e. TraceVeil's screen can act right now. */
    @Volatile var hostActive: Boolean = false
        internal set

    internal fun publishRoutes(routes: Set<String>) {
        _routes.value = routes
    }

    /** Queues [action] and waits up to [waitMs] for the host to perform it. On a timeout the request stays queued. */
    suspend fun request(
        action: AssistantAction,
        hindi: Boolean,
        speak: Boolean,
        waitMs: Long,
        nowMs: Long = SystemClock.elapsedRealtime(),
    ): UiOutcome {
        val req = enqueue(action, hindi, speak, nowMs)
        return withTimeoutOrNull(waitMs) { req.result.await() } ?: UiOutcome.NotReached()
    }

    /** Queues [action] without waiting, replacing any older request. */
    fun enqueue(action: AssistantAction, hindi: Boolean, speak: Boolean, nowMs: Long = SystemClock.elapsedRealtime()): UiRequest {
        val req = UiRequest(action, hindi, speak, nowMs)
        _pending.getAndUpdate { req }?.result?.complete(UiOutcome.NotReached())
        return req
    }

    /** Takes the waiting request for the host to perform, unless it is stale. */
    internal fun take(req: UiRequest, nowMs: Long = SystemClock.elapsedRealtime()): UiRequest? {
        if (!_pending.compareAndSet(req, null)) return null
        if (nowMs - req.createdAtMs > PENDING_TTL_MS) {
            req.result.complete(UiOutcome.NotReached())
            return null
        }
        return req
    }

    internal fun complete(req: UiRequest, outcome: UiOutcome) {
        req.result.complete(outcome)
    }
}
