package com.dpdpxray.app.apkguard

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import com.dpdpxray.app.autoguard.AutoGuardStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/**
 * Android's PackageInstaller behind [InstallerGateway]. Every session requires the person's confirmation in Android's
 * own installer (USER_ACTION_REQUIRED on Android 12+), is marked as a downloaded file (so Android's protections for
 * sideloaded apps, such as restricted settings, still apply on Android 13+), and never asks to own future updates.
 */
class PlatformInstaller(context: Context) : InstallerGateway {
    private val app = context.applicationContext
    private val installer: PackageInstaller get() = app.packageManager.packageInstaller

    override fun canRequestInstalls(): Boolean = app.packageManager.canRequestPackageInstalls()

    override fun createSession(packageName: String, sizeBytes: Long): Int {
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
            if (sizeBytes > 0) setSize(sizeBytes)
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
        }
        return installer.createSession(params)
    }

    override fun write(sessionId: Int, apk: File) {
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                session.fsync(out)
            }
        }
    }

    override fun commit(sessionId: Int, checkKey: String) {
        val result = Intent(app, InstallResultReceiver::class.java)
            .setAction(InstallResultReceiver.ACTION)
            .setPackage(app.packageName)
            .putExtra(InstallResultReceiver.EXTRA_KEY, checkKey)
        // Android adds the status extras, so the PendingIntent must be mutable; it is explicit (our own receiver).
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pending = PendingIntent.getBroadcast(app, sessionId, result, flags)
        installer.openSession(sessionId).use { it.commit(pending.intentSender) }
    }

    override fun abandon(sessionId: Int) = installer.abandonSession(sessionId)
}

/** Where each check's "Install anyway" hand-off stands, for the check screen. In memory only. */
object InstallSessions {
    /** [confirm] is Android's own confirmation screen, waiting to be shown while TraceVeil is in front. */
    data class Entry(val state: InstallState, val confirm: Intent? = null)

    private val entries = MutableStateFlow<Map<String, Entry>>(emptyMap())
    val state: StateFlow<Map<String, Entry>> = entries.asStateFlow()

    /** Installs handed over in this process, so the new-install alert does not repeat a check the person just saw. */
    val expected = ExpectedInstalls()

    fun set(key: String, state: InstallState, confirm: Intent? = null) = entries.update { it + (key to Entry(state, confirm)) }

    /** Android's confirmation screen for [key], once; null when there is none (or it was already shown). */
    fun takeConfirmation(key: String): Intent? {
        var taken: Intent? = null
        entries.update { map ->
            val entry = map[key] ?: return@update map
            taken = entry.confirm
            if (taken == null) map else map + (key to entry.copy(confirm = null))
        }
        return taken
    }

    fun clear(key: String) = entries.update { it - key }
}

/**
 * Receives the result of an "Install anyway" session from Android's installer. Not exported: only TraceVeil's own
 * PendingIntent reaches it. It records the outcome for the check screen; when Android needs the person's confirmation it
 * keeps Android's confirmation screen for the check screen to show (an activity in front may start it; a receiver should
 * not). After a successful install it deletes TraceVeil's checked copy and records the install as already alerted.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
        val state = InstallOutcome.from(status, pkg, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        val confirm = if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) confirmation(intent) else null
        InstallSessions.set(key, state, confirm)
        if (state is InstallState.Installed) {
            ApkFileChecks.release(key)
            val installed = pkg ?: return
            ApkInspector.firstInstallTime(context, installed)?.let { AutoGuardStore(context).claim(installed, it) }
        }
    }

    private fun confirmation(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)

    companion object {
        const val ACTION = "com.dpdpxray.app.apkguard.INSTALL_RESULT"
        const val EXTRA_KEY = "com.dpdpxray.app.apkguard.CHECK_KEY"
    }
}
