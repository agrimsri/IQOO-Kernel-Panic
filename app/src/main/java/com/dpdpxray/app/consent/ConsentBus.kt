package com.dpdpxray.app.consent

import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Hand-off between the accessibility service and the orchestrator. Only the armed package is watched. */
object ConsentBus {
    private val lock = Any()
    private var armGeneration = 0L
    val generation: Long get() = synchronized(lock) { armGeneration }

    /** Async screenshots must belong to the arm that requested them, even for the same target package. */
    fun emitForSession(generation: Long, event: ConsentEvent): Boolean = synchronized(lock) {
        if (_armed.value == null || generation != armGeneration) return@synchronized false
        append(event)
        true
    }
    private val _armed = MutableStateFlow<String?>(null)
    val armedPackage: StateFlow<String?> = _armed.asStateFlow()

    private val _events = MutableStateFlow<List<ConsentEvent>>(emptyList())
    val events: StateFlow<List<ConsentEvent>> = _events.asStateFlow()

    /** Becomes true when any accessibility event arrives from the armed app, i.e. it really is on screen. */
    private val _targetSeen = MutableStateFlow(false)
    val targetSeen: StateFlow<Boolean> = _targetSeen.asStateFlow()

    /** Screenshot/tree storage folder for the current session. */
    @Volatile var shotDir: java.io.File? = null

    /** The requested manual choice is guidance only, never evidence that the user made it. */
    @Volatile var expectedChoice: ConsentKind? = null

    @Volatile var unconfirmedDialogClosedAtNanos: Long? = null
        private set

    fun onConsentDialogClosed(tNanos: Long) = synchronized(lock) {
        if (_armed.value != null && !choiceMade) {
            unconfirmedDialogClosedAtNanos = tNanos
        }
    }

    val screenSeen: Boolean
        get() = _events.value.any { it.kind == ConsentKind.SCREEN_APPEARED || it.kind == ConsentKind.MANUAL_MARK }

    val choiceMade: Boolean
        get() = _events.value.any { it.kind == ConsentKind.ACCEPT || it.kind == ConsentKind.REJECT }

    fun arm(pkg: String, dir: java.io.File) = synchronized(lock) {
        armGeneration++
        _events.value = emptyList()
        _targetSeen.value = false
        expectedChoice = null
        unconfirmedDialogClosedAtNanos = null
        shotDir = dir.apply { mkdirs() }
        _armed.value = pkg
    }

    fun disarm() = synchronized(lock) {
        _armed.value = null
        expectedChoice = null
    }

    fun markTargetSeen() = synchronized(lock) {
        if (_armed.value != null && !_targetSeen.value) _targetSeen.value = true
    }

    /**
     * Drops duplicates: the same kind within 1.5 s (e.g. our own programmatic tap plus the app's click event). Two
     * different permission dialogs in quick succession are both kept; only a repeat of the same dialog text is dropped.
     */
    fun emit(event: ConsentEvent) = synchronized(lock) {
        if (_armed.value != null) append(event)
    }

    private fun append(event: ConsentEvent) {
        val list = _events.value
        val dup = list.any {
            it.kind == event.kind && kotlin.math.abs(it.tNanos - event.tNanos) < 1_500_000_000L &&
                (event.kind != ConsentKind.PERMISSION_DIALOG || it.nodeText == event.nodeText)
        }
        if (!dup) _events.value = list + event
    }
}
