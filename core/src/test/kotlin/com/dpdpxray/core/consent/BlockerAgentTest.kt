package com.dpdpxray.core.consent

import com.dpdpxray.core.consent.BlockerAgent.FieldKind
import com.dpdpxray.core.consent.BlockerAgent.Step
import com.dpdpxray.core.model.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockerAgentTest {
    private fun screen(vararg kids: UiNode) = UiNode(className = "android.widget.FrameLayout", children = kids.toList())
    private fun text(t: String) = UiNode(className = "android.widget.TextView", text = t)
    private fun button(t: String) = UiNode(className = "android.widget.Button", text = t, isClickable = true)
    private fun input(hint: String, typed: String? = null, password: Boolean = false) =
        UiNode(className = "android.widget.EditText", text = typed, hint = hint, isEditable = true, isPassword = password)

    /** A welcome screen no rule knows: a tour with Skip, plus a form. */
    private val tour = screen(text("Welcome to Zodiac"), button("Skip"), input("Nickname"), button("Next"))
    private val form = screen(text("Tell us about you"), input("Name"), input("Mobile number"), input("Password", password = true), button("Continue"), button("Sign in"))

    private fun decide(reply: String, tree: UiNode) = BlockerAgent.decide(reply, tree, BlockerAgent.digest(tree))
    private fun numberOf(tree: UiNode, needle: String): Int {
        val lines = BlockerAgent.digest(tree).text.lines()
        return lines.first { it.contains(needle) }.substringBefore('.').toInt()
    }

    @Test fun `the digest numbers what is on screen and never shows typed text`() {
        val tree = screen(text("Hello"), input("Name", typed = "Asha Verma"), input("Password", typed = "hunter2", password = true), button("Next"))
        val d = BlockerAgent.digest(tree).text
        assertTrue(d.contains("1. text \"Hello\""))
        assertTrue(d.contains("text field \"Name\", already filled"))
        assertTrue(d.contains("password field, hidden"))
        assertTrue(d.contains("button \"Next\""))
        assertFalse("typed content must not reach the model", d.contains("Asha") || d.contains("hunter2"))
    }

    @Test fun `a tap on a skip or next button is allowed`() {
        val n = numberOf(tour, "\"Skip\"")
        val step = decide("""{"action":"tap","target":$n,"reason":"skip the tour"}""", tour)
        assertEquals("Skip", (step as Step.Tap).label)
    }

    @Test fun `a harmless field is typed with a test value chosen here, not by the model`() {
        val n = numberOf(form, "\"Name\"")
        val step = decide("""{"action":"type","target":$n,"field_kind":"name","reason":"profile name","value":"Rahul Sharma"}""", form)
        assertEquals(FormFill.TEST_NAME, (step as Step.Type).value)
    }

    @Test fun `identifying fields are always handed to the person`() {
        for (field in listOf("\"Mobile number\"", "password field")) {
            val n = numberOf(form, field)
            val step = decide("""{"action":"type","target":$n,"field_kind":"name","reason":"fill it"}""", form)
            assertTrue("$field must be handed over, was $step", step is Step.HandOff)
        }
        val n = numberOf(form, "\"Name\"")
        assertTrue(decide("""{"action":"type","target":$n,"field_kind":"sensitive","reason":"needs real data"}""", form) is Step.HandOff)
    }

    @Test fun `sign-in and consent choices are never tapped, even when the model asks`() {
        val tree = screen(button("Sign in"), button("Continue with Google"), button("Buy now"), button("Accept all"), button("Reject all"), button("Allow"), button("Continue"))
        for (label in listOf("Sign in", "Continue with Google", "Buy now", "Accept all", "Reject all", "Allow")) {
            val n = numberOf(tree, "\"$label\"")
            assertTrue("$label must not be tapped", decide("""{"action":"tap","target":$n,"reason":"go"}""", tree) is Step.HandOff)
        }
        assertTrue(decide("""{"action":"tap","target":${numberOf(tree, "\"Continue\"")},"reason":"go"}""", tree) is Step.Tap)
    }

    @Test fun `a target that is not on the screen or not tappable becomes a hand-over`() {
        assertTrue(decide("""{"action":"tap","target":99,"reason":"x"}""", tour) is Step.HandOff)
        assertTrue(decide("""{"action":"tap","target":${numberOf(tour, "Welcome")},"reason":"x"}""", tour) is Step.HandOff)
        assertTrue(decide("""{"action":"tap","target":${numberOf(tour, "\"Nickname\"")},"reason":"x"}""", tour) is Step.HandOff)
    }

    @Test fun `replies are read tolerantly and an unreadable one is a hand-over`() {
        val n = numberOf(tour, "\"Next\"")
        val fenced = "Sure!\n```json\n{\"action\": \"tap\", \"target\": \"$n\", \"reason\": \"next\"}\n```"
        assertTrue(decide(fenced, tour) is Step.Tap)
        assertTrue(decide("I think you should press next.", tour) is Step.HandOff)
        assertTrue(decide("""{"action":"wait","reason":"loading"}""", tour) is Step.Wait)
        assertTrue(decide("""{"action":"launch_missiles","target":1,"reason":"x"}""", tour) is Step.HandOff)
    }

    @Test fun `an already filled field is not typed into again`() {
        val tree = screen(input("Name", typed = "Test User"), button("Continue"))
        assertTrue(decide("""{"action":"type","target":1,"field_kind":"name","reason":"x"}""", tree) is Step.HandOff)
    }

    @Test fun `field kinds map to fixed test values and unknown kinds count as sensitive`() {
        assertEquals(FormFill.TEST_BIRTH, FieldKind.of("birth_date").value)
        assertEquals(FieldKind.SENSITIVE, FieldKind.of("ssn"))
        assertEquals(null, FieldKind.SENSITIVE.value)
    }

    @Test fun `the prompt carries the screen and recent steps`() {
        val msg = BlockerAgent.userMessage(BlockerAgent.digest(tour), listOf("Typed a test name into “Nickname”"))
        assertTrue(msg.contains("button \"Skip\"") && msg.contains("Typed a test name"))
    }

    @Test fun `an open date picker is one control to confirm, not fields to fill`() {
        val picker = UiNode(
            className = "android.widget.DatePicker",
            children = listOf(
                UiNode(className = "android.widget.NumberPicker", children = listOf(UiNode(className = "android.widget.EditText", text = "Jan", isEditable = true))),
                UiNode(className = "android.widget.NumberPicker", children = listOf(UiNode(className = "android.widget.EditText", text = "1990", isEditable = true))),
            ),
        )
        val tree = screen(text("Please enter the following details"), picker, button("Cancel"), button("OK"))
        val d = BlockerAgent.digest(tree).text
        assertTrue(d.contains("picker is open"))
        assertFalse("inner number fields must not be listed as text fields", d.contains("text field"))
        val ok = numberOf(tree, "\"OK\"")
        assertTrue(decide("""{"action":"tap","target":$ok,"reason":"confirm the date"}""", tree) is Step.Tap)
    }

    @Test fun `a refusal by the safety check can be told apart from the model giving up`() {
        val gaveUp = decide("""{"action":"hand_off","reason":"needs a real phone number"}""", tour) as Step.HandOff
        assertFalse(gaveUp.vetoed)
        val vetoed = decide("""{"action":"tap","target":99,"reason":"x"}""", tour) as Step.HandOff
        assertTrue(vetoed.vetoed)
    }
}
