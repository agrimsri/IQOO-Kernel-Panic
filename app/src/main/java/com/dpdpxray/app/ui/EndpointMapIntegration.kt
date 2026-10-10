package com.dpdpxray.app.ui

import com.dpdpxray.core.geo.CountryNames
import com.dpdpxray.core.geo.LegalContext
import com.dpdpxray.core.geo.OwnerRegistry
import com.dpdpxray.core.trackers.TrackerCatalog
import java.time.LocalDate

internal object EndpointMapIntegration {
    /** The one ownership mapping: a view over the catalogue's cited company facts, never a second file. */
    fun owners(catalog: TrackerCatalog): OwnerRegistry = OwnerRegistry.fromCatalog(catalog)

    fun legalContext(today: LocalDate = LocalDate.now()): LegalContext {
        val commencement = if (today < LocalDate.of(2027, 5, 13))
            "As of $today, Section 3 is not yet in force; G.S.R.843(E) of 13 November 2025 schedules it for 13 May 2027. This is published-framework readiness context. "
        else "Verify the latest commencement notices and amendments before relying on this published framework. "
        return LegalContext.DPDP_INDIA.copy(
            name = "DPDP Act, 2023 (India), selected Section 3 scope clauses",
            caveat = commencement + "The excerpts are formatted/condensed; read the full section, including its exceptions. " +
                "No applicable jurisdiction or violation is established by this audit. Other jurisdictions are not assessed. Not legal advice. " +
                "Official Act: https://www.indiacode.nic.in/bitstream/123456789/22037/2/a2023-22.pdf . " +
                "Commencement: https://upload.indiacode.nic.in/showfile?actid=AC_CEN_45_0_00003_2023-22_1763464807080&filename=c56ceae6c383460ca69577428d36828b.pdf&type=hindinotification",
        )
    }

    /** Natural Earth is public domain and needs no credit; it is named anyway so the outline's source stays visible. */
    const val OUTLINE_CREDIT = "Country outlines: Natural Earth (public domain)."

    /** One line under the map naming highlighted countries the outline cannot draw (too small at 1:110m), or null. */
    fun undrawnNote(undrawn: List<String>): String? = when (undrawn.size) {
        0 -> null
        1 -> "Not drawn at this scale: ${CountryNames.name(undrawn[0])}. The list below includes it."
        else -> "Not drawn at this scale: ${undrawn.joinToString(", ") { CountryNames.name(it) }}. The list below includes them."
    }
}
