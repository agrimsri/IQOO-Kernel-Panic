package com.dpdpxray.app.data

import android.content.Context
import android.content.SharedPreferences
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
    /** First-run onboarding was finished or skipped. It stays reachable from Home ("How it works"). */
    val onboardingDone: Boolean = false,
    /** Read guidance steps aloud with an installed offline voice. Off unless the person turns it on. */
    val spokenGuidance: Boolean = false,
    /** "en" or "hi": language of guidance text and speech. */
    val guideLanguage: String = "en",
    /** Everyday (USER) or Developer Home; a missing value reads as DEVELOPER so existing installs see no change. */
    val audience: Audience = Audience.DEFAULT,
)

/** Read side of the stored settings, so the mapping can be tested without Android. */
internal interface SettingsKv {
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getString(key: String, default: String): String
    fun getStringSet(key: String): Set<String>
}

/** Write side of the stored settings. */
internal interface SettingsSink {
    fun putBoolean(key: String, value: Boolean)
    fun putString(key: String, value: String)
    fun putStringSet(key: String, value: Set<String>)
}

/** Key names and defaults in one place; a missing key always reads as the data-class default. */
internal object SettingsCodec {
    private val defaults = XraySettings()

    fun read(kv: SettingsKv) = XraySettings(
        firstPartyDomains = kv.getStringSet("first_party"),
        stageMode = kv.getBoolean("stage_mode", defaults.stageMode),
        fastDemo = kv.getBoolean("fast_demo", defaults.fastDemo),
        autoLoadModel = kv.getBoolean("auto_load_model", defaults.autoLoadModel),
        speakApkGuardAlerts = kv.getBoolean("speak_apk_guard_alerts", defaults.speakApkGuardAlerts),
        heyTraceVeil = kv.getBoolean("hey_traceveil", defaults.heyTraceVeil),
        onboardingDone = kv.getBoolean("onboarding_done", defaults.onboardingDone),
        spokenGuidance = kv.getBoolean("spoken_guidance", defaults.spokenGuidance),
        guideLanguage = kv.getString("guide_language", defaults.guideLanguage),
        audience = Audience.of(kv.getString("audience", defaults.audience.code)),
    )

    fun write(s: XraySettings, sink: SettingsSink) {
        sink.putStringSet("first_party", s.firstPartyDomains)
        sink.putBoolean("stage_mode", s.stageMode)
        sink.putBoolean("fast_demo", s.fastDemo)
        sink.putBoolean("auto_load_model", s.autoLoadModel)
        sink.putBoolean("speak_apk_guard_alerts", s.speakApkGuardAlerts)
        sink.putBoolean("hey_traceveil", s.heyTraceVeil)
        sink.putBoolean("onboarding_done", s.onboardingDone)
        sink.putBoolean("spoken_guidance", s.spokenGuidance)
        sink.putString("guide_language", s.guideLanguage)
        sink.putString("audience", s.audience.code)
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("xray_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<XraySettings> = _settings.asStateFlow()

    private fun read() = SettingsCodec.read(object : SettingsKv {
        override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
        override fun getString(key: String, default: String) = prefs.getString(key, default) ?: default
        override fun getStringSet(key: String) = prefs.getStringSet(key, emptySet()).orEmpty()
    })

    fun update(transform: (XraySettings) -> XraySettings) {
        val next = transform(_settings.value)
        prefs.edit { SettingsCodec.write(next, sink(this)) }
        _settings.value = next
    }

    private fun sink(editor: SharedPreferences.Editor) = object : SettingsSink {
        override fun putBoolean(key: String, value: Boolean) { editor.putBoolean(key, value) }
        override fun putString(key: String, value: String) { editor.putString(key, value) }
        override fun putStringSet(key: String, value: Set<String>) { editor.putStringSet(key, value) }
    }
}
