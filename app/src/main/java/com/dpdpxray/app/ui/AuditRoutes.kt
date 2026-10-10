package com.dpdpxray.app.ui

object AuditRoutes {
    const val VOICE = "voice/{id}"
    fun voice(auditId: String): String {
        require(auditId.matches(Regex("[A-Za-z0-9_-]+"))) { "A saved audit id is required" }
        return VOICE.replace("{id}", auditId)
    }
}
