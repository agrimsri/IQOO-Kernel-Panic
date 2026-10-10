package com.dpdpxray.app.ui.screens

import com.dpdpxray.core.datasafety.PlayLabelCheck

/** Wording of the "Play Data safety label vs what we saw" card, kept here so tests can read it. */
internal object PlayLabelCopy {
    const val TITLE = "Play Data safety label vs what we saw"
    const val PASTE = "Paste label text"
    const val OPEN_FILE = "Open a text file"
    const val DEMO = "Use demo label (LeakyShop)"
    const val REPLACE = "Replace label"
    const val REMOVE = "Remove label"
    const val CAVEAT = "A label is the developer's own declaration; Google says Play's review is not designed to verify it. " +
        "A lookup is an attempt to contact a host, not proof that data was sent."
    const val EMPTY_SUMMARY = "Paste this app's Play label to compare it with what this check saw"
    const val EMPTY_HINT = "On the app's Google Play page, open \"Data safety\", copy the text (or \"See details\") and paste it " +
        "here. TraceVeil compares it on this phone: nothing is fetched or sent, and the score does not change."
    const val PASTE_TITLE = "Paste the Data safety text"
    const val PASTE_HINT = "English text from the app's Google Play listing, for example \"No data shared with third parties\" " +
        "and \"This app may collect these data types\". It is saved with this check only."
    const val DEMO_TAG = "Demo label written by TraceVeil, not a Play listing"
    const val SEPARATE_TAG = "Separate from the score"

    /** The LeakyShop demo label only makes sense on LeakyShop and on the built-in sample audit. */
    fun demoOffered(targetPackage: String, sample: Boolean): Boolean = sample || targetPackage.startsWith("com.leakyshop.")

    /** Everyday mode: one calm line when a label is attached, nothing otherwise. */
    fun everydayLine(check: PlayLabelCheck?): String? = check?.let {
        "Compared with this app's Play Data safety label: ${it.summary.replaceFirstChar { c -> c.lowercase() }}. " +
            "Developer mode shows the details."
    }
}
