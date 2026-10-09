package com.dpdpxray.app.voice

/** Identifies the current question; obsolete completions must not replace its answer or start speaking. */
internal class VoiceAnswerGate {
    private var version = 0L
    fun begin(): Long = ++version
    fun invalidate() { ++version }
    fun isCurrent(request: Long): Boolean = request == version
}
