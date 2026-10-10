package com.dpdpxray.core.copilot

/**
 * The verdicts TraceVeil never gives: an app is never called safe, compliant, certified, malicious or illegal, a finding is
 * never called a violation, and a DNS lookup is never described as data being sent. The model prompt already forbids
 * these; this check runs on the model's actual reply, so a reply that ignores the prompt is replaced by rules text.
 */
object VerdictWords {
    private val flags = setOf(RegexOption.IGNORE_CASE)

    /** Checked in order; the first match is reported, so the more specific phrase comes first. */
    private val verdicts = listOf(
        Regex("\\bsafe to (use|install|download)\\b", flags),
        Regex("\\b(is|are|looks|seems|appears)\\s+((completely|totally|perfectly|fully)\\s+)?safe\\b", flags),
        Regex("\\bunsafe\\b", flags),
        Regex("\\b(non[- ]?)?compliant\\b", flags),
        Regex("\\billegal(ly)?\\b", flags),
        Regex("\\b(breaks|breaking|against) the law\\b", flags),
        Regex("\\bviolat(ion|ions|es|ed|ing)\\b", flags),
        Regex("\\b(certified|malicious|fraudulent|spyware)\\b", flags),
        Regex("अवैध|गैर[- ]?कानूनी|ग़ैरक़ानूनी|उल्लंघन|(पूरी तरह|बिल्कुल) सुरक्षित"),
    )

    /** "Data was sent" claims, unless the sentence negates them ("not proof that data was shared"). */
    private val dataSent = Regex("\\bdata (was|were|is being) (sent|shared|transmitted|uploaded|leaked)\\b", flags)
    private val negation = Regex("\\b(not|no|never|without)\\b|n't", flags)

    /** The first verdict phrase in [text], lower-cased, or null when the text makes no verdict. */
    fun find(text: String): String? {
        verdicts.firstNotNullOfOrNull { it.find(text) }?.let { return it.value.lowercase() }
        return dataSent.findAll(text)
            .firstOrNull { m -> !negation.containsMatchIn(text.substring(maxOf(0, m.range.first - 30), m.range.first)) }
            ?.value?.lowercase()
    }
}
