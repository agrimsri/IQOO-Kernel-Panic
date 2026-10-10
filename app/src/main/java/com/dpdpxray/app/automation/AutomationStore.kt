package com.dpdpxray.app.automation

import android.content.Context
import androidx.core.content.edit
import com.dpdpxray.core.automation.AutomationContract
import com.dpdpxray.core.automation.PairingTokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Developer automation settings (TraceVeil Runner). Off by default; the pairing token exists only after the person
 * switches automation on; the reset allowlist starts empty and holds only apps the person picked.
 */
data class AutomationSettings(
    val enabled: Boolean = false,
    val token: String? = null,
    /** Test apps whose local data the runner may clear before each plan. */
    val resetAllowlist: Set<String> = emptySet(),
) {
    /** Switching on creates a token if there is none; switching off keeps it, so a CI secret survives off/on. */
    fun switchedOn(on: Boolean, newToken: () -> String): AutomationSettings =
        if (on) copy(enabled = true, token = token ?: newToken()) else copy(enabled = false)

    /** Regenerate: the old token stops working at once. */
    fun withNewToken(newToken: () -> String): AutomationSettings = copy(token = newToken())

    fun allowing(pkg: String, ownPackage: String): AutomationSettings =
        if (AutomationContract.isValidPackage(pkg) && pkg != ownPackage) copy(resetAllowlist = resetAllowlist + pkg) else this

    fun disallowing(pkg: String): AutomationSettings = copy(resetAllowlist = resetAllowlist - pkg)
}

/** Kept in its own private preferences file, apart from the general settings (app backups are disabled). */
class AutomationStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("developer_automation", Context.MODE_PRIVATE)
    private val ownPackage = context.packageName
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AutomationSettings> = _settings.asStateFlow()

    private fun read() = AutomationSettings(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        token = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotEmpty() },
        resetAllowlist = prefs.getStringSet(KEY_ALLOWLIST, emptySet()).orEmpty().toSet(),
    )

    @Synchronized
    private fun update(transform: (AutomationSettings) -> AutomationSettings) {
        val next = transform(_settings.value)
        prefs.edit {
            putBoolean(KEY_ENABLED, next.enabled)
            putString(KEY_TOKEN, next.token)
            putStringSet(KEY_ALLOWLIST, next.resetAllowlist)
        }
        _settings.value = next
    }

    fun setEnabled(on: Boolean) = update { it.switchedOn(on) { PairingTokens.generate() } }
    fun regenerateToken() = update { it.withNewToken { PairingTokens.generate() } }
    fun allow(pkg: String) = update { it.allowing(pkg, ownPackage) }
    fun disallow(pkg: String) = update { it.disallowing(pkg) }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_TOKEN = "pairing_token"
        private const val KEY_ALLOWLIST = "reset_allowlist"

        @Volatile private var instance: AutomationStore? = null

        fun get(context: Context): AutomationStore = instance ?: synchronized(this) {
            instance ?: AutomationStore(context.applicationContext).also { instance = it }
        }
    }
}
