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

    @Test fun `field labels skip what was already typed and call a nameless password box Password`() {
        val tree = screen(input(hint = "Email", typed = "me@example.com"), input(typed = "hunter2", password = true))
        assertEquals(listOf("Password"), BlockerLexicon.fieldLabels(tree))
    }

    @Test fun `field labels are distinct, short and at most three`() {
        val tree = screen(input(hint = "A"), input(hint = "A"), input(hint = "B"), input(hint = "C"), input(hint = "D"), input(hint = "x".repeat(60)))
        assertEquals(listOf("A", "B", "C"), BlockerLexicon.fieldLabels(tree))
        assertTrue(BlockerLexicon.fieldLabels(screen(text("Hello"), button("Go"))).isEmpty())
    }

    private fun spinner() = UiNode(className = "android.widget.Spinner", isClickable = true)

    private fun profileForm() = screen(
        text("AstroGuru"), text("Please enter the following details to continue"),
        input(hint = "Name", typed = "Name"), input(hint = "Date of Birth", typed = "Date of Birth"), spinner(), button("CONTINUE"),
    )

    @Test fun `a profile form with a continue button is a form blocker`() {
        assertEquals(BlockerKind.FORM, BlockerLexicon.detect(profileForm()))
    }

    @Test fun `a lone search box is not a form blocker`() {
        assertNull(BlockerLexicon.detect(screen(text("Explore"), input(hint = "Search"), text("Trending"))))
    }

    @Test fun `the form plan fills name and date of birth with test values and finds continue`() {
        val tree = profileForm()
        val plan = FormFill.plan(tree)
        assertEquals(listOf("Name" to FormFill.TEST_NAME, "Date of Birth" to FormFill.TEST_BIRTH), plan.fills.map { it.label to it.value })
        assertTrue(plan.manual.isEmpty())
        assertEquals("CONTINUE", tree.flatten()[plan.forwardIndex!!].text)
        // The placeholder shown back is not "something typed", so both fields still count as empty.
        assertEquals(listOf("Name", "Date of Birth"), BlockerLexicon.fieldLabels(tree))
    }

    @Test fun `identifying fields are never filled and are left for the person`() {
        val tree = screen(
            text("Tell us about you"), input(hint = "Name"), input(hint = "Mobile number"), input(hint = "Email address"),
            input(hint = "Pin code"), input(hint = "Username"), button("Next"),
        )
        val plan = FormFill.plan(tree)
        assertEquals(listOf("Name"), plan.fills.map { it.label })
        assertEquals(listOf("Mobile number", "Email address", "Pin code", "Username"), plan.manual)
    }

    @Test fun `a field that already has text is neither filled nor asked for`() {
        val tree = screen(input(hint = "Name", typed = "Asha"), input(hint = "Date of Birth"), button("Continue"))
        assertEquals(listOf("Date of Birth"), FormFill.plan(tree).fills.map { it.label })
        assertEquals(listOf("Date of Birth"), BlockerLexicon.fieldLabels(tree))
    }

    @Test fun `a sign-in screen with a password stays a sign-in blocker, never a form`() {
        val tree = screen(text("Welcome"), input(hint = "Name"), input(password = true), button("Continue"))
        assertEquals(BlockerKind.SIGN_IN, BlockerLexicon.detect(tree))
        assertTrue(FormFill.plan(tree).manual.contains("Password"))
    }

    @Test fun `a date picker dialog is a form blocker and its OK button is found`() {
        val dialog = screen(
            UiNode(className = "android.widget.DatePicker", children = listOf(UiNode(className = "android.widget.NumberPicker", text = "1990"))),
            button("Cancel"), button("OK"),
        )
        assertEquals(BlockerKind.FORM, BlockerLexicon.detect(dialog))
        assertEquals("OK", dialog.flatten()[FormFill.pickerConfirmIndex(dialog)!!].text)
        assertNull(FormFill.pickerConfirmIndex(profileForm()))
    }
}
