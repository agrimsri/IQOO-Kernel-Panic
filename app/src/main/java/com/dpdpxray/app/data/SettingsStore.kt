package com.dpdpxray.app.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class XraySettings(
    val firstPartyDomains: Set<String> = emptySet(),
    val stageMode: Boolean = false,
    val fastDemo: Boolean = false,
    val autoLoadModel: Boolean = true,
    /** Read APK Guard new-install alerts aloud with an offline voice. Off unless the user switches it on. */
    val speakApkGuardAlerts: Boolean = false,
    /** Opt-in for the experimental "Hey TraceVeil" wake word; it reveals the controls but never starts the microphone. */
    val heyTraceVeil: Boolean = false,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("xray_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<XraySettings> = _settings.asStateFlow()

    private fun read() = XraySettings(
        firstPartyDomains = prefs.getStringSet("first_party", emptySet()).orEmpty(),
        stageMode = prefs.getBoolean("stage_mode", false),
        fastDemo = prefs.getBoolean("fast_demo", false),
        autoLoadModel = prefs.getBoolean("auto_load_model", true),
        speakApkGuardAlerts = prefs.getBoolean("speak_apk_guard_alerts", false),
        heyTraceVeil = prefs.getBoolean("hey_traceveil", false),
    )

    fun update(transform: (XraySettings) -> XraySettings) {
        val next = transform(_settings.value)
        prefs.edit {
            putStringSet("first_party", next.firstPartyDomains)
            putBoolean("stage_mode", next.stageMode)
            putBoolean("fast_demo", next.fastDemo)
            putBoolean("auto_load_model", next.autoLoadModel)
            putBoolean("speak_apk_guard_alerts", next.speakApkGuardAlerts)
            putBoolean("hey_traceveil", next.heyTraceVeil)
        }
        _settings.value = next
    }
}
