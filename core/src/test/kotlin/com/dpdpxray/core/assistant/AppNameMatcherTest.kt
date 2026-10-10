package com.dpdpxray.core.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNameMatcherTest {
    private fun app(label: String, pkg: String = "com.example." + label.lowercase().filter { it.isLetterOrDigit() }) = AppCandidate(label, pkg)

    private val instagram = app("Instagram", "com.instagram.android")
    private val instagramLite = app("Instagram Lite", "com.instagram.lite")
    private val whatsapp = app("WhatsApp", "com.whatsapp")
    private val whatsappBusiness = app("WhatsApp Business", "com.whatsapp.w4b")
    private val youtube = app("YouTube", "com.google.android.youtube")
    private val youtubeMusic = app("YouTube Music", "com.google.android.apps.youtube.music")
    private val leaky = app("LeakyShop", "com.leakyshop.demo")
    private val leakyFixed = app("LeakyShop Fixed", "com.leakyshop.demo.fixed")
    private val google = app("Google", "com.google.android.googlequicksearchbox")
    private val playStore = app("Google Play Store", "com.android.vending")
    private val telegram = app("Telegram", "org.telegram.messenger")
    private val spotify = app("Spotify", "com.spotify.music")
    private val facebook = app("Facebook", "com.facebook.katana")
    private val installed = listOf(
        instagram, instagramLite, whatsapp, whatsappBusiness, youtube, youtubeMusic, leaky, leakyFixed, google, playStore, telegram, spotify, facebook,
    )

    private fun match(q: String) = AppNameMatcher.match(q, installed)

    @Test fun `an exact label wins, ignoring case and spacing`() {
        assertEquals(AppMatch.Found(instagram), match("instagram"))
        assertEquals(AppMatch.Found(instagram), match("INSTAGRAM"))
        assertEquals(AppMatch.Found(whatsapp), match("whats app"))
        assertEquals(AppMatch.Found(leaky), match("leaky shop"))
        assertEquals(AppMatch.Found(leakyFixed), match("leakyshop fixed"))
        assertEquals(AppMatch.Found(google), match("google"))
        assertEquals(AppMatch.Found(playStore), match("google play store"))
    }

    @Test fun `a small misspelling still finds the app`() {
        assertEquals(AppMatch.Found(instagram), match("instagran"))
        assertEquals(AppMatch.Found(telegram), match("telegramm"))
        assertEquals(AppMatch.Found(spotify), match("spotifi"))
    }

    @Test fun `words of a longer label are matched`() {
        assertEquals(AppMatch.Found(playStore), match("play store"))
        assertEquals(AppMatch.Found(youtubeMusic), match("youtube music"))
    }

    @Test fun `an unclear name gives the two best options`() {
        val insta = match("insta")
        assertTrue(insta is AppMatch.Ambiguous)
        assertEquals(setOf(instagram, instagramLite), (insta as AppMatch.Ambiguous).options.toSet())
        assertEquals(2, insta.options.size)
        val leakyShops = AppNameMatcher.match("shop", listOf(leaky, leakyFixed, spotify))
        assertTrue(leakyShops is AppMatch.Ambiguous)
    }

    @Test fun `app names said in Devanagari match their Latin labels`() {
        assertEquals(AppMatch.Found(instagram), match("इंस्टाग्राम"))
        assertEquals(AppMatch.Found(whatsapp), match("व्हाट्सऐप"))
        assertEquals(AppMatch.Found(youtube), match("यूट्यूब"))
        assertEquals(AppMatch.Found(spotify), match("स्पॉटिफाई"))
        assertEquals(AppMatch.Found(facebook), match("फ़ेसबुक"))
    }

    @Test fun `an app that is not installed is not found`() {
        assertEquals(AppMatch.NotFound, match("netflix"))
        assertEquals(AppMatch.NotFound, match("bank"))
        assertEquals(AppMatch.NotFound, match(""))
        assertEquals(AppMatch.NotFound, AppNameMatcher.match("instagram", emptyList()))
    }

    @Test fun `transliteration and phonetic keys`() {
        assertEquals("instagram", AppNameMatcher.latin("इंस्टाग्राम"))
        assertEquals("fesbuk", AppNameMatcher.latin("फ़ेसबुक"))
        val key = { s: String -> AppNameMatcher.skeleton(AppNameMatcher.phonetic(s)) }
        assertEquals(key("whatsapp"), key(AppNameMatcher.latin("व्हाट्सएप")))
        assertEquals(listOf("leaky", "shop"), AppNameMatcher.words("LeakyShop"))
    }
}
