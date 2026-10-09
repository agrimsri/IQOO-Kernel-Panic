package com.dpdpxray.core.copilot

/**
 * Checks that an answer cites only passages it was given. Citations are bracketed corpus ids such as `[s9(3)]`;
 * a bare "Section 33" mention is rejected because it could not have come from the retrieved passages.
 */
object CitationValidator {
    private val bracketed = Regex("\\[([a-z]+[0-9]*(?:\\([0-9a-z]+\\))?)]")
    private val bareSection = Regex("\\b(section|rule|धारा|नियम)\\s*[0-9]+", RegexOption.IGNORE_CASE)

    fun citedIds(text: String): List<String> = bracketed.findAll(text).map { it.groupValues[1] }.distinct().toList()

    fun isGrounded(text: String, allowedIds: Set<String>): Boolean =
        citedIds(text).all { it in allowedIds } && !bareSection.containsMatchIn(text)
}
