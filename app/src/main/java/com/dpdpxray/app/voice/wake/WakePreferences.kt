package com.dpdpxray.app.voice.wake

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The Hey TraceVeil choices. [duration] is null until the user picks one; then [WakeDuration.default] applies. */
data class WakePrefs(val duration: WakeDuration? = null, val sensitivity: WakeSensitivity = WakeSensitivity.STRICT)

/**
 * Stored in its own preferences file so the wake-word choices never collide with the app's main settings. Read once
 * per process and shared by the Settings card and the listening service.
 */
class WakePreferences private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<WakePrefs> = _state.asStateFlow()

    private fun read() = WakePrefs(
        duration = WakeDuration.fromName(prefs.getString(KEY_DURATION, null)),
        sensitivity = WakeSensitivity.fromName(prefs.getString(KEY_SENSITIVITY, null)) ?: WakeSensitivity.STRICT,
    )

    fun setDuration(value: WakeDuration) = update { it.copy(duration = value) }

    fun setSensitivity(value: WakeSensitivity) = update { it.copy(sensitivity = value) }

    private fun update(transform: (WakePrefs) -> WakePrefs) {
        val next = transform(_state.value)
        prefs.edit {
            putString(KEY_DURATION, next.duration?.name)
            putString(KEY_SENSITIVITY, next.sensitivity.name)
        }
        _state.value = next
    }

    companion object {
        private const val FILE = "hey_traceveil"
        private const val KEY_DURATION = "session_length"
        private const val KEY_SENSITIVITY = "sensitivity"

        @Volatile private var instance: WakePreferences? = null

        fun get(context: Context): WakePreferences =
            instance ?: synchronized(this) { instance ?: WakePreferences(context).also { instance = it } }
    }
}
