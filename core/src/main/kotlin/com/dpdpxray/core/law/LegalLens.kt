package com.dpdpxray.core.law

import com.dpdpxray.core.dpdp.CitationService
import com.dpdpxray.core.dpdp.Passage
import com.dpdpxray.core.model.AuditResult
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.Finding
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.rules.ScoreCalculator
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Legal lenses: the same observed findings, read against different laws.
 *
 * A lens never changes the evidence or the consent-first score. It changes which legal text is quoted and how each check
 * relates to that law: REQUIRED (the law sets a rule the check tests), CONTEXT (relevant, but not strictly required, or
 * only in some cases) or NOT_APPLICABLE (the law does not speak to what the check tests). The mapping is data
 * (resources/law/profiles.json); a check without an entry gets the profile's fallback, so new checks can be mapped later
 * by adding entries. Passages (resources/law/passages.json) carry their source URL, retrieval date and a verbatim flag.
 * India keeps the existing DPDP corpus and each finding's own references.
 */

/** The legal lenses TraceVeil ships. Saved and exported by name: add new values at the end and never rename one. */
@Serializable
enum class LegalProfile {
    INDIA, EU, UK, US_CALIFORNIA, US_CHILDREN, BRAZIL, SINGAPORE, GLOBAL_BASELINE;

    companion object {
        /** Used when no lens is chosen and the region has no profile. */
        val DEFAULT = GLOBAL_BASELINE

        /** "eu", "US-California", "us_children" and "GLOBAL_BASELINE" all work; null when nothing matches. */
        fun parse(text: String?): LegalProfile? {
            val key = text?.trim()?.uppercase()?.replace('-', '_')?.replace(' ', '_')?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { it.name == key }
        }
    }
}

@Serializable
enum class LawRelation(val label: String) {
    REQUIRED("Required"),
    CONTEXT("Context"),
    NOT_APPLICABLE("Not applicable"),
}

/** What a passage is, so a regulator's guidance or a good-practice line is never shown as statute. */
@Serializable
enum class PassageKind(val label: String) {
    LAW("Law"),
    RECITAL("Recital (guides interpretation)"),
    GUIDANCE("Regulator guidance, not law"),
    GOOD_PRACTICE("Good practice, not law"),
}

/** Whether a profile is a law or a good-practice baseline. */
@Serializable
enum class ProfileKind { LAW, GOOD_PRACTICE }

/** Where a profile's passages come from: the existing DPDP corpus (India) or law/passages.json. */
@Serializable
enum class PassageSource { DPDP_CORPUS, LENS }

@Serializable
data class LensPassage(
    val id: String,
    val profile: String,
    val source: String,
    val title: String,
    val text: String,
    val kind: PassageKind = PassageKind.LAW,
    /** True only when [text] was copied exactly from [url] (whitespace collapsed; " … " marks an omission). */
    val verbatim: Boolean,
    /** ISO 639-1 code of [text]. */
    val language: String = "en",
    val url: String? = null,
    /** ISO date the source was read. */
    val retrieved: String? = null,
    /** A plain-English summary of a non-English passage. Always a summary, never an official translation. */
    val englishSummary: String? = null,
) {
    /** "verbatim" or "summary", plus the language when it is not English. */
    val wording: String get() = (if (verbatim) "verbatim" else "summary") + if (language != "en") " · ${language.uppercase()} original" else ""
}

@Serializable
data class CheckMapping(
    val relation: LawRelation,
    val note: String,
    val refs: List<String> = emptyList(),
)

@Serializable
data class LensProfile(
    val id: String,
    val name: String,
    val laws: String,
    val kind: ProfileKind = ProfileKind.LAW,
    val status: String,
    val scope: String = "",
    /** ISO 3166-1 alpha-2 regions whose device locale selects this lens by default. */
    val regions: List<String> = emptyList(),
    val passageSource: PassageSource = PassageSource.LENS,
    /** Only India keeps its existing statutory penalty ceilings; every other lens shows no penalty figure. */
    val penaltyFigures: Boolean = false,
    val limitations: List<String> = emptyList(),
    val fallback: CheckMapping,
    val checks: Map<String, CheckMapping> = emptyMap(),
) {
    val isLaw: Boolean get() = kind == ProfileKind.LAW

    /** "India · Digital Personal Data Protection Act 2023 and DPDP Rules 2025". */
    val title: String get() = "$name · $laws"

    /** The chip text: "Required" under a law, "Good practice" under the baseline. */
    fun relationLabel(r: LawRelation): String = if (!isLaw && r == LawRelation.REQUIRED) "Good practice" else r.label
}

@Serializable
private data class ProfilesFile(val schema: String, val profiles: List<LensProfile>)

/** One finding seen through a lens. [mapped] is false when the profile's fallback was used. */
data class LensFinding(val finding: Finding, val mapping: CheckMapping, val mapped: Boolean) {
    val relation: LawRelation get() = mapping.relation
}

/**
 * The result read under one lens. [score] ignores NOT_APPLICABLE findings and is otherwise the consent-first arithmetic;
 * it is a reading of the findings, never a legal verdict. V0 (low visibility) keeps it inconclusive under every lens.
 */
data class LensView(
    val id: LegalProfile,
    val profile: LensProfile,
    val findings: List<LensFinding>,
    val score: Int?,
    val band: ScoreBand,
    /** The consent-history range of the result read (unknown history), so the line names it instead of "inconclusive". */
    val historyBounds: com.dpdpxray.core.model.HistoryBounds? = null,
) {
    val required: List<LensFinding> get() = findings.filter { it.relation == LawRelation.REQUIRED }
    val context: List<LensFinding> get() = findings.filter { it.relation == LawRelation.CONTEXT }
    val notApplicable: List<LensFinding> get() = findings.filter { it.relation == LawRelation.NOT_APPLICABLE }

    fun of(check: CheckId): LensFinding? = findings.firstOrNull { it.finding.checkId == check }

    /** "Score under the EU / EEA lens: 79/100 · Needs work" or the inconclusive equivalent. */
    val scoreLine: String
        get() = "Score under the ${profile.name} lens: " +
            when {
                (score == null || band == ScoreBand.INCONCLUSIVE) && historyBounds != null ->
                    "depends on consent history (consent-first range ${historyBounds.range})"
                score == null || band == ScoreBand.INCONCLUSIVE -> "inconclusive"
                else -> "$score/100 · ${band.label}"
            }

    /** What the lens score counts and skips, and that it is not a verdict. */
    val scoreNote: String
        get() {
            val na = notApplicable.map { it.finding.checkId.code }
            val skipped = if (na.isEmpty()) "skips none" else "skips ${na.size} not applicable here (${na.joinToString()})"
            return "Counts the findings this lens marks ${profile.relationLabel(LawRelation.REQUIRED)} or Context and $skipped. " +
                "A reading of the evidence, not a legal verdict."
        }
}

class LegalLens(profiles: List<LensProfile>, val passages: List<LensPassage>) {
    private val byId: Map<LegalProfile, LensProfile> = profiles.mapNotNull { p -> LegalProfile.parse(p.id)?.let { it to p } }.toMap()
    private val passageById = passages.associateBy { it.id }

    val profiles: List<LegalProfile> get() = LegalProfile.entries.filter { it in byId }

    fun profile(id: LegalProfile): LensProfile = byId[id] ?: error("No legal profile data for $id")

    /** The mapping for a check code ("C1", or a future "C11"), falling back to the profile's generic entry. */
    fun mapping(id: LegalProfile, checkCode: String): CheckMapping = profile(id).let { it.checks[checkCode] ?: it.fallback }

    fun mapping(id: LegalProfile, check: CheckId): CheckMapping = mapping(id, check.code)

    fun isMapped(id: LegalProfile, checkCode: String): Boolean = checkCode in profile(id).checks

    fun passage(passageId: String): LensPassage? = passageById[passageId]

    /** The lens's own passages for a check, in the mapping's order. India uses [passagesFor] with its DPDP corpus. */
    fun passages(id: LegalProfile, checkCode: String): List<LensPassage> = mapping(id, checkCode).refs.mapNotNull { passageById[it] }

    /**
     * The legal text to show for [finding] under [id]. India keeps the existing citations (each finding's own DPDP
     * references through [citations]); every other lens shows its mapped passages. Info findings without references
     * show none, as before.
     */
    fun passagesFor(id: LegalProfile, finding: Finding, citations: CitationService?, k: Int = 3): List<LensPassage> =
        when (profile(id).passageSource) {
            PassageSource.DPDP_CORPUS -> citations?.cite(finding, k)?.map { it.asLensPassage() }.orEmpty()
            PassageSource.LENS -> passages(id, finding.checkId.code)
        }

    /** The relation of one check under every profile, for "Compare laws". */
    fun compare(checkCode: String): List<Pair<LegalProfile, CheckMapping>> = profiles.map { it to mapping(it, checkCode) }

    fun view(result: AuditResult, id: LegalProfile): LensView {
        val p = profile(id)
        val findings = result.findings.map { f -> LensFinding(f, mapping(id, f.checkId.code), f.checkId.code in p.checks) }
        val (score, band) = scoreUnder(findings)
        return LensView(id, p, findings, score, band, result.historyBounds)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        val default: LegalLens by lazy { loadDefault() }

        fun loadDefault(): LegalLens {
            val profiles = json.decodeFromString(ProfilesFile.serializer(), resource("/law/profiles.json")).profiles
            val passages = json.decodeFromString<List<LensPassage>>(resource("/law/passages.json"))
            return LegalLens(profiles, passages)
        }

        /** Consent-first arithmetic over the findings that apply under the lens (NOT_APPLICABLE ones are skipped). */
        fun scoreUnder(findings: List<LensFinding>): Pair<Int?, ScoreBand> =
            ScoreCalculator.compute(findings.filter { it.relation != LawRelation.NOT_APPLICABLE }.map { it.finding })

        private fun resource(path: String): String = LegalLens::class.java.getResourceAsStream(path)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: error("$path missing from resources")
    }
}

/** A DPDP corpus passage in the lens shape (India). The corpus records no URL, so none is invented. */
fun Passage.asLensPassage(): LensPassage = LensPassage(
    id = id, profile = LegalProfile.INDIA.name, source = source, title = title, text = text, kind = PassageKind.LAW, verbatim = verbatim,
)

/** Picks the default lens from the device region (Locale country), else the global baseline. */
object LensRegions {
    fun forRegion(country: String?, lens: LegalLens = LegalLens.default): LegalProfile {
        val cc = country?.trim()?.uppercase()?.takeIf { it.length == 2 } ?: return LegalProfile.DEFAULT
        return lens.profiles.firstOrNull { cc in lens.profile(it).regions } ?: LegalProfile.DEFAULT
    }

    /** A hint for regions where no single lens fits, shown next to the automatic choice. */
    fun hint(country: String?): String? = when (country?.trim()?.uppercase()) {
        "US" -> "No single US privacy law covers every app. Choose California (CCPA) or US children (COPPA) if they apply."
        else -> null
    }
}
