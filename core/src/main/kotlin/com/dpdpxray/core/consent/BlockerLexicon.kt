package com.dpdpxray.core.consent

import com.dpdpxray.core.model.UiNode

/** The kinds of screen that stand between an app's launch and its consent screen: nothing to do with consent itself. */
enum class BlockerKind { SIGN_IN, OTP, CAPTCHA, BIOMETRIC, FORM }

/**
 * Recognises sign-in, verification-code, CAPTCHA and biometric/PIN screens from the accessibility tree, and the few
 * harmless buttons that get past them without an account ("Skip", "Not now", "Continue as guest"). TraceVeil never types
 * a password or code and never taps a social-login button: it only ever taps a label from [isSkipLabel] (or completes a
 * harmless profile form with test values, see [FormFill]), and otherwise
 * asks the person to do the step themselves. A screen that is itself a consent screen is never a blocker.
 */
object BlockerLexicon {
    private val SIGN_IN_CUES = listOf(
        "log in", "login", "sign in", "signin", "sign up", "signup", "register", "create account", "create an account",
        "phone number", "mobile number", "email address", "username", "user name", "forgot password",
        "लॉग इन", "साइन इन", "रजिस्टर", "मोबाइल नंबर", "पासवर्ड",
    )
    private val SOCIAL_LOGIN = listOf(
        "continue with google", "continue with facebook", "continue with apple", "sign in with google",
        "sign in with facebook", "sign in with apple", "log in with facebook", "login with google",
    )
    private val OTP_CUES = listOf(
        "otp", "one time password", "one-time password", "verification code", "enter code", "enter the code",
        "6 digit", "6-digit", "4 digit", "4-digit", "resend code", "resend otp", "ओटीपी",
    )
    private val CAPTCHA_CUES = listOf(
        "captcha", "not a robot", "verify you are human", "verify youre human", "select all images",
    )
    private val BIOMETRIC_CUES = listOf(
        "fingerprint", "touch the sensor", "face unlock", "use biometric", "biometric", "enter your pin", "enter pin",
        "enter passcode",
    )

    /** Buttons that skip a gate without creating an account or sending anything. Matched whole, after normalising. */
    private val SKIP_LABELS = setOf(
        "skip", "skip for now", "skip login", "skip sign in", "skip this", "not now", "maybe later", "later",
        "remind me later", "no thanks", "continue as guest", "guest", "browse as guest", "use as guest",
        "continue without account", "continue without an account", "continue without signing in",
        "continue without login", "explore", "explore first", "ill do it later", "do it later",
        "छोड़ें", "अभी नहीं", "बाद में", "अतिथि के रूप में जारी रखें", "ಬಿಟ್ಟುಬಿಡಿ",
    )

    /** The kind of blocker on this screen, or null. Consent screens are never blockers. */
    fun detect(tree: UiNode): BlockerKind? {
        val nodes = tree.flatten()
        val labels = nodes.map { it.label }
        if (ConsentLexicon.isConsentScreen(labels)) return null
        val text = labels.map(::normalize).filter { it.isNotEmpty() }.joinToString(" | ")
        fun has(cues: List<String>) = cues.any { text.contains(it) }
        val editable = nodes.any { it.isEditable || it.className.endsWith("EditText") }
        return when {
            has(CAPTCHA_CUES) -> BlockerKind.CAPTCHA
            nodes.any { it.isPassword } -> BlockerKind.SIGN_IN
            editable && has(OTP_CUES) -> BlockerKind.OTP
            editable && has(SIGN_IN_CUES) -> BlockerKind.SIGN_IN
            has(SOCIAL_LOGIN) && has(SIGN_IN_CUES) -> BlockerKind.SIGN_IN
            has(BIOMETRIC_CUES) -> BlockerKind.BIOMETRIC
            FormFill.pickerConfirmIndex(tree) != null -> BlockerKind.FORM
            editable && FormFill.forwardIndex(tree) != null -> BlockerKind.FORM
            else -> null
        }
    }

    /**
     * What the person is asked to fill in on this screen, as the app labels it ("Phone number", "Password"): for each
     * field, its placeholder, else its description, else the nearest text just before it. A password field never gives up
     * its content, and what was typed into any field is never read. At most [MAX_FIELDS] distinct, short labels.
     */
    fun fieldLabels(tree: UiNode): List<String> {
        val nodes = tree.flatten()
        val labels = mutableListOf<String>()
        nodes.forEachIndexed { i, n ->
            if (!isField(n)) return@forEachIndexed
            val label = fieldLabel(nodes, i)
            // A field that already holds something is not what the person is being asked for.
            if (isFilled(n, label)) return@forEachIndexed
            if (!label.isNullOrEmpty() && label !in labels) labels += label
        }
        return labels.take(MAX_FIELDS)
    }

    internal fun isField(n: UiNode) = n.isEditable || n.isPassword || n.className.endsWith("EditText")

    /** The app's label for the field at [index]: placeholder, else description, else the text just before it. */
    internal fun fieldLabel(nodes: List<UiNode>, index: Int): String? {
        val n = nodes[index]
        val own = n.hint ?: n.contentDescription?.takeIf { it.isNotBlank() }
        val before = nodes.subList(0, index).asReversed().firstOrNull { p ->
            !p.isEditable && !p.isClickable && p.children.isEmpty() && p.text?.isNotBlank() == true && p.text!!.length <= MAX_LABEL
        }?.text
        return (own ?: (if (n.isPassword) "Password" else null) ?: before)?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_LABEL }
    }

    /** True when something has been typed: text that is neither empty nor just the placeholder shown back. */
    internal fun isFilled(n: UiNode, label: String?): Boolean {
        val t = n.text?.trim().orEmpty()
        return !n.isPassword && t.isNotEmpty() && t != n.hint?.trim() && t != label
    }

    internal fun normalized(raw: String) = normalize(raw)

    private const val MAX_FIELDS = 3
    private const val MAX_LABEL = 40

    /** True for a button that safely gets past a gate. Never true for sign-in, social-login or account-creation buttons. */
    fun isSkipLabel(label: String?): Boolean {
        val text = normalize(label ?: return false)
        return text.isNotEmpty() && text.length <= MAX_SKIP_LENGTH && text in SKIP_LABELS
    }

    private const val MAX_SKIP_LENGTH = 40

    private fun normalize(raw: String): String = raw.lowercase()
        .replace(Regex("[’'`,.!?:;()\\[\\]{}\"*]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}
