package com.dpdpxray.app.voice

import android.content.Context
import com.dpdpxray.app.BuildConfig
import java.io.File

/**
 * Demo-only cloud voice switch. Read from `api.env` in the app's external files folder (next to the models), pushed by
 * the presenter over ADB, so the key is never compiled into the APK or committed:
 *   adb push api.env /sdcard/Android/data/com.dpdpxray.app/files/
 * Off unless the file says `USE_API=true` and carries a key. It only enables Sarvam text-to-speech; speech recognition
 * and answers always stay on the phone.
 */
class ApiConfig internal constructor(val useApi: Boolean, val sarvamKey: String?) {
    override fun toString() = "ApiConfig(useApi=$useApi, sarvamKey=${if (sarvamKey == null) "absent" else "present"})"

    companion object {
        /** The online voice is off: what a build without it always uses. */
        val OFF = ApiConfig(useApi = false, sarvamKey = null)
    }
}

object ApiEnv {
    const val FILE_NAME = "api.env"

    /**
     * Whether this build includes the optional online voice at all (Gradle's ONLINE_VOICE_AVAILABLE: on in debug builds,
     * off in release builds unless `-Ptraceveil.onlineVoice=true`). Without it `api.env` is never read.
     */
    val ONLINE_VOICE_AVAILABLE: Boolean = BuildConfig.ONLINE_VOICE_AVAILABLE

    /** The online voice as it applies now: off when the person switched it off ([OnlineVoiceSwitch]); then the file is not read. */
    fun load(context: Context): ApiConfig = load(ONLINE_VOICE_AVAILABLE && OnlineVoiceSwitch.get(context).allowed.value) { readFile(context) }

    /** Whether `api.env` on this phone turns the online voice on, before the person's own switch. */
    fun setUp(context: Context): Boolean =
        SpeechOutputChoice.choose(load(ONLINE_VOICE_AVAILABLE) { readFile(context) }) == SpeechOutputChoice.ONLINE

    private fun readFile(context: Context): String? =
        runCatching { File(context.getExternalFilesDir(null), FILE_NAME).takeIf { it.isFile }?.readText() }.getOrNull()

    /** [read] returns the file's text (null when absent); it is not called at all when the build has no online voice. */
    fun load(onlineVoiceAvailable: Boolean, read: () -> String?): ApiConfig = if (onlineVoiceAvailable) parse(read()) else ApiConfig.OFF

    fun parse(text: String?): ApiConfig {
        val values = text.orEmpty().lineSequence()
            .map { it.trim().removePrefix("export ").trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
            .associate { line -> line.substringBefore('=').trim() to line.substringAfter('=').trim().trim('"', '\'') }
        val key = values["SARVAM_API_KEY"]?.takeIf { it.isNotBlank() }
        return ApiConfig(useApi = values["USE_API"].equals("true", ignoreCase = true) && key != null, sarvamKey = key)
    }
}
