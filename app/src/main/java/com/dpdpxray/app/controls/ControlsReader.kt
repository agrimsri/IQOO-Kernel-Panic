package com.dpdpxray.app.controls

import android.content.Context
import android.content.pm.PackageManager
import com.dpdpxray.app.apkguard.ApkInstallWatcher
import com.dpdpxray.app.data.XraySettings
import com.dpdpxray.app.trust.TrustReader
import com.dpdpxray.app.voice.ApiEnv

/** What only Android can say, read off the main thread each time a screen showing the inventory resumes. */
internal data class PhoneReadings(
    val requested: Set<String>,
    val notificationsOn: Boolean?,
    val micGranted: Boolean?,
    val installUnknownAllowed: Boolean?,
    val onlineVoiceSetUp: Boolean,
)

/**
 * Reads the inventory's inputs from the code that already owns them: the manifest's permissions (TrustReader), the
 * notification check Auto-guard uses and api.env. Read-only: it never asks for a permission or changes a setting. The
 * VPN, consent watcher and Shizuku are read by the preflight for the Setup sheet, not here.
 */
internal object ControlsReader {
    fun read(ctx: Context): PhoneReadings {
        val requested = runCatching { TrustReader.permissions(ctx).map { it.name }.toSet() }.getOrDefault(emptySet())
        return PhoneReadings(
            requested = requested,
            notificationsOn = runCatching { ApkInstallWatcher.notificationsAllowed(ctx) }.getOrNull(),
            micGranted = runCatching { ctx.checkSelfPermission(ControlsInventory.MICROPHONE) == PackageManager.PERMISSION_GRANTED }.getOrNull(),
            installUnknownAllowed = if (ControlsInventory.INSTALL_PACKAGES in requested) runCatching { ctx.packageManager.canRequestPackageInstalls() }.getOrNull() else null,
            onlineVoiceSetUp = runCatching { ApiEnv.setUp(ctx) }.getOrDefault(false),
        )
    }

    fun readings(
        settings: XraySettings,
        phone: PhoneReadings,
        wakeListening: Boolean,
        onlineVoiceAllowed: Boolean,
        runnerOn: Boolean,
        savedChecks: Int,
        checkRunning: Boolean,
    ) = ControlReadings(
        settings = settings,
        requestedPermissions = phone.requested,
        notificationsOn = phone.notificationsOn,
        micGranted = phone.micGranted,
        installUnknownAllowed = phone.installUnknownAllowed,
        wakeListening = wakeListening,
        onlineVoiceInBuild = ApiEnv.ONLINE_VOICE_AVAILABLE,
        onlineVoiceSetUp = phone.onlineVoiceSetUp,
        onlineVoiceAllowed = onlineVoiceAllowed,
        runnerOn = runnerOn,
        savedChecks = savedChecks,
        checkRunning = checkRunning,
    )
}
