package com.dpdpxray.app.data

import com.dpdpxray.core.law.LegalLens
import com.dpdpxray.core.law.LegalProfile
import com.dpdpxray.core.law.LensRegions

/**
 * The legal lens the app uses: the person's choice in Settings, or automatic (device region, else the global baseline).
 * Pure so the mapping can be tested without Android; the app passes Locale.getDefault().country as [region].
 */
data class LensChoice(
    val profile: LegalProfile,
    /** True when no lens was chosen and the region decided. */
    val automatic: Boolean,
    /** The region that decided an automatic choice, as reported by the device ("" when unknown). */
    val region: String,
    /** Extra advice for regions where no single lens fits (for example the US). */
    val hint: String?,
) {
    /** "Automatic: EU / EEA (device region DE)" or "India (chosen in Settings)". */
    fun describe(lens: LegalLens = LegalLens.default): String {
        val name = lens.profile(profile).name
        return if (automatic) "Automatic: $name" + (if (region.isNotBlank()) " (device region $region)" else " (device region unknown)")
        else "$name (chosen in Settings)"
    }
}

object LegalLensSetting {
    /** Stored value for "automatic". */
    const val AUTOMATIC = ""

    fun resolve(setting: String, region: String?, lens: LegalLens = LegalLens.default): LensChoice {
        val chosen = LegalProfile.parse(setting)
        val cc = region?.trim()?.uppercase().orEmpty()
        return if (chosen != null) LensChoice(chosen, automatic = false, region = cc, hint = null)
        else LensChoice(LensRegions.forRegion(cc, lens), automatic = true, region = cc, hint = LensRegions.hint(cc))
    }
}
