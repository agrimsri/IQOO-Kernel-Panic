package com.dpdpxray.core.model

import kotlinx.serialization.Serializable

/**
 * What TraceVeil read from the audited app's installed manifest (Android PackageManager) at audit start. Only
 * allow-listed names are kept (see `SdkManifestCatalog`): never the app's other meta-data, which can hold API keys or
 * client tokens.
 *
 * Unknown stays unknown: a key missing from [collectionFlags] means "not declared" only when [PART_META_DATA] is in
 * [partsRead]; a component missing from [sdkComponents] means "not declared" only when its part was read.
 */
@Serializable
data class AppManifestFacts(
    val versionName: String? = null,
    val versionCode: Long? = null,
    /** Allow-listed `<application>` meta-data that is declared, by name, with its value as text ("true", "false", …). */
    val collectionFlags: Map<String, String> = emptyMap(),
    /**
     * Allow-listed SDK component class names found among the app's services, receivers and providers, plus Firebase
     * component-registrar meta-data names (written "com.google.firebase.components:<registrar>").
     */
    val sdkComponents: List<String> = emptyList(),
    /** Which manifest parts were read: any of [PART_META_DATA], [PART_SERVICES], [PART_RECEIVERS], [PART_PROVIDERS]. */
    val partsRead: List<String> = emptyList(),
) {
    fun read(part: String): Boolean = part in partsRead

    companion object {
        const val PART_META_DATA = "meta-data"
        const val PART_SERVICES = "services"
        const val PART_RECEIVERS = "receivers"
        const val PART_PROVIDERS = "providers"
        val COMPONENT_PARTS = listOf(PART_SERVICES, PART_RECEIVERS, PART_PROVIDERS)
    }
}
