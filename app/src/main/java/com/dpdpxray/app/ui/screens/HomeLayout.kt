package com.dpdpxray.app.ui.screens

import com.dpdpxray.app.data.Audience
import com.dpdpxray.app.ui.guide.Bi
import com.dpdpxray.app.ui.modes.ModeCopy
import com.dpdpxray.app.ui.onboarding.OnboardingCopy

/** The parts of Home below its header (logo, title, language and settings), top to bottom. */
enum class HomeSection { MODE_SWITCH, HERO, UPDATES, RECENT, TRUST, MORE_WAYS }

/** The rows of Home's "More ways to check". */
enum class HomeWay { APK_FILE, PHONE_APPS, AUTOMATE, ASK_LAST, CAMERA, SAMPLE, MORE_SAMPLES }

/**
 * What Home shows, decided without Compose so it can be unit tested. Both modes share one Home: the mode switch, the
 * hero, recent checks, the trust card and "More ways to check" are always there (the trust card when the build has the
 * trust screen); Update Watch appears only while a checked app has a different version installed; developer mode adds
 * its quick actions as extra rows under "More ways to check", one tap from Home.
 */
object HomeLayout {
    fun sections(audience: Audience, updatedApps: Int, trustAvailable: Boolean): List<HomeSection> = buildList {
        add(HomeSection.MODE_SWITCH)
        add(HomeSection.HERO)
        if (updatedApps > 0) add(HomeSection.UPDATES)
        add(HomeSection.RECENT)
        if (trustAvailable) add(HomeSection.TRUST)
        add(HomeSection.MORE_WAYS)
    }

    /**
     * The "More ways to check" rows. Everyday mode: an app file and (when the build has it) the apps on this phone.
     * Developer mode adds, in this order: Automate it (when wired), Ask about my last check (when there is one), Camera
     * check, See a sample and More samples.
     */
    fun ways(audience: Audience, checkupAvailable: Boolean, automationAvailable: Boolean, hasLastCheck: Boolean): List<HomeWay> = buildList {
        add(HomeWay.APK_FILE)
        if (checkupAvailable) add(HomeWay.PHONE_APPS)
        if (audience == Audience.DEVELOPER) {
            if (automationAvailable) add(HomeWay.AUTOMATE)
            if (hasLastCheck) add(HomeWay.ASK_LAST)
            add(HomeWay.CAMERA)
            add(HomeWay.SAMPLE)
            add(HomeWay.MORE_SAMPLES)
        }
    }

    /** The two segments of Home's mode switch, Everyday first, as the same Audience values Settings and onboarding set. */
    val modes: List<Audience> = listOf(Audience.USER, Audience.DEVELOPER)

    /** A segment's two lines: the mode's name ("Everyday") and who it is for ("I use apps"). */
    fun modeLines(a: Audience): Pair<Bi, Bi> = ModeCopy.name(a) to OnboardingCopy.audiences.first { it.audience == a }.title

    /** What TalkBack and the tests read for a segment: "Everyday · I use apps". */
    fun modeLabel(a: Audience): Bi = modeLines(a).let { (name, who) -> Bi("${name.en} · ${who.en}", "${name.hi} · ${who.hi}") }
}
