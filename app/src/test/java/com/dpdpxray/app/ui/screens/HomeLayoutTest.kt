package com.dpdpxray.app.ui.screens

import com.dpdpxray.app.data.Audience
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLayoutTest {
    private val both = listOf(Audience.USER, Audience.DEVELOPER)

    @Test fun `the mode switch sits right under the header in both modes`() {
        both.forEach { a ->
            val s = HomeLayout.sections(a, updatedApps = 0, trustAvailable = true)
            assertEquals(HomeSection.MODE_SWITCH, s.first())
            assertEquals(HomeSection.HERO, s[1])
        }
    }

    @Test fun `the mode switch offers both modes, everyday first, named for who they are for`() {
        assertEquals(listOf(Audience.USER, Audience.DEVELOPER), HomeLayout.modes)
        assertEquals("Everyday · I use apps", HomeLayout.modeLabel(Audience.USER).en)
        assertEquals("Developer · I build apps", HomeLayout.modeLabel(Audience.DEVELOPER).en)
        both.forEach { assertTrue(HomeLayout.modeLabel(it).hi.isNotBlank()) }
    }

    @Test fun `Update Watch shows only while at least one checked app has updated`() {
        both.forEach { a ->
            assertFalse(HomeSection.UPDATES in HomeLayout.sections(a, updatedApps = 0, trustAvailable = true))
            assertTrue(HomeSection.UPDATES in HomeLayout.sections(a, updatedApps = 1, trustAvailable = true))
            assertTrue(HomeSection.UPDATES in HomeLayout.sections(a, updatedApps = 4, trustAvailable = true))
        }
    }

    @Test fun `the trust card is on Home in both modes when the trust screen exists`() {
        both.forEach { a ->
            assertTrue(HomeSection.TRUST in HomeLayout.sections(a, updatedApps = 0, trustAvailable = true))
            assertFalse(HomeSection.TRUST in HomeLayout.sections(a, updatedApps = 0, trustAvailable = false))
        }
    }

    @Test fun `sections keep the agreed order`() {
        assertEquals(
            listOf(HomeSection.MODE_SWITCH, HomeSection.HERO, HomeSection.UPDATES, HomeSection.RECENT, HomeSection.TRUST, HomeSection.MORE_WAYS),
            HomeLayout.sections(Audience.DEVELOPER, updatedApps = 2, trustAvailable = true),
        )
        assertEquals(
            listOf(HomeSection.MODE_SWITCH, HomeSection.HERO, HomeSection.RECENT, HomeSection.TRUST, HomeSection.MORE_WAYS),
            HomeLayout.sections(Audience.USER, updatedApps = 0, trustAvailable = true),
        )
    }

    @Test fun `everyday mode shows only the file and phone rows under More ways to check`() {
        assertEquals(
            listOf(HomeWay.APK_FILE, HomeWay.PHONE_APPS),
            HomeLayout.ways(Audience.USER, checkupAvailable = true, automationAvailable = true, hasLastCheck = true),
        )
        assertEquals(listOf(HomeWay.APK_FILE), HomeLayout.ways(Audience.USER, checkupAvailable = false, automationAvailable = true, hasLastCheck = true))
    }

    @Test fun `developer mode adds its quick actions one tap from Home`() {
        assertEquals(
            listOf(HomeWay.APK_FILE, HomeWay.PHONE_APPS, HomeWay.AUTOMATE, HomeWay.ASK_LAST, HomeWay.CAMERA, HomeWay.SAMPLE, HomeWay.MORE_SAMPLES),
            HomeLayout.ways(Audience.DEVELOPER, checkupAvailable = true, automationAvailable = true, hasLastCheck = true),
        )
        // "Ask about my last check" needs a check to ask about; automation needs its screen wired.
        val noHistory = HomeLayout.ways(Audience.DEVELOPER, checkupAvailable = true, automationAvailable = false, hasLastCheck = false)
        assertFalse(HomeWay.ASK_LAST in noHistory)
        assertFalse(HomeWay.AUTOMATE in noHistory)
        assertTrue(listOf(HomeWay.CAMERA, HomeWay.SAMPLE, HomeWay.MORE_SAMPLES).all { it in noHistory })
    }
}
