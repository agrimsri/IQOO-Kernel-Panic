package com.dpdpxray.core.apk

enum class ServiceCategory { BANK_OR_PAYMENT, GOVERNMENT }

data class NameMatch(val keyword: String, val category: ServiceCategory)

/**
 * A deliberately weak, INFERRED heuristic: does an app's label or package name contain a word that is common in the
 * names of Indian bank, payment or government services? Public advisories (CERT-In e-Challan, pension and KYC lures)
 * describe fake apps that borrow such names. A match never says the app is fake; a miss never says it is genuine.
 */
object ServiceNameHeuristic {
    /** Long words match anywhere inside a word ("hdfcbank", "echallan"). */
    private val LONG_WORDS: List<Pair<String, ServiceCategory>> = listOf(
        "challan" to ServiceCategory.GOVERNMENT,
        "parivahan" to ServiceCategory.GOVERNMENT,
        "pension" to ServiceCategory.GOVERNMENT,
        "kisan" to ServiceCategory.GOVERNMENT,
        "aadhaar" to ServiceCategory.GOVERNMENT,
        "aadhar" to ServiceCategory.GOVERNMENT,
        "uidai" to ServiceCategory.GOVERNMENT,
        "digilocker" to ServiceCategory.GOVERNMENT,
        "incometax" to ServiceCategory.GOVERNMENT,
        "yojana" to ServiceCategory.GOVERNMENT,
        "netbanking" to ServiceCategory.BANK_OR_PAYMENT,
        "bank" to ServiceCategory.BANK_OR_PAYMENT,
    )

    /** Short words match only as a whole word, so "cupid" is not "upi" and "porto" is not "rto". */
    private val WHOLE_WORDS: List<Pair<String, ServiceCategory>> = listOf(
        "rto" to ServiceCategory.GOVERNMENT,
        "epfo" to ServiceCategory.GOVERNMENT,
        "gov" to ServiceCategory.GOVERNMENT,
        "pmkisan" to ServiceCategory.GOVERNMENT,
        "sbi" to ServiceCategory.BANK_OR_PAYMENT,
        "yono" to ServiceCategory.BANK_OR_PAYMENT,
        "upi" to ServiceCategory.BANK_OR_PAYMENT,
        "bhim" to ServiceCategory.BANK_OR_PAYMENT,
        "kyc" to ServiceCategory.BANK_OR_PAYMENT,
        "ekyc" to ServiceCategory.BANK_OR_PAYMENT,
    )

    fun match(label: String?, packageName: String): NameMatch? {
        val words = (words(label.orEmpty()) + words(packageName)).toSet()
        // Government words first, then bank and payment words, in catalogue order: deterministic for any input.
        val ordered = (LONG_WORDS.map { Triple(it.first, it.second, false) } + WHOLE_WORDS.map { Triple(it.first, it.second, true) })
            .sortedBy { it.second != ServiceCategory.GOVERNMENT }
        for ((keyword, category, whole) in ordered) {
            val hit = if (whole) keyword in words else words.any { keyword in it }
            if (hit) return NameMatch(keyword, category)
        }
        // Two-word phrases written apart: "PM Kisan" is covered by "kisan"; "Income Tax" needs both words.
        val all = words(label.orEmpty())
        if (all.zipWithNext().any { (a, b) -> a == "income" && b == "tax" }) return NameMatch("income tax", ServiceCategory.GOVERNMENT)
        return null
    }

    /** Lower-case words split on punctuation, spaces and camelCase ("SBIYono" -> sbi, yono; "mParivahan" -> m, parivahan). */
    internal fun words(text: String): List<String> =
        text.split(Regex("[^A-Za-z0-9]+"))
            .flatMap { it.split(Regex("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")) }
            .map { it.lowercase() }
            .filter { it.isNotEmpty() }
}
