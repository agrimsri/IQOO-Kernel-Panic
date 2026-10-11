package com.dpdpxray.core.datasafety

import kotlinx.serialization.Serializable

/**
 * Google Play's Data safety categories and the data types inside each, as listed in Play Console Help "Provide information
 * for Google Play's Data safety section" (https://support.google.com/googleplay/android-developer/answer/10787469, read
 * 11 Oct 2026). Saved labels store these by name: add new values at the end and never rename one.
 */
@Serializable
enum class DataCategory(val label: String, val types: List<String>, val aliases: List<String> = emptyList()) {
    LOCATION("Location", listOf("Approximate location", "Precise location")),
    PERSONAL_INFO(
        "Personal info",
        listOf("Name", "Email address", "User IDs", "Address", "Phone number", "Race and ethnicity", "Political or religious beliefs", "Sexual orientation", "Other info"),
    ),
    FINANCIAL_INFO("Financial info", listOf("User payment info", "Purchase history", "Credit score", "Other financial info")),
    HEALTH_AND_FITNESS("Health and fitness", listOf("Health info", "Fitness info")),
    MESSAGES("Messages", listOf("Emails", "SMS or MMS", "Other in-app messages")),
    PHOTOS_AND_VIDEOS("Photos and videos", listOf("Photos", "Videos")),
    AUDIO_FILES("Audio files", listOf("Voice or sound recordings", "Music files", "Other audio files"), aliases = listOf("Audio")),
    FILES_AND_DOCS("Files and docs", listOf("Files and docs")),
    CALENDAR("Calendar", listOf("Calendar events")),
    CONTACTS("Contacts", listOf("Contacts")),
    APP_ACTIVITY("App activity", listOf("App interactions", "In-app search history", "Installed apps", "Other user-generated content", "Other actions")),
    WEB_BROWSING("Web browsing", listOf("Web browsing history")),
    APP_INFO_AND_PERFORMANCE("App info and performance", listOf("Crash logs", "Diagnostics", "Other app performance data")),
    DEVICE_OR_OTHER_IDS("Device or other IDs", listOf("Device or other IDs")),
}

/** One side of a label (shared or collected): the categories it names, the data types it names, and what it leaves out. */
@Serializable
data class DeclaredData(
    /** Play categories named directly or through one of their data types, in the order the text names them. */
    val categories: List<DataCategory> = emptyList(),
    /** Play data types named (usually only on the "See details" page), in order. */
    val types: List<String> = emptyList(),
    /** "Location, App activity and 2 others": how many more the text says exist but does not name. 0 = none said. */
    val notListed: Int = 0,
)

/**
 * What a pasted Google Play "Data safety" text declares. Every field is what the text says, nothing more: a statement the
 * text does not contain stays null (unknown), never "no". A label is the developer's own declaration; Google says its
 * review "is not designed to verify the accuracy and completeness of your data safety declarations".
 */
@Serializable
data class DataSafetyLabel(
    /** "No data shared with third parties". */
    val noDataShared: Boolean = false,
    /** "No data collected". */
    val noDataCollected: Boolean = false,
    /** What the text says may be shared; null = the text has no sharing list. */
    val shared: DeclaredData? = null,
    /** What the text says may be collected; null = the text has no collection list. */
    val collected: DeclaredData? = null,
    /** "Data is encrypted in transit" (true) or "Data isn't encrypted" (false); null = not stated. */
    val encryptedInTransit: Boolean? = null,
    /** "You can request that data be deleted" (true) or "Data can't be deleted" (false); null = not stated. */
    val deletionRequestable: Boolean? = null,
    /** Reading notes in plain words (contradictions, a heading with nothing recognisable after it, a cut text). */
    val notes: List<String> = emptyList(),
) {
    val sharingStated: Boolean get() = noDataShared || shared != null
    val collectionStated: Boolean get() = noDataCollected || collected != null

    /** False when the text has neither a sharing nor a collection statement: "could not read a label". */
    val readable: Boolean get() = sharingStated || collectionStated

    /** Every category the label declares as shared or collected. */
    val declaredCategories: Set<DataCategory>
        get() = (shared?.categories.orEmpty() + collected?.categories.orEmpty()).toSet()
}

/**
 * Reads the English text a person copies from a Play listing's "Data safety" section (the summary on the listing or the
 * "See details" page), pasted as it comes: one statement per line or all on one line, any case. Deterministic and
 * offline. Phrases it does not know are ignored; it never guesses a type that is not named.
 */
object DataSafetyParser {
    /** Longer text is cut here (a label is a few hundred characters). */
    const val MAX_CHARS = 20_000

    private enum class Kind { NO_SHARED, NO_COLLECTED, SHARED, COLLECTED, KEEP, END, ENCRYPTED, NOT_ENCRYPTED, DELETABLE, NOT_DELETABLE }

    private enum class Mode { NONE, SHARED, COLLECTED }

    private class Marker(val start: Int, val end: Int, val kind: Kind)

    /** Words may be split by any whitespace, including a line break. */
    private fun phrase(vararg alternatives: String): String =
        alternatives.joinToString("|") { alt -> alt.trim().split(' ').joinToString("\\s+") }

    private val markers: List<Pair<Kind, Regex>> = listOf(
        Kind.NO_SHARED to Regex(
            phrase("no data shared with third parties", "no data shared") + "|" +
                phrase("the developer says this app doesn't share user data") + "[^\\n.]*",
        ),
        Kind.NO_COLLECTED to Regex(phrase("no data collected") + "|" + phrase("the developer says this app doesn't collect user data") + "[^\\n.]*"),
        Kind.SHARED to Regex(
            phrase(
                "this app may share these data types with third parties", "this app may share these data types",
                "data shared and for what purpose", "data that may be shared with other companies or organizations",
                "data that may be shared with other companies or organisations", "data shared",
            ),
        ),
        Kind.COLLECTED to Regex(
            phrase("this app may collect these data types", "data collected and for what purpose", "data this app may collect", "data collected"),
        ),
        Kind.ENCRYPTED to Regex(phrase("data is encrypted in transit")),
        Kind.NOT_ENCRYPTED to Regex(phrase("data isn't encrypted", "data is not encrypted", "data not encrypted")),
        Kind.DELETABLE to Regex(phrase("you can request that data be deleted", "you can request that data is deleted")),
        Kind.NOT_DELETABLE to Regex(
            phrase("data can't be deleted", "data cannot be deleted", "data can not be deleted", "you can't request data deletion", "you can't request that data be deleted"),
        ),
        Kind.END to Regex(phrase("security practices", "independent security review", "data safety")),
        Kind.KEEP to Regex(
            phrase("learn more", "see details", "data practices may vary", "for more information about collected and shared data", "safety starts with understanding") +
                "[^\\n.]{0,120}",
        ),
    )

    /** Every category label, alias and data type, longest first so "Email address" wins over "Address". */
    private val names: List<Pair<String, DataCategory>> = DataCategory.entries
        .flatMap { c -> (listOf(c.label) + c.aliases + c.types).map { it to c } }
        .distinctBy { it.first.lowercase() }
        .sortedByDescending { it.first.length }

    private val typeNames: Map<String, String> = DataCategory.entries.flatMap { c -> c.types.map { it.lowercase() to it } }.toMap()

    private val nameRegex = Regex(
        names.joinToString("|", prefix = "(?<![a-z])(", postfix = ")(?![a-z])") { (n, _) -> phrase(n.lowercase()).replace("(", "\\(").replace(")", "\\)") },
    )
    private val byName: Map<String, DataCategory> = names.associate { (n, c) -> n.lowercase() to c }
    private val others = Regex("(?:and|\\+)\\s*(\\d{1,3})\\s+(?:others?|more)")

    fun parse(text: String): DataSafetyLabel {
        val notes = mutableListOf<String>()
        val cut = text.length > MAX_CHARS
        if (cut) notes += "Only the first $MAX_CHARS characters were read."
        val t = normalise(if (cut) text.take(MAX_CHARS) else text)
        val found = pickMarkers(t)

        var noShared = false
        var noCollected = false
        var encrypted: Boolean? = null
        var deletable: Boolean? = null
        var shared: Side? = null
        var collected: Side? = null
        var mode = Mode.NONE
        var cursor = 0
        fun flush(until: Int) {
            if (until <= cursor) return
            val segment = t.substring(cursor, until)
            when (mode) {
                Mode.SHARED -> shared!!.read(segment)
                Mode.COLLECTED -> collected!!.read(segment)
                Mode.NONE -> Unit
            }
        }
        for (m in found) {
            flush(m.start)
            cursor = m.end
            when (m.kind) {
                Kind.NO_SHARED -> { noShared = true; mode = Mode.NONE }
                Kind.NO_COLLECTED -> { noCollected = true; mode = Mode.NONE }
                Kind.SHARED -> { if (shared == null) shared = Side(); mode = Mode.SHARED }
                Kind.COLLECTED -> { if (collected == null) collected = Side(); mode = Mode.COLLECTED }
                Kind.ENCRYPTED -> { encrypted = true; mode = Mode.NONE }
                Kind.NOT_ENCRYPTED -> { encrypted = false; mode = Mode.NONE }
                Kind.DELETABLE -> { deletable = true; mode = Mode.NONE }
                Kind.NOT_DELETABLE -> { deletable = false; mode = Mode.NONE }
                Kind.END -> mode = Mode.NONE
                Kind.KEEP -> Unit
            }
        }
        flush(t.length)

        val sharedData = shared?.result()
        val collectedData = collected?.result()
        if (sharedData != null && sharedData.categories.isEmpty() && sharedData.notListed == 0) {
            notes += "A sharing heading was found, but no Play data type was recognised after it."
        }
        if (collectedData != null && collectedData.categories.isEmpty() && collectedData.notListed == 0) {
            notes += "A collection heading was found, but no Play data type was recognised after it."
        }
        if (noShared && sharedData != null && sharedData.categories.isNotEmpty()) {
            notes += "The text says both \"No data shared with third parties\" and lists shared data types; both are kept."
        }
        if (noCollected && collectedData != null && collectedData.categories.isNotEmpty()) {
            notes += "The text says both \"No data collected\" and lists collected data types; both are kept."
        }
        return DataSafetyLabel(
            noDataShared = noShared, noDataCollected = noCollected, shared = sharedData, collected = collectedData,
            encryptedInTransit = encrypted, deletionRequestable = deletable, notes = notes,
        )
    }

    /** Lower case, straight apostrophes, single spaces within a line; line breaks kept. */
    private fun normalise(text: String): String = text.lowercase()
        .replace('’', '\'').replace('‘', '\'').replace(' ', ' ').replace(' ', ' ').replace(' ', ' ')
        .replace("\r\n", "\n").replace('\r', '\n')
        .replace(Regex("[ \\t]+"), " ")

    /** All marker matches; where two overlap, the one that starts first (then the longer) wins. */
    private fun pickMarkers(t: String): List<Marker> {
        val all = markers.flatMap { (kind, re) -> re.findAll(t).map { Marker(it.range.first, it.range.last + 1, kind) } }
            .sortedWith(compareBy<Marker> { it.start }.thenByDescending { it.end })
        val kept = mutableListOf<Marker>()
        for (m in all) if (kept.isEmpty() || m.start >= kept.last().end) kept += m
        return kept
    }

    private class Side {
        val categories = LinkedHashSet<DataCategory>()
        val types = LinkedHashSet<String>()
        var notListed = 0

        fun read(segment: String) {
            others.findAll(segment).forEach { notListed += it.groupValues[1].toInt() }
            nameRegex.findAll(segment).forEach { m ->
                val key = m.value.replace(Regex("\\s+"), " ")
                byName[key]?.let { categories += it }
                typeNames[key]?.let { types += it }
            }
        }

        fun result() = DeclaredData(categories.toList(), types.toList(), notListed)
    }
}

/** Labels bundled with the app for the demo. Written by the TraceVeil team; not copied from any Play listing. */
object DemoLabels {
    /** The LeakyShop demo label: "No data shared with third parties" and "App activity" collected, nothing else. */
    val LEAKYSHOP: String by lazy {
        DemoLabels::class.java.getResourceAsStream("/datasafety/leakyshop_demo_label.txt")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("leakyshop_demo_label.txt missing from resources")
    }
}
