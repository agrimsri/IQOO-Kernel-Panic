package com.dpdpxray.app.audit

import android.content.Context
import android.content.pm.ComponentInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.dpdpxray.core.manifest.SdkManifestCatalog
import com.dpdpxray.core.model.AppManifestFacts

/**
 * Reads the audited app's installed manifest through PackageManager (read-only; TraceVeil already declares
 * QUERY_ALL_PACKAGES) and keeps only the allow-listed SDK facts (check C13). Each part is read separately so one
 * oversized part (very large apps) only leaves that part unread, which the facts record, instead of losing everything.
 */
internal object ManifestFactsReader {
    private const val FIREBASE_REGISTRAR = "com.google.firebase.components:"

    fun read(context: Context, pkg: String, catalog: SdkManifestCatalog = SdkManifestCatalog.default): AppManifestFacts? {
        val pm = context.packageManager
        val base = info(pm, pkg, 0) ?: return null
        val metaData: Map<String, Any?>? = runCatching {
            @Suppress("DEPRECATION")
            val bundle = pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA).metaData
            // Only documented switches are read; other meta-data (API keys, client tokens) is never touched.
            @Suppress("DEPRECATION")
            bundle?.keySet()?.filter(catalog::isCollectionFlag)?.associateWith { bundle.get(it) }.orEmpty()
        }.getOrNull()
        val services = info(pm, pkg, PackageManager.GET_SERVICES or PackageManager.GET_META_DATA)?.services?.let { list ->
            // Firebase registers each SDK as meta-data on its ComponentDiscoveryService.
            names(list) + list.flatMap { s -> s.metaData?.keySet().orEmpty().filter { it.startsWith(FIREBASE_REGISTRAR) } }
        } ?: info(pm, pkg, PackageManager.GET_SERVICES)?.let { names(it.services) }
        return catalog.facts(
            versionName = base.versionName,
            versionCode = base.longVersionCode,
            metaData = metaData,
            components = mapOf(
                AppManifestFacts.PART_SERVICES to services,
                AppManifestFacts.PART_RECEIVERS to info(pm, pkg, PackageManager.GET_RECEIVERS)?.let { names(it.receivers) },
                AppManifestFacts.PART_PROVIDERS to info(pm, pkg, PackageManager.GET_PROVIDERS)?.let { names(it.providers) },
            ),
        )
    }

    /** null when Android refused the read (package gone, or the answer too large); a part that was read is never null. */
    private fun info(pm: PackageManager, pkg: String, flags: Int): PackageInfo? = runCatching {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(pkg, flags or PackageManager.MATCH_DISABLED_COMPONENTS)
    }.getOrNull()

    private fun names(list: Array<out ComponentInfo>?): List<String> = list.orEmpty().map { it.name }
}
