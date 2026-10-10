package com.dpdpxray.core.trackers

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Who publishes an audited app, from one cited source. Separate from the tracker vendors' company facts
 * ([CompanyCountry]): this answers "whose app is this?", so that the app's own company's servers are not reported as a
 * third-party tracker. The default records quote the developer name a Google Play listing gives under
 * "About the developer" for exactly that package.
 *
 * Limitation: the owner is looked up by package name. A modified or side-loaded copy that reuses the package name would
 * be labelled with the real publisher; the signing certificate is not checked.
 */
@Serializable
data class AppOwner(
    /** Exact package name, or a `prefix.*` wildcard covering child packages on a dot boundary. */
    @SerialName("package") val packageName: String,
    /** The app's name as the source lists it. */
    val app: String,
    /** Company group; the same names as [TrackerEntry.company] (e.g. "Meta", "Google"). */
    val company: String,
    /** The source's own words naming the publisher, so a reviewer can check the claim without trusting this file. */
    val quote: String,
    /** https URL of the page that was read. */
    val source: String,
    /** Date the source was read, yyyy-mm-dd. */
    val asOf: String,
    val note: String? = null,
)

/** Package-name lookup over cited [AppOwner] records. An app with no record has an unknown owner (null), never a guess. */
class AppOwners(private val records: List<AppOwner>) {
    private val exact = records.filterNot { it.isWildcard }.associateBy { it.packageName }
    private val wildcards = records.filter { it.isWildcard }.sortedByDescending { it.packageName.length }

    /** An exact record wins; otherwise the longest wildcard whose prefix is followed by a dot. */
    fun ownerOf(packageName: String): AppOwner? = exact[packageName]
        ?: wildcards.firstOrNull { packageName.startsWith(it.packageName.removeSuffix("*")) }

    fun all(): List<AppOwner> = records

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private const val MAX_QUOTE = 400
        private val date = Regex("\\d{4}-\\d{2}-\\d{2}")
        private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+(\\.\\*)?")

        private val AppOwner.isWildcard get() = packageName.endsWith(".*")

        /** Throws [IllegalArgumentException] naming the package when a record lacks its citation or is malformed. */
        fun fromJson(text: String): AppOwners = AppOwners(json.decodeFromString<List<AppOwner>>(text).onEach(::check))

        fun loadDefault(): AppOwners {
            val text = AppOwners::class.java.getResourceAsStream("/trackers/app_owners.json")
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("app_owners.json missing from resources")
            return fromJson(text)
        }

        /** Loaded once; the resource is static. */
        val default: AppOwners by lazy { loadDefault() }

        private fun check(o: AppOwner) {
            fun ensure(ok: Boolean, why: String) = require(ok) { "${o.packageName}: $why" }
            ensure(packagePattern.matches(o.packageName), "not a package name or 'prefix.*' wildcard")
            ensure(o.company.isNotBlank(), "needs a company")
            ensure(o.app.isNotBlank(), "needs the app's listed name")
            ensure(o.source.startsWith("https://") && o.source.length >= 12 && o.source.none { it.isWhitespace() }, "needs an https source URL")
            ensure(date.matches(o.asOf), "needs an asOf date (yyyy-mm-dd)")
            ensure(o.quote.isNotBlank() && o.quote.trim().length <= MAX_QUOTE, "needs the source's own words as a quote (1..$MAX_QUOTE characters)")
        }
    }
}
