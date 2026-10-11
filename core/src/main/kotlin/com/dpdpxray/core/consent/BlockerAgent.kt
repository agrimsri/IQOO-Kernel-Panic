package com.dpdpxray.core.consent

import com.dpdpxray.core.model.UiNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Lets an on-device model get past a screen no rule was written for (a welcome tour, an age gate, a language picker, a
 * permission primer, an unfamiliar form) on the way to an app's consent screen.
 *
 * The split is deliberate. The model only *proposes* one step from a numbered list of what is on screen. Everything that
 * could do harm is decided here, in code, and cannot be talked round by anything on the screen or in the model's reply:
 * - it never types a value of its own: it names the *kind* of a harmless field (name, birth date, age, city) and this code
 *   supplies an obvious test value; identifying or authenticating fields (phone, email, password, codes, address, IDs, cards)
 *   are always handed to the person;
 * - it never taps sign-in, sign-up, social-login, payment, subscription, install or delete buttons, and never makes the
 *   consent choice itself (Accept or Reject belong to the audit plan, not to this step);
 * - a step that does not match what is on screen, or repeats a failed one, becomes a hand-over to the person.
 */
object BlockerAgent {
    /** What kind of field the model says it is looking at. The value typed is chosen from this, never by the model. */
    enum class FieldKind(val value: String?) {
        NAME(FormFill.TEST_NAME), BIRTH_DATE(FormFill.TEST_BIRTH), AGE(FormFill.TEST_AGE), CITY("Delhi"), OTHER("Test"), SENSITIVE(null);

        companion object {
            fun of(raw: String?): FieldKind = entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: SENSITIVE
        }
    }

    sealed interface Step {
        val reason: String

        data class Tap(val index: Int, val label: String, override val reason: String = "") : Step
        data class Type(val index: Int, val label: String, val kind: FieldKind, val value: String, override val reason: String = "") : Step
        data class Wait(override val reason: String = "") : Step
        /** [vetoed] is true when the safety check refused what the model chose, false when the model itself gave up. */
        data class HandOff(override val reason: String, val vetoed: Boolean = true) : Step
    }

    /** What the model is shown, and how its numbers map back to positions in [UiNode.flatten]. */
    class Digest(val text: String, private val positions: List<Int>) {
        /** Position in the flattened tree for the number the model used, or null when it is not one of the numbers shown. */
        fun position(number: Int?): Int? = number?.let { positions.getOrNull(it - 1) }
        val isEmpty get() = positions.isEmpty()
    }

    private const val MAX_ELEMENTS = 45
    private const val MAX_LABEL = 70

    /** A numbered list of what is on screen. Text typed into fields is never included, only whether they are empty. */
    fun digest(tree: UiNode): Digest {
        val nodes = tree.flatten()
        val lines = mutableListOf<String>()
        val positions = mutableListOf<Int>()
        fun add(i: Int, text: String) {
            if (positions.size >= MAX_ELEMENTS) return
            positions += i
            lines += "${positions.size}. $text"
        }
        // A date or number picker or a dropdown is one control: what is inside it is not listed on its own.
        val insidePicker = mutableSetOf<Int>()
        var pickerNote: String? = null
        nodes.forEachIndexed { i, n ->
            if (isPicker(n) || n.className.endsWith("Spinner")) { val size = n.flatten().size; for (k in i + 1 until i + size) insidePicker += k }
        }
        nodes.forEachIndexed { i, n ->
            if (i in insidePicker) return@forEachIndexed
            if (isPicker(n)) {
                pickerNote = "a date or number picker is open, showing: ${clip(n.flatten().mapNotNull { it.text?.takeIf { t -> t.isNotBlank() } }.joinToString(" "))}"
                return@forEachIndexed
            }
            val label = clip(n.label.ifBlank { descendantLabel(n) })
            when {
                BlockerLexicon.isField(n) -> {
                    val name = BlockerLexicon.fieldLabel(nodes, i)?.let { "\"$it\"" } ?: "(unlabelled)"
                    val state = if (n.isPassword) "password field, hidden" else if (BlockerLexicon.isFilled(n, BlockerLexicon.fieldLabel(nodes, i))) "already filled" else "empty"
                    add(i, "text field $name, $state")
                }
                n.className.endsWith("Spinner") -> add(i, "dropdown ${if (label.isNotEmpty()) "\"$label\"" else ""} (chooser)".replace("  ", " "))
                n.isCheckable -> add(i, "${if (n.className.contains("Switch")) "switch" else "checkbox"} \"$label\", ${if (n.isChecked) "on" else "off"}")
                n.isClickable && label.isNotEmpty() -> add(i, "button \"$label\"")
                n.children.isEmpty() && !n.isClickable && n.text?.isNotBlank() == true -> add(i, "text \"${clip(n.text!!.trim())}\"")
            }
        }
        pickerNote?.let { note ->
            val ok = FormFill.pickerConfirmIndex(tree)?.let { positions.indexOf(it) }?.takeIf { it >= 0 }
            lines += "Note: $note. " + (ok?.let { "Confirm it by tapping button ${it + 1}." } ?: "Confirm it with its OK button.")
        }
        // When there is nothing left to fill in, say so and name the way on: a small model otherwise keeps looking for fields.
        val filled = lines.count { it.contains("text field") && it.endsWith("already filled") }
        val empty = lines.count { it.contains("text field") && !it.endsWith("already filled") && !it.contains("password") }
        val forward = FormFill.forwardIndex(tree)?.let { positions.indexOf(it) }?.takeIf { it >= 0 }
        if (filled > 0 && empty == 0 && forward != null) {
            lines += "Note: every text field is filled in. The way forward is to tap button ${forward + 1}."
        }
        return Digest(lines.joinToString("\n"), positions)
    }

    private fun isPicker(n: UiNode) = n.className.endsWith("DatePicker") || n.className.endsWith("TimePicker") || n.className.endsWith("NumberPicker")

    private fun descendantLabel(n: UiNode): String = n.children.firstNotNullOfOrNull { c ->
        c.label.ifBlank { descendantLabel(c) }.takeIf { it.isNotBlank() }
    }.orEmpty()

    private fun clip(s: String) = s.replace('\n', ' ').trim().let { if (it.length > MAX_LABEL) it.take(MAX_LABEL) + "…" else it }

    val SYSTEM = """
        You operate a phone for a privacy audit. An app is showing a screen that is NOT its privacy or consent screen: it may be a
        welcome tour, a profile or age form, a language or country picker, a permission primer, a loading screen, or a sign-in wall.
        Your goal is to get past it to the app's own consent or privacy-choice screen without signing in, creating an account,
        paying, or entering any real personal detail.

        You are given a numbered list of what is on screen. Choose ONE step. Reply with one JSON object and nothing else:
        {"action": "tap" | "type" | "wait" | "hand_off", "target": number, "field_kind": "name" | "birth_date" | "age" | "city" | "other" | "sensitive", "reason": short sentence}

        Rules:
        - Prefer a button such as Skip, Not now, Maybe later, Continue as guest, Next, Continue, Get started, OK or Done.
        - For a plain profile form (name, date of birth, age, city) use "type", one field at a time, saying what kind of field it is
          with field_kind; the app supplies a harmless test value. Then tap the Continue button.
        - A date-of-birth field is also "type" with field_kind "birth_date". If a note says a picker is open, tap the OK button it names.
        - If a field asks for a phone number, email, password, code, address, ID or card number, or you need any real detail,
          answer "hand_off". Also answer "hand_off" if signing in or creating an account is the only way forward.
        - Never tap Sign in, Log in, Sign up, Register, Continue with Google/Facebook/Apple, Buy, Pay, Subscribe, Install or Delete.
        - Never tap Accept, Reject, Allow or Deny on a privacy choice: that is not your job.
        - When every text field is already filled in, do not type again: tap the Continue or Next button.
        - If the screen is just loading, answer "wait". If you are unsure, answer "hand_off".
        - "target" is the number in the list. It is required for tap and type. A screenshot may be attached to help you read the screen.
    """.trimIndent()

    val JSON_SCHEMA = """
        {"type":"object","properties":{
          "action":{"type":"string","enum":["tap","type","wait","hand_off"]},
          "target":{"type":"integer"},
          "field_kind":{"type":"string","enum":["name","birth_date","age","city","other","sensitive"]},
          "reason":{"type":"string"}
        },"required":["action","reason"]}
    """.trimIndent()

    fun userMessage(digest: Digest, history: List<String>, screenshotAttached: Boolean = false): String = buildString {
        if (screenshotAttached) {
            append("A screenshot of this same screen is attached. Use it to understand icons, images and text the list misses, ")
            append("but only ever choose a target from the numbered list.\n\n")
        }
        append("On screen now:\n").append(digest.text.ifEmpty { "(nothing readable)" })
        if (history.isNotEmpty()) {
            append("\n\nSteps already taken on this screen (if it did not change, choose something different or hand_off):\n")
            history.takeLast(4).forEach { append("- ").append(it).append('\n') }
        }
        append("\nChoose the next step.")
    }

    /** What the model said, before any safety check. Null when the reply cannot be read as a step. */
    private data class Proposal(val action: String, val target: Int?, val kind: FieldKind, val reason: String)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun proposal(reply: String): Proposal? {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val o: JsonObject = runCatching { json.parseToJsonElement(reply.substring(start, end + 1)).jsonObject }.getOrNull() ?: return null
        val action = o["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() ?: return null
        return Proposal(
            action = action,
            target = o["target"]?.jsonPrimitive?.let { it.intOrNull ?: it.contentOrNull?.trim()?.toIntOrNull() },
            kind = FieldKind.of(o["field_kind"]?.jsonPrimitive?.contentOrNull),
            reason = (o["reason"]?.jsonPrimitive?.contentOrNull ?: "").replace('\n', ' ').trim().take(140),
        )
    }

    /** Taps that are never made, whatever the model says. Matched on the normalised label. */
    private val NEVER_TAP = listOf(
        "sign in", "signin", "log in", "login", "sign up", "signup", "register", "create account", "create an account",
        "continue with", "sign in with", "buy", "pay", "purchase", "subscribe", "upgrade", "donate", "delete", "remove account",
        "uninstall", "install", "update now", "send otp", "get otp", "verify", "call now", "share",
        "साइन इन", "लॉग इन", "रजिस्टर", "खरीदें", "भुगतान",
    )

    private val MOVE_ON = setOf("continue", "ok", "okay", "next", "done", "get started", "start", "proceed")

    fun isForbiddenTap(label: String): Boolean {
        val text = BlockerLexicon.normalized(label)
        if (text.isEmpty()) return true
        if (BlockerLexicon.isSkipLabel(label)) return false
        // Buttons that make a privacy choice (Accept, Agree, Allow, Reject, Deny, Yes...) belong to the audit plan; only the
        // plain "move on" words are safe even though the consent lexicon would read them as Accept.
        if (ConsentLexicon.classifyButton(label) != null && text !in MOVE_ON) return true
        return NEVER_TAP.any { cue -> if (cue.length <= 3) text.split(" ").contains(cue) else text.contains(cue) }
    }

    /**
     * The step to take for the model's [reply] on the screen [tree] that [digest] was made from. Always safe to carry out:
     * anything that fails a check, or cannot be read, comes back as [Step.HandOff].
     */
    fun decide(reply: String, tree: UiNode, digest: Digest): Step {
        val p = proposal(reply) ?: return Step.HandOff("The model's reply could not be read.")
        when (p.action) {
            "wait" -> return Step.Wait(p.reason)
            "hand_off", "handoff", "hand off" -> return Step.HandOff(p.reason.ifEmpty { "The model could not find a safe way forward." }, vetoed = false)
        }
        val nodes = tree.flatten()
        val at = digest.position(p.target) ?: return Step.HandOff("The model chose something that is not on the screen.")
        val node = nodes[at]
        return when (p.action) {
            "tap" -> {
                val label = node.label.ifBlank { nodes.drop(at).firstOrNull { it.label.isNotBlank() }?.label.orEmpty() }
                when {
                    BlockerLexicon.isField(node) -> Step.HandOff("The model chose a text field to tap.")
                    !node.isClickable -> Step.HandOff("The model chose something that cannot be tapped.")
                    isForbiddenTap(label) -> Step.HandOff("The model chose “$label”, which TraceVeil never taps.")
                    else -> Step.Tap(at, label, p.reason)
                }
            }
            "type" -> {
                val label = BlockerLexicon.fieldLabel(nodes, at)
                when {
                    !BlockerLexicon.isField(node) -> Step.HandOff("The model chose something that is not a text field.")
                    node.isPassword -> Step.HandOff("This is a password field; only the person fills it in.")
                    label != null && FormFill.isSensitive(label) -> Step.HandOff("“$label” would need a real personal detail.")
                    p.kind.value == null -> Step.HandOff("The model said this field needs a real personal detail.")
                    BlockerLexicon.isFilled(node, label) -> Step.HandOff("The field is already filled.")
                    else -> Step.Type(at, label ?: "a field", p.kind, p.kind.value!!, p.reason)
                }
            }
            else -> Step.HandOff("The model chose an action TraceVeil does not know.")
        }
    }
}
