package com.dpdpxray.app.apkguard

import com.dpdpxray.core.apk.DeclaredComponent
import com.dpdpxray.core.apk.InstallOrigin
import com.dpdpxray.core.apk.InstallSource
import com.dpdpxray.core.apk.ServiceKind
import java.security.MessageDigest
import java.util.Locale

/** A declared service or receiver, copied out of Android's ServiceInfo/ActivityInfo so mapping is testable on the JVM. */
data class RawComponent(val className: String, val permission: String?)

/** Pure mapping from PackageManager values to APK Guard facts and display strings. No Android types. */
object ApkFactsMapper {
    /**
     * Services are matched by their binding permission; device admin is a receiver protected by BIND_DEVICE_ADMIN.
     * [enabled] holds, per kind, the class names Android reports as switched on; a kind missing from it stays unknown.
     */
    fun components(services: List<RawComponent>, receivers: List<RawComponent>, enabled: Map<ServiceKind, Set<String>>): List<DeclaredComponent> {
        val fromServices = services.mapNotNull { s ->
            ServiceKind.fromBindPermission(s.permission)?.takeIf { it != ServiceKind.DEVICE_ADMIN }?.let { s to it }
        }
        val fromReceivers = receivers.mapNotNull { r ->
            ServiceKind.fromBindPermission(r.permission)?.takeIf { it == ServiceKind.DEVICE_ADMIN }?.let { r to it }
        }
        return (fromServices + fromReceivers).map { (c, kind) -> DeclaredComponent(kind, c.className, enabled[kind]?.contains(c.className)) }
    }

    /** Permissions whose matching flag has [grantedFlag] set (PackageInfo.REQUESTED_PERMISSION_GRANTED). */
    fun granted(requested: Array<String>?, flags: IntArray?, grantedFlag: Int): Set<String> {
        if (requested == null || flags == null) return emptySet()
        return requested.indices.filter { it < flags.size && flags[it] and grantedFlag != 0 }.map { requested[it] }.toSet()
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02X".format(it) }

    fun colonHex(hex: String): String = hex.uppercase(Locale.ROOT).chunked(2).joinToString(":")

    /** First and last four bytes, enough to compare by eye; the full value is always available to copy. */
    fun shortFingerprint(hex: String): String {
        val bytes = hex.uppercase(Locale.ROOT).chunked(2)
        if (bytes.size <= 8) return bytes.joinToString(":")
        return bytes.take(4).joinToString(":") + " … " + bytes.takeLast(4).joinToString(":")
    }

    fun originLabel(origin: InstallOrigin): String {
        val pkg = origin.installerPackage
        return when (origin.source) {
            InstallSource.PLAY_STORE -> "Google Play" + (pkg?.let { " ($it)" } ?: "")
            InstallSource.OTHER_STORE -> (origin.storeName ?: "Another app store") + (pkg?.let { " ($it)" } ?: "")
            InstallSource.FILE -> "APK file" + (pkg?.let { " via $it" } ?: "")
            InstallSource.OTHER_APP -> "Another app: " + (pkg ?: "unnamed")
            InstallSource.PREINSTALLED -> "Came with the phone (system app)"
            InstallSource.UNKNOWN -> "Not reported by Android"
            InstallSource.NOT_INSTALLED -> "APK file, not installed"
        }
    }

    fun size(bytes: Long?): String = when {
        bytes == null || bytes < 0 -> "unknown size"
        bytes < 1_000 -> "$bytes B"
        bytes < 1_000_000 -> String.format(Locale.ROOT, "%.1f kB", bytes / 1_000.0)
        else -> String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)
    }
}
