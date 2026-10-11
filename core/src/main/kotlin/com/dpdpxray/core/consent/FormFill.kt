package com.dpdpxray.core.consent

import com.dpdpxray.core.model.UiNode

/**
 * Plans how to get past a profile form that stands between an app's launch and its consent screen ("Name", "Date of
 * Birth", then CONTINUE). Only harmless profile fields are ever filled, and only with obvious test values, never the
 * person's own details. Anything that could identify or authenticate someone (phone, email, password, codes, address, ID
 * or card numbers) is left alone and reported in [Plan.manual] so the person is asked for it instead.
 *
 * Indexes are positions in [UiNode.flatten] of the screen's tree.
 */
object FormFill {
    data class Fill(val index: Int, val label: String, val value: String)

    /** [fills] to type, [manual] labels that need the person, [forwardIndex] the Continue-style button (or null). */
    data class Plan(val fills: List<Fill>, val manual: List<String>, val forwardIndex: Int?)

    private val FORWARD = setOf(
        "continue", "next", "submit", "proceed", "get started", "start", "done", "save", "save and continue",
        "continue to app", "lets go", "lets start", "confirm", "go",
        "जारी रखें", "आगे बढ़ें", "आगे", "सबमिट", "शुरू करें",
    )

    /** Fields that identify or authenticate a person: never filled, even with test values. */
    private val SENSITIVE = listOf(
        "phone", "mobile", "email", "e-mail", "password", "passcode", "pin", "otp", "code", "address", "aadhaar",
        "aadhar", "pan", "card", "account", "ifsc", "upi", "cvv", "ssn", "passport", "licence", "license", "username",
        "user name", "referral", "coupon", "फ़ोन", "मोबाइल", "ईमेल", "पासवर्ड", "पता",
    )

    private val NAME = listOf("name", "नाम")
    private val BIRTH = listOf("date of birth", "birth", "dob", "जन्म")

    /** Test values: obviously not a real person, so nothing the app records is anyone's actual detail. */
    const val TEST_NAME = "Test User"
    const val TEST_BIRTH = "01/01/1990"
    const val TEST_AGE = "30"

    private fun words(label: String) = BlockerLexicon.normalized(label).split(" ")

    private fun hasWord(label: String, cues: List<String>): Boolean {
        val text = BlockerLexicon.normalized(label)
        val w = words(label)
        return cues.any { cue -> if (cue.contains(' ') || cue.length > 3) text.contains(cue) else cue in w }
    }

    /** True when a field called [label] could identify or authenticate someone. */
    fun isSensitive(label: String): Boolean = hasWord(label, SENSITIVE)

    /** The value to type for a field called [label], or null when it is not a safe profile field. */
    fun valueFor(label: String): String? = when {
        hasWord(label, SENSITIVE) -> null
        hasWord(label, BIRTH) -> TEST_BIRTH
        hasWord(label, listOf("age", "आयु")) -> TEST_AGE
        hasWord(label, NAME) -> TEST_NAME
        else -> null
    }

    private val PICKER_CONFIRM = setOf("ok", "done", "set", "select", "confirm", "save")

    /** The OK button of a date or number picker dialog (a form's date-of-birth field opens one), or null. */
    fun pickerConfirmIndex(tree: UiNode): Int? {
        val nodes = tree.flatten()
        if (nodes.none { it.className.endsWith("DatePicker") || it.className.endsWith("NumberPicker") }) return null
        return nodes.indexOfFirst { it.isClickable && BlockerLexicon.normalized(it.label) in PICKER_CONFIRM }.takeIf { it >= 0 }
    }

    fun forwardIndex(tree: UiNode): Int? = tree.flatten().indexOfFirst { n ->
        n.isClickable && !isFieldNode(n) && BlockerLexicon.normalized(n.label) in FORWARD
    }.takeIf { it >= 0 }

    private fun isFieldNode(n: UiNode) = BlockerLexicon.isField(n)

    fun plan(tree: UiNode): Plan {
        val nodes = tree.flatten()
        val fills = mutableListOf<Fill>()
        val manual = mutableListOf<String>()
        nodes.forEachIndexed { i, n ->
            if (!BlockerLexicon.isField(n)) return@forEachIndexed
            val label = BlockerLexicon.fieldLabel(nodes, i)
            if (BlockerLexicon.isFilled(n, label)) return@forEachIndexed
            val value = if (n.isPassword || label == null) null else valueFor(label)
            if (value != null) fills += Fill(i, label!!, value) else manual += (label ?: "a field")
        }
        return Plan(fills, manual.distinct(), forwardIndex(tree))
    }
}
