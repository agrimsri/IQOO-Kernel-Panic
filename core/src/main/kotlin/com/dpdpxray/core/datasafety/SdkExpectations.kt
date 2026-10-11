package com.dpdpxray.core.datasafety

import com.dpdpxray.core.model.Category
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One Play category an SDK's own documentation describes collecting, with the vendor's words. */
@Serializable
data class ExpectedType(val category: DataCategory, val quote: String = "")

/**
 * What an SDK's documentation says it collects, mapped to Play's Data safety categories. A curated entry quotes the vendor
 * page it was read from ([verified]); otherwise it is TraceVeil's catalogue expectation for that kind of SDK, unverified,
 * and is worded more softly. Mapping a vendor's words to Play's categories is TraceVeil's reading, and what an SDK
 * actually collects depends on how the app configures it.
 */
@Serializable
data class SdkExpectation(
    /** Tracker catalogue entity name (e.g. "AppsFlyer"). */
    val entity: String,
    val types: List<ExpectedType>,
    val sourceTitle: String? = null,
    val sourceUrl: String? = null,
    /** Date the source was read, yyyy-mm-dd. */
    val checked: String? = null,
    /** The vendor page itself says the SDK shares (not only collects) these data types. */
    val sharesPerSource: Boolean = false,
    /** The vendor's words for [sharesPerSource]. */
    val sharesQuote: String? = null,
    val note: String? = null,
) {
    /** A vendor page was read and quoted. False = catalogue expectation, unverified. */
    val verified: Boolean get() = sourceUrl != null

    val categories: List<DataCategory> get() = types.map { it.category }.distinct()
}

/** Curated SDK expectations (bundled `datasafety/sdk_expectations.json`) plus soft defaults by catalogue category. */
class SdkExpectations(val curated: List<SdkExpectation>) {
    private val byEntity = curated.associateBy { it.entity }

    /** The curated entry for [entity], else the unverified default for its [category], else null (not a third-party SDK kind). */
    fun forEntity(entity: String, category: Category): SdkExpectation? = byEntity[entity] ?: defaultFor(entity, category)

    companion object {
        const val UNVERIFIED = "catalogue expectation, unverified"
        const val MAPPING_NOTE = "Vendor pages describe data in their own words; matching those words to Play's categories " +
            "is TraceVeil's reading, and what an SDK collects depends on how the app configures it."

        private val json = Json { ignoreUnknownKeys = true }

        val default: SdkExpectations by lazy { loadDefault() }

        fun loadDefault(): SdkExpectations {
            val text = SdkExpectations::class.java.getResourceAsStream("/datasafety/sdk_expectations.json")
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("sdk_expectations.json missing from resources")
            return SdkExpectations(json.decodeFromString<List<SdkExpectation>>(text))
        }

        /** Soft defaults by kind of SDK, with no vendor quote. Not a third-party SDK kind: null. */
        fun defaultFor(entity: String, category: Category): SdkExpectation? {
            val cats = when (category) {
                Category.ADVERTISING, Category.ATTRIBUTION, Category.SOCIAL -> listOf(DataCategory.DEVICE_OR_OTHER_IDS, DataCategory.APP_ACTIVITY)
                Category.ANALYTICS, Category.ENGAGEMENT -> listOf(DataCategory.APP_ACTIVITY, DataCategory.DEVICE_OR_OTHER_IDS)
                Category.CRASH_REPORTING -> listOf(DataCategory.APP_INFO_AND_PERFORMANCE)
                else -> return null
            }
            return SdkExpectation(entity, cats.map { ExpectedType(it) })
        }
    }
}
