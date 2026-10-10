package com.dpdpxray.core.datasafety

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The label text a person attached to a saved audit, kept verbatim so the comparison can be re-run with newer rules. */
@Serializable
data class PlayLabelRecord(
    val text: String,
    /** [PASTED], [FILE] or [DEMO]. */
    val source: String,
    val addedAtMs: Long,
) {
    val parsed: DataSafetyLabel get() = DataSafetyParser.parse(text)

    companion object {
        const val PASTED = "pasted"
        const val FILE = "file"
        const val DEMO = "demo"

        fun sourceWords(source: String): String = when (source) {
            DEMO -> "demo label written by the TraceVeil team (not a Google Play listing)"
            FILE -> "text file chosen on the phone"
            PASTED -> "text pasted on the phone"
            else -> source
        }
    }
}

/**
 * The optional "playLabel" part of a traceveil.audit.v1 bundle: the attached label text, how TraceVeil read it and, when
 * computed for this file, the comparison. Older readers ignore it; a bundle without it means "no label attached", never
 * "the label matches".
 */
@Serializable
data class PlayLabelExport(
    val basis: String = BASIS,
    val source: String,
    val addedAtMs: Long,
    val text: String,
    val label: DataSafetyLabel,
    /** null = not computed in this file (for example in a backup); re-run it from [text]. */
    val check: PlayLabelCheck? = null,
) {
    companion object {
        const val BASIS = "The Data safety text a person attached on the phone, read by TraceVeil and set against this bundle's " +
            "lookups with TraceVeil's tracker catalogue and the SDK documentation it cites. Separate from the score. A label is " +
            "the developer's own declaration; a lookup is an attempt to contact a host, not proof that data was sent."

        fun of(record: PlayLabelRecord, check: PlayLabelCheck?) = PlayLabelExport(
            source = record.source, addedAtMs = record.addedAtMs, text = record.text, label = check?.label ?: record.parsed, check = check,
        )
    }
}

/** The comparison as report text: a Markdown section (report and PDF) and plain text (TraceVeil CI). */
object PlayLabelReport {
    const val TITLE = "Play Data safety label vs what this check saw"
    const val PLAY_REVIEW_QUOTE = "Google's review process is not designed to verify the accuracy and completeness of your data safety declarations."
    const val PLAY_HELP_URL = "https://support.google.com/googleplay/android-developer/answer/10787469"
    const val SEPARATE = "Separate from the score: these are points to review with the developer, not findings."
    const val RESEARCH_CONTEXT = "Context: Arkalakis et al. (USENIX Security 2024) studied \"almost 5K applications\" and report that " +
        "\"81% misrepresent their data collection and sharing practices in the Data Safety section\". " +
        "https://www.usenix.org/conference/usenixsecurity24/presentation/arkalakis"

    /** What the label states, one line per part; parts the text does not state say so. */
    fun labelLines(label: DataSafetyLabel): List<String> = buildList {
        add("Sharing: " + side(label.noDataShared, "No data shared with third parties", label.shared))
        add("Collection: " + side(label.noDataCollected, "No data collected", label.collected))
        val security = listOfNotNull(
            label.encryptedInTransit?.let { if (it) "Data is encrypted in transit" else "Data isn't encrypted" },
            label.deletionRequestable?.let { if (it) "You can request that data be deleted" else "Data can't be deleted" },
        )
        if (security.isNotEmpty()) add("Security: " + security.joinToString("; "))
    }

    private fun side(none: Boolean, noneWords: String, data: DeclaredData?): String {
        val listed = data?.let { d ->
            when {
                d.categories.isEmpty() && d.notListed == 0 -> "a heading, but no Play data type was recognised"
                else -> PlayLabelComparator.join(d.categories.map { it.label }) +
                    if (d.notListed > 0) " (and ${d.notListed} others not named)" else ""
            }
        }
        return when {
            none && listed != null -> "$noneWords, and also lists $listed"
            none -> noneWords
            listed != null -> listed
            else -> "not in the pasted text"
        }
    }

    fun markdown(record: PlayLabelRecord, check: PlayLabelCheck): String = buildString {
        appendLine("## $TITLE")
        appendLine()
        appendLine("*$SEPARATE A label is the developer's own declaration; Google says \"$PLAY_REVIEW_QUOTE\" ($PLAY_HELP_URL). " +
            "A lookup is an attempt to contact a host, not proof that data was sent.*")
        appendLine()
        appendLine("- Label: ${PlayLabelRecord.sourceWords(record.source)}, attached ${date(record.addedAtMs)}")
        labelLines(check.label).forEach { appendLine("- $it") }
        check.label.notes.forEach { appendLine("- Reading note: $it") }
        val review = check.toReview
        val info = check.points.filter { it.informational }
        if (review.isNotEmpty()) {
            appendLine()
            appendLine("**Points to review (${review.size})**")
            appendLine()
            review.forEachIndexed { i, p ->
                appendLine("${i + 1}. ${p.text}")
                p.caveat?.let { appendLine("   *$it*") }
            }
        } else if (check.label.readable) {
            appendLine()
            appendLine("No points to review in this comparison.")
        }
        if (info.isNotEmpty()) {
            appendLine()
            appendLine("**For information**")
            appendLine()
            info.forEach { appendLine("- ${it.text}") }
        }
        if (check.notCompared.isNotEmpty()) {
            appendLine()
            appendLine("**Not compared**")
            appendLine()
            check.notCompared.forEach { appendLine("- $it") }
        }
        if (check.observed.isNotEmpty()) {
            appendLine()
            appendLine("**Sources behind the SDK expectations**")
            appendLine()
            check.observed.forEach { appendLine("- ${sourceLine(it)}") }
            appendLine()
            appendLine("*${SdkExpectations.MAPPING_NOTE}*")
        }
        appendLine()
        appendLine("*$RESEARCH_CONTEXT*")
    }

    /** One SDK's expectation with its source, for reports and the command line. */
    fun sourceLine(o: ObservedSdk): String {
        val e = o.expectation
        val seen = "seen ${o.relations.joinToString(", ")}"
        if (!e.verified) return "${o.entity}: ${SdkExpectations.UNVERIFIED} (${PlayLabelComparator.join(e.categories.map { it.label })}; $seen)."
        val quotes = e.types.joinToString("; ") { "${it.category.label}: \"${it.quote}\"" }
        return "${o.entity} ($seen): $quotes. ${e.sourceTitle}, ${e.sourceUrl}, read ${e.checked}."
    }

    /** Puts [section] before "## Method and limitations" when the report has it, else at the end. */
    fun insertInto(markdown: String, section: String): String {
        val at = markdown.indexOf("\n## Method and limitations")
        val block = section.trimEnd() + "\n"
        return if (at < 0) markdown.trimEnd() + "\n\n" + block
        else markdown.substring(0, at).trimEnd() + "\n\n" + block + markdown.substring(at)
    }

    /** Plain text for `traceveil-ci check --play-label`. */
    fun text(check: PlayLabelCheck, labelFile: String): String = buildString {
        appendLine("$TITLE ($labelFile)")
        appendLine("  Separate from the score and the policy result. A label is the developer's own declaration;")
        appendLine("  a lookup is an attempt to contact a host, not proof that data was sent.")
        if (!check.label.readable) {
            appendLine("  ${PlayLabelComparator.UNREADABLE}")
            return@buildString
        }
        labelLines(check.label).forEach { appendLine("  $it") }
        val review = check.toReview
        if (review.isEmpty()) appendLine("  No points to review in this comparison.")
        else {
            appendLine("  Points to review (${review.size}):")
            review.forEachIndexed { i, p ->
                appendLine("    ${i + 1}. ${p.text}")
                p.caveat?.let { appendLine("       Note: $it") }
            }
        }
        check.points.filter { it.informational }.takeIf { it.isNotEmpty() }?.let { info ->
            appendLine("  For information:")
            info.forEach { appendLine("    - ${it.text}") }
        }
        if (check.notCompared.isNotEmpty()) {
            appendLine("  Not compared:")
            check.notCompared.forEach { appendLine("    - $it") }
        }
        if (check.observed.isNotEmpty()) {
            appendLine("  Sources:")
            check.observed.forEach { o ->
                val e = o.expectation
                appendLine("    - ${o.entity}: " + if (e.verified) "${e.sourceUrl} (read ${e.checked})" else SdkExpectations.UNVERIFIED)
            }
        }
    }

    private fun date(ms: Long): String =
        if (ms <= 0) "at an unrecorded time" else DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US).withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(ms))
}
