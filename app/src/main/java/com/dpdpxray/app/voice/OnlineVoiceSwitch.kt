package com.dpdpxray.app.voice

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The person's own off switch for the online voice (Settings › Permissions & background). It only ever narrows what
 * `api.env` allows: with it off, [ApiEnv.load] reports the online voice as off and the key file is not read, so spoken
 * answers use the phone's own voice and nothing is sent. On (allowed) unless switched off, so an install that was set up
 * for the online voice keeps it. Stored in its own private preferences file.
 */
class OnlineVoiceSwitch private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _allowed = MutableStateFlow(prefs.getBoolean(KEY_ALLOWED, true))
    val allowed: StateFlow<Boolean> = _allowed.asStateFlow()

    fun set(on: Boolean) {
        prefs.edit { putBoolean(KEY_ALLOWED, on) }
        _allowed.value = on
    }

    companion object {
        private const val FILE = "online_voice"
        private const val KEY_ALLOWED = "allowed"

        @Volatile private var instance: OnlineVoiceSwitch? = null

        fun get(context: Context): OnlineVoiceSwitch =
            instance ?: synchronized(this) { instance ?: OnlineVoiceSwitch(context).also { instance = it } }
    }
}
