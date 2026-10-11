package com.dpdpxray.core.consent

import com.dpdpxray.core.model.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockerLexiconTest {
    private fun screen(vararg kids: UiNode) = UiNode(className = "android.widget.FrameLayout", children = kids.toList())
    private fun text(t: String) = UiNode(className = "android.widget.TextView", text = t)
    private fun button(t: String) = UiNode(className = "android.widget.Button", text = t, isClickable = true)
    private fun field(hint: String? = null, password: Boolean = false) =
        UiNode(className = "android.widget.EditText", text = hint, isEditable = true, isPassword = password)

    @Test fun `a password field is a sign-in blocker`() {
        assertEquals(BlockerKind.SIGN_IN, BlockerLexicon.detect(screen(text("Welcome back"), field(), field(password = true), button("Log in"))))
    }

    @Test fun `a one-time-code screen is an OTP blocker`() {
        assertEquals(BlockerKind.OTP, BlockerLexicon.detect(screen(text("Enter the code we sent you"), field(), button("Verify"))))
    }

    @Test fun `a phone number box with sign-up wording is a sign-in blocker`() {
        assertEquals(BlockerKind.SIGN_IN, BlockerLexicon.detect(screen(text("Sign up"), field("Mobile number"), button("Continue"))))
    }

    @Test fun `social login buttons alone are a sign-in blocker`() {
        assertEquals(BlockerKind.SIGN_IN, BlockerLexicon.detect(screen(text("Sign in to continue"), button("Continue with Google"), button("Continue with Facebook"))))
    }

    @Test fun `a captcha is a blocker`() {
        assertEquals(BlockerKind.CAPTCHA, BlockerLexicon.detect(screen(text("Please verify you are human"), text("I'm not a robot"))))
    }

    @Test fun `a consent screen is never a blocker even with a sign-in field`() {
        val consent = screen(text("We value your privacy and use cookies"), field(password = true), button("Accept all"), button("Reject all"))
        assertNull(BlockerLexicon.detect(consent))
    }

    @Test fun `an ordinary screen is not a blocker`() {
        assertNull(BlockerLexicon.detect(screen(text("Home"), text("Today's offers"), button("Shop now"))))
    }

    @Test fun `only whole skip labels are safe to tap`() {
        listOf("Skip", "skip for now", "Not now", "Maybe later", "Continue as guest", "No thanks", "अभी नहीं", "Continue without signing in")
            .forEach { assertTrue("$it should skip", BlockerLexicon.isSkipLabel(it)) }
        listOf("Log in", "Sign up", "Continue with Google", "Create account", "Skip the tutorial and buy", "Continue", "Allow", null, "")
            .forEach { assertFalse("$it must not be tapped", BlockerLexicon.isSkipLabel(it)) }
    }

    private fun input(hint: String? = null, description: String? = null, typed: String? = null, password: Boolean = false) =
        UiNode(className = "android.widget.EditText", text = typed, hint = hint, contentDescription = description, isEditable = true, isPassword = password)

    @Test fun `field labels use the placeholder, then the description, then the text just before`() {
        val tree = screen(
            text("Welcome back"), input(hint = "Phone number"),
            text("Your email"), input(),
            input(description = "Referral code"),
        )
        assertEquals(listOf("Phone number", "Your email", "Referral code"), BlockerLexicon.fieldLabels(tree))
    }

    @Test fun `field labels never include what was typed and call a nameless password box Password`() {
        val tree = screen(input(hint = "Email", typed = "me@example.com"), input(typed = "hunter2", password = true))
        assertEquals(listOf("Email", "Password"), BlockerLexicon.fieldLabels(tree))
    }

    @Test fun `field labels are distinct, short and at most three`() {
        val tree = screen(input(hint = "A"), input(hint = "A"), input(hint = "B"), input(hint = "C"), input(hint = "D"), input(hint = "x".repeat(60)))
        assertEquals(listOf("A", "B", "C"), BlockerLexicon.fieldLabels(tree))
        assertTrue(BlockerLexicon.fieldLabels(screen(text("Hello"), button("Go"))).isEmpty())
    }
}
