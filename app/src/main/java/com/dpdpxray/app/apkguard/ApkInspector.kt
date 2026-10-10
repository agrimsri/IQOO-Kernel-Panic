package com.dpdpxray.app.apkguard

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import androidx.core.graphics.drawable.toBitmap
import com.dpdpxray.core.apk.ApkFacts
import com.dpdpxray.core.apk.InstallOrigin
import com.dpdpxray.core.apk.InstallSource
import com.dpdpxray.core.apk.InstallSources
import com.dpdpxray.core.apk.InstalledTwin
import com.dpdpxray.core.apk.PackageSourceHint
import com.dpdpxray.core.apk.ServiceKind
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** What APK Guard read about one package, plus display-only details that are not rule inputs. */
data class InspectedApp(
    val facts: ApkFacts,
    val icon: Bitmap?,
    val isSystemApp: Boolean = false,
    val firstInstallTime: Long? = null,
    val lastUpdateTime: Long? = null,
    /** The app that started the install, when Android reports it and it differs from the installer. */
    val initiatingPackage: String? = null,
    val fileName: String? = null,
    val fileSizeBytes: Long? = null,
    val fileSha256: String? = null,
)

sealed interface ArchiveRead {
    data class Ok(val app: InspectedApp) : ArchiveRead
    data class Failed(val reason: String) : ArchiveRead
}

/**
 * Reads what Android shows about a package: declared permissions, declared services and receivers, signing
 * certificates, target SDK and install source. It only reads; it never installs, launches or changes anything.
 */
object ApkInspector {
    /** Larger files are refused rather than copied; normal app APKs are far smaller. */
    const val MAX_FILE_BYTES = 400L * 1024 * 1024
    private const val CACHE_DIR = "apkguard"
    private const val STALE_COPY_MS = 10 * 60 * 1000L

    private const val COMPONENT_FLAGS = PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or
        PackageManager.GET_RECEIVERS or PackageManager.MATCH_DISABLED_COMPONENTS

    // --- installed apps -------------------------------------------------------------------------------------------

    fun readInstalled(context: Context, pkg: String): InspectedApp? {
        val pm = context.packageManager
        val info = packageInfo(pm, pkg, COMPONENT_FLAGS or PackageManager.GET_SIGNING_CERTIFICATES)
            ?: packageInfo(pm, pkg, PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNING_CERTIFICATES) // very large manifests
            ?: return null
        val ai = info.applicationInfo ?: return null
        val isSystem = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0
        val (installer, initiating, hint) = installSource(pm, pkg)
        val signers = signers(info)
        val facts = ApkFacts(
            packageName = pkg,
            label = runCatching { ai.loadLabel(pm).toString() }.getOrNull(),
            versionName = info.versionName,
            versionCode = info.longVersionCode,
            targetSdk = ai.targetSdkVersion,
            permissions = info.requestedPermissions?.toSet().orEmpty(),
            grantedPermissions = ApkFactsMapper.granted(info.requestedPermissions, info.requestedPermissionsFlags, PackageInfo.REQUESTED_PERMISSION_GRANTED),
            components = ApkFactsMapper.components(rawServices(info), rawReceivers(info), enabledComponents(context, pkg)),
            origin = InstallSources.classify(installer, hint, isSystem),
            certSha256 = signers.map { ApkFactsMapper.sha256Hex(it.toByteArray()) },
            signerSubjects = signers.mapNotNull { subject(it) },
            hasLauncherIcon = pm.getLaunchIntentForPackage(pkg) != null,
        )
        return InspectedApp(
            facts = facts,
            icon = runCatching { ai.loadIcon(pm).toBitmap(128, 128) }.getOrNull(),
            isSystemApp = isSystem,
            firstInstallTime = info.firstInstallTime.takeIf(::plausibleInstallTime),
            lastUpdateTime = info.lastUpdateTime,
            initiatingPackage = initiating?.takeIf { it != installer },
        )
    }

    private fun packageInfo(pm: PackageManager, pkg: String, flags: Int): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
        else @Suppress("DEPRECATION") pm.getPackageInfo(pkg, flags)
    }.getOrNull()

    private fun installSource(pm: PackageManager, pkg: String): Triple<String?, String?, PackageSourceHint> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val src = runCatching { pm.getInstallSourceInfo(pkg) }.getOrNull() ?: return Triple(null, null, PackageSourceHint.UNSPECIFIED)
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) when (src.packageSource) {
                PackageInstaller.PACKAGE_SOURCE_STORE -> PackageSourceHint.STORE
                PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE -> PackageSourceHint.LOCAL_FILE
                PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE -> PackageSourceHint.DOWNLOADED_FILE
                PackageInstaller.PACKAGE_SOURCE_OTHER -> PackageSourceHint.OTHER
                else -> PackageSourceHint.UNSPECIFIED
            } else PackageSourceHint.UNSPECIFIED
            return Triple(src.installingPackageName ?: src.initiatingPackageName, src.initiatingPackageName, hint)
        }
        return Triple(legacyInstaller(pm, pkg), null, PackageSourceHint.UNSPECIFIED)
    }

    @Suppress("DEPRECATION")
    private fun legacyInstaller(pm: PackageManager, pkg: String): String? = runCatching { pm.getInstallerPackageName(pkg) }.getOrNull()

    /** Per service kind, the class names Android reports as switched on. Kinds Android does not expose stay unknown. */
    private fun enabledComponents(context: Context, pkg: String): Map<ServiceKind, Set<String>> = buildMap {
        runCatching {
            context.getSystemService(AccessibilityManager::class.java)
                ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                ?.mapNotNull { it.resolveInfo?.serviceInfo }?.filter { it.packageName == pkg }?.map { it.name }?.toSet()
        }.getOrNull()?.let { put(ServiceKind.ACCESSIBILITY, it) }
        runCatching {
            context.getSystemService(DevicePolicyManager::class.java)?.activeAdmins.orEmpty()
                .filter { it.packageName == pkg }.map { it.className }.toSet()
        }.getOrNull()?.let { put(ServiceKind.DEVICE_ADMIN, it) }
        runCatching {
            context.getSystemService(InputMethodManager::class.java)?.enabledInputMethodList
                ?.filter { it.packageName == pkg }?.map { it.serviceName }?.toSet()
        }.getOrNull()?.let { put(ServiceKind.INPUT_METHOD, it) }
    }

    private fun rawServices(info: PackageInfo) = info.services.orEmpty().map { RawComponent(it.name, it.permission) }
    private fun rawReceivers(info: PackageInfo) = info.receivers.orEmpty().map { RawComponent(it.name, it.permission) }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): List<Signature> {
        info.signingInfo?.apkContentsSigners?.takeIf { it.isNotEmpty() }?.let { return it.toList() }
        return info.signatures?.toList().orEmpty()
    }

    private fun subject(sig: Signature): String? = runCatching {
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(sig.toByteArray())) as X509Certificate
        cert.subjectX500Principal.name
    }.getOrNull()

    // --- APK files --------------------------------------------------------------------------------------------------

    /** Copies the file to private cache, reads it with getPackageArchiveInfo and always deletes the copy. */
    fun readArchive(context: Context, uri: Uri): ArchiveRead {
        val resolver = context.contentResolver
        val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        // Leftovers from an interrupted check; a copy that another check is reading right now is left alone.
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > STALE_COPY_MS }?.forEach { it.delete() }
        val (name, declaredSize) = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (!c.moveToFirst()) null to null
                else c.getString(0) to (if (c.isNull(1)) null else c.getLong(1))
            } ?: (null to null)
        }.getOrDefault(null to null)
        if (declaredSize != null && declaredSize > MAX_FILE_BYTES) return ArchiveRead.Failed("This file is larger than ${ApkFactsMapper.size(MAX_FILE_BYTES)}, so APK Guard did not copy it.")

        val copy = File.createTempFile("check-", ".apk", dir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            val input = runCatching { resolver.openInputStream(uri) }.getOrNull()
                ?: return ArchiveRead.Failed("TraceVeil could not open this file. Open it again from the app where you found it.")
            input.use { inp ->
                copy.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_FILE_BYTES) return ArchiveRead.Failed("This file is larger than ${ApkFactsMapper.size(MAX_FILE_BYTES)}, so APK Guard stopped reading it.")
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            }
            val fileSha = digest.digest().joinToString("") { "%02X".format(it) }
            val pm = context.packageManager
            val info = archiveInfo(pm, copy.path, COMPONENT_FLAGS or PackageManager.GET_SIGNING_CERTIFICATES)
                ?: archiveInfo(pm, copy.path, PackageManager.GET_PERMISSIONS)
                ?: return ArchiveRead.Failed("This file could not be read as an Android app (APK). App bundles such as .xapk or .apks are not supported.")
            val signers = signers(info).ifEmpty { legacyArchiveSigners(pm, copy.path) }
            val ai = info.applicationInfo?.apply { sourceDir = copy.path; publicSourceDir = copy.path }
            val pkg = info.packageName
            val certs = signers.map { ApkFactsMapper.sha256Hex(it.toByteArray()) }
            val facts = ApkFacts(
                packageName = pkg,
                label = ai?.let { runCatching { it.loadLabel(pm).toString() }.getOrNull() },
                versionName = info.versionName,
                versionCode = info.longVersionCode,
                targetSdk = ai?.targetSdkVersion,
                permissions = info.requestedPermissions?.toSet().orEmpty(),
                grantedPermissions = null,
                components = ApkFactsMapper.components(rawServices(info), rawReceivers(info), emptyMap()),
                origin = InstallOrigin(InstallSource.NOT_INSTALLED),
                certSha256 = certs,
                signerSubjects = signers.mapNotNull { subject(it) },
                hasLauncherIcon = null,
                installedTwin = installedTwin(context, pkg),
            )
            return ArchiveRead.Ok(
                InspectedApp(
                    facts = facts,
                    icon = ai?.let { runCatching { it.loadIcon(pm).toBitmap(128, 128) }.getOrNull() },
                    fileName = name, fileSizeBytes = total, fileSha256 = fileSha,
                ),
            )
        } catch (e: Exception) {
            return ArchiveRead.Failed("APK Guard could not read this file (${e.javaClass.simpleName}).")
        } finally {
            copy.delete()
        }
    }

    private fun archiveInfo(pm: PackageManager, path: String, flags: Int): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        else @Suppress("DEPRECATION") pm.getPackageArchiveInfo(path, flags)
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun legacyArchiveSigners(pm: PackageManager, path: String): List<Signature> =
        archiveInfo(pm, path, PackageManager.GET_SIGNATURES)?.let { signers(it) }.orEmpty()

    private fun installedTwin(context: Context, pkg: String): InstalledTwin? {
        val pm = context.packageManager
        val info = packageInfo(pm, pkg, PackageManager.GET_SIGNING_CERTIFICATES) ?: return null
        val label = info.applicationInfo?.let { runCatching { it.loadLabel(pm).toString() }.getOrNull() } ?: pkg
        return InstalledTwin(label, signers(info).map { ApkFactsMapper.sha256Hex(it.toByteArray()) })
    }

    /**
     * Android reports a date before Android 1.0 (e.g. "10 Feb 1970") for some apps the phone maker preloads; that is
     * not a real install date, so it is shown as not reported.
     */
    fun plausibleInstallTime(epochMs: Long): Boolean = epochMs >= ANDROID_1_0_MS

    private const val ANDROID_1_0_MS = 1_222_128_000_000L // 23 Sep 2008

    // --- installed list for the hub ---------------------------------------------------------------------------------

    data class ListedApp(val packageName: String, val label: String, val isSystem: Boolean, val firstInstallTime: Long, val hasLauncher: Boolean)

    /** Every installed app except TraceVeil, newest first. Apps without a launcher screen are included on purpose. */
    fun listInstalled(context: Context): List<ListedApp> {
        val pm = context.packageManager
        val launchable = runCatching {
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            else @Suppress("DEPRECATION") pm.queryIntentActivities(intent, 0)
        }.getOrDefault(emptyList()).map { it.activityInfo.packageName }.toSet()
        val packages = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
            else @Suppress("DEPRECATION") pm.getInstalledPackages(0)
        }.getOrDefault(emptyList())
        return packages.asSequence()
            .filter { it.packageName != context.packageName }
            .mapNotNull { p ->
                val ai = p.applicationInfo ?: return@mapNotNull null
                ListedApp(
                    packageName = p.packageName,
                    label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(p.packageName),
                    isSystem = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    firstInstallTime = p.firstInstallTime,
                    hasLauncher = p.packageName in launchable,
                )
            }
            .sortedWith(compareByDescending<ListedApp> { it.firstInstallTime }.thenBy { it.label.lowercase() })
            .toList()
    }
}
