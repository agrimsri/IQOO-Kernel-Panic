package com.dpdpxray.app.trust

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.TrafficStats
import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.dpdpxray.app.apkguard.ApkFactsMapper
import com.dpdpxray.app.capture.CaptureBus
import com.dpdpxray.app.capture.CaptureState
import com.dpdpxray.app.control.DeviceChecks
import com.dpdpxray.app.voice.ApiEnv
import com.dpdpxray.app.voice.SpeechOutputChoice
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** TraceVeil's own network counters since the phone started. Null = Android did not report it (never shown as zero). */
data class OwnTraffic(val rxBytes: Long?, val txBytes: Long?, val uptimeMillis: Long)

/** One signing certificate of this install: lower-case SHA-256 and its subject, for the debug-key note. */
data class SignerCert(val sha256: String, val subject: String?)

/** What this install says about itself, for comparing with the APK someone was given. */
data class OwnIdentity(val versionName: String?, val versionCode: Long, val signers: List<SignerCert>, val apkFiles: List<File>)

/**
 * Reads the trust screen's live facts about TraceVeil itself from Android. Read-only: it never prepares the VPN, asks
 * for a permission or changes a setting. The file hash is slow for a large APK, so callers run it off the main thread.
 */
object TrustReader {
    private fun packageInfo(context: Context, flags: Int): PackageInfo? = runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        else @Suppress("DEPRECATION") pm.getPackageInfo(context.packageName, flags)
    }.getOrNull()

    /** Every permission TraceVeil requests (including ones added by its libraries), with Android's current grant state. */
    fun permissions(context: Context): List<HeldPermission> {
        val info = packageInfo(context, PackageManager.GET_PERMISSIONS) ?: return emptyList()
        val names = info.requestedPermissions.orEmpty()
        val flags = info.requestedPermissionsFlags
        return names.mapIndexed { i, name ->
            HeldPermission(name, flags?.getOrNull(i)?.let { it and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0 })
        }
    }

    fun onlineVoiceOn(context: Context): Boolean = SpeechOutputChoice.choose(ApiEnv.load(context)) == SpeechOutputChoice.ONLINE

    /** Whether Android may back up TraceVeil's data (the manifest's allowBackup), read from this install. */
    fun backupAllowed(context: Context): Boolean? =
        runCatching { context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0 }.getOrNull()

    fun accessibilityOn(context: Context): Boolean = DeviceChecks.accessibilityOn(context)

    /** The package a check's VPN is routing right now, or null. Read from TraceVeil's own state; never prepares a VPN. */
    fun vpnRoutingNow(): String? = (CaptureBus.state.value as? CaptureState.Running)?.targetPackage

    fun traffic(): OwnTraffic {
        val uid = Process.myUid()
        fun read(v: Long) = v.takeIf { it != TrafficStats.UNSUPPORTED.toLong() && it >= 0 }
        return OwnTraffic(read(TrafficStats.getUidRxBytes(uid)), read(TrafficStats.getUidTxBytes(uid)), SystemClock.elapsedRealtime())
    }

    fun identity(context: Context): OwnIdentity {
        val info = packageInfo(context, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = info?.signingInfo?.apkContentsSigners.orEmpty().map { sig ->
            val subject = runCatching {
                (CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(sig.toByteArray())) as X509Certificate)
                    .subjectX500Principal.name
            }.getOrNull()
            SignerCert(ApkFactsMapper.sha256Hex(sig.toByteArray()).lowercase(), subject)
        }
        val ai = context.applicationInfo
        val files = (listOfNotNull(ai.sourceDir) + ai.splitSourceDirs.orEmpty()).map(::File)
        return OwnIdentity(info?.versionName, info?.longVersionCode ?: 0L, signers, files)
    }

    /** Streams the file through SHA-256; null when it cannot be read. Blocking: call from Dispatchers.IO. */
    fun sha256(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1 shl 20).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        TrustText.hex(digest.digest())
    }.getOrNull()
}
