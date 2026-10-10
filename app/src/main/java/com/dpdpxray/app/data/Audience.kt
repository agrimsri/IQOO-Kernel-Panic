package com.dpdpxray.app.data

/**
 * Who TraceVeil's front door is for, chosen at first launch and switchable later. One app and one engine either way:
 * the mode changes Home and the check setup's defaults, never what a check records or how it is scored.
 */
enum class Audience(val code: String) {
    /** "I use apps": protect my phone. */
    USER("user"),

    /** "I build apps": test my app. */
    DEVELOPER("developer");

    companion object {
        /** Installs from before modes existed have no stored value; they stay on the developer Home they know. */
        val DEFAULT = DEVELOPER

        fun of(code: String?): Audience = entries.firstOrNull { it.code == code } ?: DEFAULT
    }
}
