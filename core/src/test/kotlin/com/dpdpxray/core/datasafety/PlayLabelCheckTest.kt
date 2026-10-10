package com.dpdpxray.core.datasafety

import com.dpdpxray.core.copilot.VerdictWords
import com.dpdpxray.core.datasafety.DataCategory.APP_ACTIVITY
import com.dpdpxray.core.datasafety.DataCategory.APP_INFO_AND_PERFORMANCE
import com.dpdpxray.core.datasafety.DataCategory.DEVICE_OR_OTHER_IDS
import com.dpdpxray.core.datasafety.DataCategory.LOCATION
import com.dpdpxray.core.datasafety.DataCategory.PERSONAL_INFO
import com.dpdpxray.core.datasafety.LabelPointKind.COLLECTED_NOT_SHARED
import com.dpdpxray.core.datasafety.LabelPointKind.DECLARED_NOT_EXPLAINED
import com.dpdpxray.core.datasafety.LabelPointKind.NO_COLLECTION_VS_SDKS
import com.dpdpxray.core.datasafety.LabelPointKind.NO_SHARING_VS_THIRD_PARTY_SDKS
import com.dpdpxray.core.datasafety.LabelPointKind.TYPE_NOT_DECLARED
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.trackers.AppOwner
import com.dpdpxray.core.trackers.AppOwners
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Play label vs what this check saw". Sessions are test-only copies of the device-shaped LeakyShop demo audits in
 * DemoScoreRegressionTest (leaky 21 / fixed 100), not device observations.
 */
class PlayLabelCheckTest {
    private val catalog = TrackerCatalog.loadDefault()
    private val comparator = PlayLabelComparator(catalog)
    private val leaky = DemoSessions.leaky
    private val fixed = DemoSessions.fixed

    private val demo = DataSafetyParser.parse(DemoLabels.LEAKYSHOP)

    @Test
    fun `demo label on the leaky LeakyShop audit gives the expected points, collection first`() {
        val c = comparator.compare(demo, leaky)
        assertEquals(
            listOf(TYPE_NOT_DECLARED, TYPE_NOT_DECLARED, TYPE_NOT_DECLARED, NO_SHARING_VS_THIRD_PARTY_SDKS),
            c.points.map { it.kind },
        )
        val ids = c.points[0]
        assertEquals(listOf(DEVICE_OR_OTHER_IDS), ids.categories)
        assertEquals(
            listOf("Google Analytics for Firebase", "Meta (Facebook SDK)", "Firebase Crashlytics", "AppsFlyer", "Google Ads / AdMob", "CleverTap"),
            ids.sdks,
        )
        assertTrue(ids.text, ids.text.startsWith("Label does not list Device or other IDs among collected or shared data, but this check saw lookups to"))
        assertTrue(ids.text, ids.text.contains("before consent"))
        assertTrue(ids.text, ids.text.contains("CleverTap") && ids.text.contains("unverified"))
        assertEquals(listOf(LOCATION), c.points[1].categories)
        assertEquals(listOf("Google Analytics for Firebase", "Google Ads / AdMob"), c.points[1].sdks)
        assertEquals(listOf(APP_INFO_AND_PERFORMANCE), c.points[2].categories)
        assertEquals(listOf("Firebase Crashlytics", "Google Ads / AdMob"), c.points[2].sdks)

        val sharing = c.points[3]
        assertEquals(listOf("Meta (Facebook SDK)", "AppsFlyer", "Google Ads / AdMob"), sharing.sdks)
        assertEquals(
            "Label says no data shared with third parties, while this check saw lookups to advertising or attribution SDK hosts: " +
                "Meta (Facebook SDK) and AppsFlyer before consent, and Google Ads / AdMob after Accept. Their documentation says " +
                "these SDKs can collect Device or other IDs and App activity. The documentation for Google Ads / AdMob says it " +
                "\"collects and shares the following data types automatically for advertising, analytics, and fraud prevention purposes.\"",
            sharing.text,
        )
        assertTrue(sharing.caveat!!.contains("service provider"))
        assertTrue(c.points.none { it.informational })
        assertEquals(6, c.observed.size)
    }

    @Test
    fun `the same label on the fixed build has no points and says why`() {
        val c = comparator.compare(demo, fixed)
        assertTrue(c.points.filterNot { it.informational }.isEmpty())
        assertTrue(c.observed.isEmpty())
        assertTrue(c.notCompared.any { it.contains("no lookups to catalogued third-party SDK hosts") })
        assertTrue(c.notCompared.any { it.contains("does not show the label is complete") })
    }

    @Test
    fun `no data collected is the strongest point and comes first`() {
        val c = comparator.compare(DataSafetyParser.parse(LabelFixtures.NOTHING), leaky)
        assertEquals(NO_COLLECTION_VS_SDKS, c.points.first().kind)
        assertTrue(c.points.first().text.startsWith("Label says no data collected, but this check saw lookups to Google Analytics for Firebase"))
        assertTrue(c.points.first().caveat!!.contains("Play counts data"))
        assertEquals(NO_SHARING_VS_THIRD_PARTY_SDKS, c.points[1].kind)
        assertTrue("no per-type points when the label says nothing is collected", c.points.none { it.kind == TYPE_NOT_DECLARED })
    }

    @Test
    fun `collected but not shared, when the label lists what it shares`() {
        val label = DataSafetyParser.parse(
            "This app may share these data types with third parties\nApp activity\n" +
                "This app may collect these data types\nApp activity, Device or other IDs, Location, App info and performance",
        )
        val c = comparator.compare(label, leaky)
        val notShared = c.points.filter { it.kind == COLLECTED_NOT_SHARED }
        // Google's Mobile Ads page says that SDK collects and shares location estimates and diagnostics too.
        assertEquals(listOf(DEVICE_OR_OTHER_IDS, LOCATION, APP_INFO_AND_PERFORMANCE), notShared.flatMap { it.categories })
        val p = notShared.first()
        assertTrue(p.text, p.text.startsWith("Label declares Device or other IDs as collected but not shared, while third-party SDK hosts were contacted:"))
        assertEquals(listOf("Meta (Facebook SDK)", "AppsFlyer", "Google Ads / AdMob"), p.sdks)
        assertTrue(c.points.none { it.kind == TYPE_NOT_DECLARED })
        assertTrue(c.points.none { it.kind == NO_SHARING_VS_THIRD_PARTY_SDKS })
    }

    @Test
    fun `a cut-short summary is not checked for undeclared types`() {
        val c = comparator.compare(DataSafetyParser.parse(LabelFixtures.SUMMARY_TRUNCATED), leaky)
        assertTrue(c.points.none { it.kind == TYPE_NOT_DECLARED || it.kind == COLLECTED_NOT_SHARED })
        assertTrue(c.notCompared.any { it.contains("and 5 others") })
        assertTrue(c.notCompared.any { it.contains("and 2 others") })
    }

    @Test
    fun `declared types no observed SDK explains are informational`() {
        val label = DataSafetyParser.parse("No data shared with third parties\nData collected\nPersonal info\nApp activity\nDevice or other IDs\nLocation\nApp info and performance")
        val c = comparator.compare(label, leaky)
        val info = c.points.single { it.kind == DECLARED_NOT_EXPLAINED }
        assertTrue(info.informational)
        assertEquals(listOf(PERSONAL_INFO), info.categories)
        assertEquals(c.points.last(), info)
    }

    @Test
    fun `an unreadable label gives no points, only the reason`() {
        val c = comparator.compare(DataSafetyParser.parse(LabelFixtures.NOT_A_LABEL), leaky)
        assertTrue(c.points.isEmpty())
        assertTrue(c.notCompared.single().startsWith("Could not find a Data safety statement"))
    }

    @Test
    fun `same-company services are left out and named`() {
        val owners = AppOwners(listOf(AppOwner("com.leakyshop.demo", "LeakyShop", "Meta", "test-only record", "https://example.org/test-only", "2026-10-11")))
        val c = PlayLabelComparator(catalog, owners = owners).compare(demo, leaky)
        assertTrue(c.observed.none { it.entity == "Meta (Facebook SDK)" })
        assertTrue(c.points.none { "Meta (Facebook SDK)" in it.sdks })
        assertEquals(listOf("Meta (Facebook SDK)"), c.sameCompany)
    }

    @Test
    fun `wording stays neutral everywhere`() {
        val labels = listOf(demo, DataSafetyParser.parse(LabelFixtures.NOTHING), DataSafetyParser.parse(LabelFixtures.DETAILS_PAGE),
            DataSafetyParser.parse(LabelFixtures.SUMMARY_TRUNCATED), DataSafetyParser.parse(LabelFixtures.NOT_A_LABEL))
        val banned = Regex("\\b(false|falsely|lie|lies|lying|liar|violat\\w*|illegal\\w*|fraudulent|breach\\w*|undeclared sharing)\\b", RegexOption.IGNORE_CASE)
        labels.flatMap { l -> listOf(comparator.compare(l, leaky), comparator.compare(l, fixed)) }.forEach { c ->
            val texts = c.points.flatMap { listOfNotNull(it.text, it.caveat) } + c.notCompared + c.summary + PlayLabelComparator.ABOUT
            texts.forEach { t ->
                assertNull(t, VerdictWords.find(t))
                assertNull(t, banned.find(t)?.value)
            }
        }
    }

    @Test
    fun `the comparison never changes the score`() {
        val engine = RulesEngine(catalog)
        val before = engine.evaluate(leaky)
        comparator.compare(demo, leaky)
        val after = engine.evaluate(leaky)
        assertEquals(before, after)
        assertEquals(21, after.score)
        assertEquals(ScoreBand.AT_RISK, after.band)
        assertEquals(setOf(CheckId.C1, CheckId.C2, CheckId.C3, CheckId.C4, CheckId.C10), after.findings.map { it.checkId }.toSet())
        assertEquals(100, engine.evaluate(fixed).score)
    }

    @Test
    fun `summary line counts points to review`() {
        assertEquals("4 points to review", comparator.compare(demo, leaky).summary)
        assertEquals("No points to review in this comparison", comparator.compare(demo, fixed).summary)
        assertEquals("Could not read a Data safety label in this text", comparator.compare(DataSafetyParser.parse("hello"), leaky).summary)
        assertFalse(comparator.compare(demo, leaky).observed.first().expectation.sourceUrl.isNullOrBlank())
    }
}
