package com.dpdpxray.core.consent

/**
 * Recognises Android's own "app stopped" and "app not responding" dialogs (shown by the system, not by the app) for the
 * audited app, from the dialog's visible text. The app's name must be in the same line as the message, so another
 * app's crash during the audit is never blamed on the audited one. English and Hindi system wording; a phone brand
 * that rewords the dialog may not be recognised, which leaves the crash unrecorded (unknown), never invented.
 */
object CrashDialog {
    enum class Kind { STOPPED, NOT_RESPONDING }

    private val STOPPED = listOf("keeps stopping", "has stopped", "keeps crashing", "बार-बार बंद हो रहा है", "बंद हो गया है", "रुक गया है")
    private val NOT_RESPONDING = listOf("isn't responding", "isnt responding", "is not responding", "not responding", "जवाब नहीं दे रहा")

    /** The kind of system dialog naming [appLabel] among [texts], or null. */
    fun detect(texts: List<String?>, appLabel: String): Kind? {
        val label = appLabel.trim().lowercase()
        if (label.isEmpty()) return null
        for (raw in texts) {
            val t = raw?.lowercase()?.replace('’', '\'') ?: continue
            if (!t.contains(label)) continue
            if (NOT_RESPONDING.any { t.contains(it) }) return Kind.NOT_RESPONDING
            if (STOPPED.any { t.contains(it) }) return Kind.STOPPED
        }
        return null
    }
}
