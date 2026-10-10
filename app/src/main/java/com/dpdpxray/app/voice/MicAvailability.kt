package com.dpdpxray.app.voice

import android.speech.SpeechRecognizer
import com.dpdpxray.core.copilot.CopilotLanguage

sealed interface MicState {
    data object Ready : MicState
    data object NeedsPermission : MicState
    data class Unavailable(val reason: String) : MicState
}

/** Decides whether push-to-talk can run, and if not, says why. Question chips work in every state. */
object MicAvailability {
    private const val CHIPS = "Tap a question instead."

    fun evaluate(
        sdkInt: Int,
        permissionDeclared: Boolean,
        permissionGranted: Boolean,
        onDeviceAvailable: Boolean,
        /** On-device packs reported by the recognizer; null when the phone cannot say (Android 12) or the check failed. */
        installedLanguages: List<String>?,
        language: CopilotLanguage,
    ): MicState = when {
        sdkInt < 31 -> MicState.Unavailable("On-device speech recognition needs Android 12 or later. $CHIPS")
        !permissionDeclared -> MicState.Unavailable("Microphone access is not part of this build yet. $CHIPS")
        !onDeviceAvailable -> MicState.Unavailable("This phone has no on-device speech recognizer, and TraceVeil never sends audio to the cloud. $CHIPS")
        !permissionGranted -> MicState.NeedsPermission
        installedLanguages != null && installedLanguages.none { it.substringBefore('-').equals(language.tag.substringBefore('-'), true) } ->
            MicState.Unavailable("The ${name(language)} on-device speech pack is not installed. You can add it in the phone's speech settings. $CHIPS")
        else -> MicState.Ready
    }

    fun denied(): MicState = MicState.Unavailable("Microphone permission was not granted. $CHIPS")

    fun errorReason(code: Int): String = SpeechErrors.reason(code)

    private fun name(l: CopilotLanguage) = if (l == CopilotLanguage.HI) "Hindi" else "English"
}
