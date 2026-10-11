package com.dpdpxray.app.audit

import com.dpdpxray.core.model.WatchSpan

/**
 * The stop switch of the running background watch. The notification's Stop and the live screen's "Stop and save" set it;
 * the watch then ends early and keeps what it saw. Reset when a watch starts.
 */
object WatchControl {
    @Volatile private var stop = false

    val stopRequested: Boolean get() = stop

    fun requestStop() { stop = true }

    fun reset() { stop = false }
}

/** What the screen showed of the watched app at one moment. */
enum class ScreenSample {
    /** Not on screen: another app or the lock screen was in front. */
    OFF,

    /** A window of the app was on screen. */
    ON,

    /** The screen could not be read, so the app may have been on screen. */
    UNKNOWN,
}

/**
 * The background watch itself: the capture keeps running for the planned length while TraceVeil only looks, never opens,
 * restarts or taps the app. It ends early when the person stops it or the capture stops, and keeps what was seen.
 *
 * Every few seconds it reads whether the app is on screen. A lookup counts as background only between two readings that
 * both found the app off screen, so a moment the app was (or may have been) on screen is never called background.
 *
 * Time comes from [clockNanos] (the capture's clock, which keeps counting while the phone sleeps), and the loop waits in
 * short steps, so a phone that slept through the end finishes the watch as soon as it wakes. Pure apart from its lambdas.
 */
internal class WatchLoop(
    private val clockNanos: () -> Long,
    private val pause: suspend (Long) -> Unit,
    private val stopRequested: () -> Boolean,
    private val captureRunning: () -> Boolean,
    private val screen: suspend () -> ScreenSample,
    /** Called about once per [tickMs] with the time left, for the progress line and the notification. */
    private val onTick: suspend (remainingMs: Long) -> Unit,
    private val tickMs: Long = 1_000,
    private val sampleEveryMs: Long = 5_000,
) {
    enum class End { FINISHED, STOPPED_BY_PERSON, CAPTURE_STOPPED }

    /**
     * [firstOffNanos]: when the app was first seen off screen (the start of its background time), null when that never
     * happened. [onScreen]: the stretches after it when the app was on screen or may have been.
     */
    data class Outcome(val startNanos: Long, val endNanos: Long, val end: End, val firstOffNanos: Long?, val onScreen: List<WatchSpan>) {
        val watchedMs: Long get() = (endNanos - startNanos) / 1_000_000
    }

    suspend fun run(plannedMs: Long): Outcome {
        require(plannedMs > 0)
        val start = clockNanos()
        val end = start + plannedMs * 1_000_000
        val samples = mutableListOf<Pair<Long, ScreenSample>>()
        var lastSample: Long? = null
        var ended = End.FINISHED
        while (true) {
            val now = clockNanos()
            if (lastSample == null || now - lastSample >= sampleEveryMs * 1_000_000) {
                samples += now to screen()
                lastSample = now
            }
            if (now >= end) break
            if (stopRequested()) { ended = End.STOPPED_BY_PERSON; break }
            if (!captureRunning()) { ended = End.CAPTURE_STOPPED; break }
            val left = (end - now + 999_999) / 1_000_000
            onTick(left)
            pause(minOf(tickMs, left))
        }
        val stop = clockNanos()
        if (lastSample != stop) samples += stop to screen()
        return Outcome(start, stop, ended, firstOff(samples), spans(samples, stop))
    }

    companion object {
        fun firstOff(samples: List<Pair<Long, ScreenSample>>): Long? = samples.firstOrNull { it.second == ScreenSample.OFF }?.first

        /**
         * The stretches after the first off-screen reading when the app was on screen or may have been. A run of readings
         * that were not OFF spans from the reading before it to the reading after it (or [end]), so the time between two
         * readings counts as off screen only when both readings were OFF.
         */
        fun spans(samples: List<Pair<Long, ScreenSample>>, end: Long): List<WatchSpan> {
            val first = samples.indexOfFirst { it.second == ScreenSample.OFF }
            if (first < 0) return emptyList()
            val out = mutableListOf<WatchSpan>()
            var i = first + 1
            while (i < samples.size) {
                if (samples[i].second == ScreenSample.OFF) { i++; continue }
                var j = i
                while (j + 1 < samples.size && samples[j + 1].second != ScreenSample.OFF) j++
                val from = samples[i - 1].first
                val to = if (j + 1 < samples.size) samples[j + 1].first else end
                if (to > from) out += WatchSpan(from, to)
                i = j + 1
            }
            return out
        }
    }
}
