package com.dpdpxray.core.manifest

import com.dpdpxray.core.model.AppManifestFacts
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One documented manifest switch: [offWhen] is the declared value that turns the behaviour off. */
@Serializable
data class ManifestFlag(
    val key: String,
    val offWhen: String,
    /** What leaving the key out means, per [source]. */
    val absentMeans: String,
    val source: String,
)

/**
 * A vendor SDK whose default data collection is controlled by `<application>` meta-data. [components] and
 * [componentPrefixes] are the names the SDK's own library manifest merges into an app ([componentSource]), so their
 * presence in an installed app shows the SDK is bundled. [gates] all have to stay at their default for collection to
 * start at launch; [identifiers] are separate identifier switches, reported when collection is on.
 */
@Serializable
data class SdkManifestEntry(
    val sdkKey: String,
    /** The tracker-catalogue entity this SDK reports as, so fixes and same-company context line up. */
    val entity: String,
    val collection: String,
    val components: List<String> = emptyList(),
    val componentPrefixes: List<String> = emptyList(),
    val componentSource: String,
    val gates: List<ManifestFlag>,
    val identifiers: List<ManifestFlag> = emptyList(),
    val source: String,
    val note: String = "",
)

/** How a documented switch is set in the installed manifest. */
enum class FlagState {
    /** Not declared, so the documented default (on) applies. */
    DEFAULT,
    /** Declared with the value that keeps the behaviour on. */
    ON,
    /** Declared with the value that turns the behaviour off. */
    OFF,
    /** Declared with a value that is not a boolean, so its effect is not established. */
    UNREADABLE,
}

/** One SDK found in the installed manifest, with the state of each of its documented switches. */
data class SdkCollectionStatus(
    val entry: SdkManifestEntry,
    /** Allow-listed component names that show the SDK is bundled. */
    val evidencedBy: List<String>,
    val gates: List<Pair<ManifestFlag, FlagState>>,
    val identifiers: List<Pair<ManifestFlag, FlagState>>,
) {
    /** true = every gate left on; false = a gate turns collection off; null = a gate's value could not be read. */
    val onByDefault: Boolean?
        get() = when {
            gates.any { it.second == FlagState.OFF } -> false
            gates.any { it.second == FlagState.UNREADABLE } -> null
            else -> true
        }
}

/**
 * Allow-list and reading rules for SDK collection switches in an installed app's manifest. Only names in this catalogue
 * are ever kept from the manifest ([facts]), so other meta-data such as API keys or client tokens never reach a saved
 * audit. Every entry cites the vendor documentation it rests on.
 */
class SdkManifestCatalog(val entries: List<SdkManifestEntry>) {
    private val flagKeys: Set<String> = entries.flatMap { e -> (e.gates + e.identifiers).map { it.key } }.toSet()
    private val componentNames: Set<String> = entries.flatMap { it.components }.toSet()
    private val componentPrefixes: List<String> = entries.flatMap { it.componentPrefixes }

    fun isSdkComponent(name: String): Boolean = name in componentNames || componentPrefixes.any { name.startsWith(it) }

    /**
     * Keeps only allow-listed facts. [metaData] is the application's meta-data (null = not read); [components] maps
     * each manifest part ([AppManifestFacts.COMPONENT_PARTS]) to the names found there (null = that part not read).
     * Firebase registrars are passed as "com.google.firebase.components:<registrar>" names in the services part.
     */
    fun facts(
        versionName: String?,
        versionCode: Long?,
        metaData: Map<String, Any?>?,
        components: Map<String, List<String>?>,
    ): AppManifestFacts {
        val flags = metaData.orEmpty().filterKeys { it in flagKeys }.mapValues { (_, v) -> v?.toString() ?: "null" }
        val found = components.values.filterNotNull().flatten().filter(::isSdkComponent).distinct().sorted()
        val parts = buildList {
            if (metaData != null) add(AppManifestFacts.PART_META_DATA)
            AppManifestFacts.COMPONENT_PARTS.filter { components[it] != null }.forEach(::add)
        }
        return AppManifestFacts(versionName, versionCode, flags.toSortedMap(), found, parts)
    }

    /**
     * SDKs whose components appear in [facts], with their switch states. An SDK whose components are not found is left
     * out: either it is not bundled, or (when component parts were not read) its presence is not established.
     */
    fun evaluate(facts: AppManifestFacts): List<SdkCollectionStatus> = entries.mapNotNull { e ->
        val by = facts.sdkComponents.filter { n -> n in e.components || e.componentPrefixes.any { n.startsWith(it) } }
        if (by.isEmpty()) return@mapNotNull null
        fun state(f: ManifestFlag): FlagState {
            if (!facts.read(AppManifestFacts.PART_META_DATA)) return FlagState.UNREADABLE
            val v = facts.collectionFlags[f.key]?.trim()?.lowercase() ?: return FlagState.DEFAULT
            return when {
                v == f.offWhen.lowercase() -> FlagState.OFF
                v == "true" || v == "false" -> FlagState.ON
                else -> FlagState.UNREADABLE
            }
        }
        SdkCollectionStatus(e, by, e.gates.map { it to state(it) }, e.identifiers.map { it to state(it) })
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String): SdkManifestCatalog = SdkManifestCatalog(json.decodeFromString<List<SdkManifestEntry>>(text))

        fun loadDefault(): SdkManifestCatalog {
            val text = SdkManifestCatalog::class.java.getResourceAsStream("/sdk/manifest_flags.json")
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("sdk/manifest_flags.json missing from resources")
            return fromJson(text)
        }

        /** Loaded once; the catalogue ships with the app and does not change at runtime. */
        val default: SdkManifestCatalog by lazy { loadDefault() }
    }
}
