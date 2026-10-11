package com.dpdpxray.core.consent

import com.dpdpxray.core.model.ConsentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsentLexiconTest {

    @Test
    fun `english accept buttons`() {
        for (label in listOf("Accept", "Accept all", "I Agree", "Agree & Continue", "Allow", "Got it!", "ACCEPT AND CONTINUE")) {
            assertEquals(label, ConsentKind.ACCEPT, ConsentLexicon.classifyButton(label))
        }
    }

    @Test
    fun `english reject buttons, including ones that contain accept words`() {
        for (label in listOf("Reject", "Reject all", "Decline", "Don't allow", "No thanks", "Continue without accepting", "Only necessary")) {
            assertEquals(label, ConsentKind.REJECT, ConsentLexicon.classifyButton(label))
        }
    }

    @Test
    fun `settings buttons`() {
        for (label in listOf("Manage", "Manage preferences", "Customise", "More options")) {
            assertEquals(label, ConsentKind.SETTINGS, ConsentLexicon.classifyButton(label))
        }
    }

    @Test
    fun `hindi and kannada buttons`() {
        assertEquals(ConsentKind.ACCEPT, ConsentLexicon.classifyButton("मैं सहमत हूँ"))
        assertEquals(ConsentKind.ACCEPT, ConsentLexicon.classifyButton("स्वीकार करें"))
        assertEquals(ConsentKind.REJECT, ConsentLexicon.classifyButton("अस्वीकार करें"))
        assertEquals(ConsentKind.ACCEPT, ConsentLexicon.classifyButton("ಒಪ್ಪುತ್ತೇನೆ"))
    }

    @Test
    fun `website consent tools' refusal wording, including phrases that start with accept or continue`() {
        for (label in listOf(
            "Continue without agreeing", "Continue without agreeing →", "Do not consent", "I do not accept", "I do not agree",
            "Necessary cookies only", "Accept necessary cookies", "Accept only essential cookies", "Allow necessary cookies",
            "Allow only essential cookies", "Reject all cookies", "Deny", "सभी अस्वीकार करें",
        )) {
            assertEquals(label, ConsentKind.REJECT, ConsentLexicon.classifyButton(label))
        }
    }

    @Test
    fun `website consent tools' settings and accept wording`() {
        for (label in listOf("Cookie settings", "Cookies Settings", "Cookie preferences", "Privacy settings")) {
            assertEquals(label, ConsentKind.SETTINGS, ConsentLexicon.classifyButton(label))
        }
        for (label in listOf("Accept All Cookies", "Allow all", "Agree and close", "Accept & continue", "सभी स्वीकार करें")) {
            assertEquals(label, ConsentKind.ACCEPT, ConsentLexicon.classifyButton(label))
        }
    }

    @Test
    fun `long sentences and unrelated labels are not buttons`() {
        assertNull(ConsentLexicon.classifyButton("By continuing you agree to the terms that you accept when you use this application"))
        assertNull(ConsentLexicon.classifyButton("Add to cart"))
        assertNull(ConsentLexicon.classifyButton(""))
        assertNull(ConsentLexicon.classifyButton(null))
    }

    @Test
    fun `detects a consent screen from cue words`() {
        assertTrue(ConsentLexicon.isConsentScreen(listOf("We value your privacy", "We use your personal data to personalise ads", "Accept")))
        assertTrue(ConsentLexicon.isConsentScreen(listOf("आपकी गोपनीयता", "सहमति", "स्वीकार करें")))
        assertTrue(ConsentLexicon.isConsentScreen(listOf("Privacy policy", "Accept")))
    }

    @Test
    fun `an ordinary screen is not a consent screen`() {
        assertFalse(ConsentLexicon.isConsentScreen(listOf("Trending products", "Add to cart", "Continue")))
        assertFalse(ConsentLexicon.isConsentScreen(listOf("Read our privacy policy")))
    }
}
