package com.dpdpxray.core.datasafety

import com.dpdpxray.core.datasafety.DataCategory.APP_ACTIVITY
import com.dpdpxray.core.datasafety.DataCategory.APP_INFO_AND_PERFORMANCE
import com.dpdpxray.core.datasafety.DataCategory.DEVICE_OR_OTHER_IDS
import com.dpdpxray.core.datasafety.DataCategory.LOCATION
import com.dpdpxray.core.datasafety.DataCategory.PERSONAL_INFO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataSafetyParserTest {

    @Test
    fun `summary with no sharing and three collected categories`() {
        val l = DataSafetyParser.parse(LabelFixtures.SUMMARY_NO_SHARING)
        assertTrue(l.noDataShared)
        assertFalse(l.noDataCollected)
        assertNull("no sharing list when the label says nothing is shared", l.shared)
        assertEquals(listOf(LOCATION, APP_ACTIVITY, DEVICE_OR_OTHER_IDS), l.collected!!.categories)
        assertEquals(0, l.collected!!.notListed)
        assertEquals(true, l.encryptedInTransit)
        assertEquals(true, l.deletionRequestable)
        assertTrue(l.readable)
        assertTrue(l.sharingStated)
        assertTrue(l.collectionStated)
    }

    @Test
    fun `cut-short summary keeps the count of types it does not name`() {
        val l = DataSafetyParser.parse(LabelFixtures.SUMMARY_TRUNCATED)
        assertFalse(l.noDataShared)
        assertEquals(listOf(LOCATION, APP_ACTIVITY), l.shared!!.categories)
        assertEquals(2, l.shared!!.notListed)
        assertEquals(listOf(LOCATION, PERSONAL_INFO), l.collected!!.categories)
        assertEquals(5, l.collected!!.notListed)
        assertEquals(false, l.encryptedInTransit)
        assertEquals(false, l.deletionRequestable)
    }

    @Test
    fun `details page reads categories and data types, and ignores purposes`() {
        val l = DataSafetyParser.parse(LabelFixtures.DETAILS_PAGE)
        assertEquals(listOf(LOCATION, DEVICE_OR_OTHER_IDS), l.shared!!.categories)
        assertEquals(listOf("Approximate location", "Device or other IDs"), l.shared!!.types)
        assertEquals(listOf(PERSONAL_INFO, APP_ACTIVITY, APP_INFO_AND_PERFORMANCE, DEVICE_OR_OTHER_IDS), l.collected!!.categories)
        assertEquals(listOf("Name", "App interactions", "Crash logs", "Diagnostics", "Device or other IDs"), l.collected!!.types)
        assertEquals(true, l.encryptedInTransit)
    }

    @Test
    fun `a label that declares nothing`() {
        val l = DataSafetyParser.parse(LabelFixtures.NOTHING)
        assertTrue(l.noDataShared)
        assertTrue(l.noDataCollected)
        assertNull(l.shared)
        assertNull(l.collected)
        assertTrue(l.readable)
        assertEquals(true, l.encryptedInTransit)
        assertNull("not stated, so unknown", l.deletionRequestable)
    }

    @Test
    fun `one line with an Oxford comma`() {
        val l = DataSafetyParser.parse(LabelFixtures.ONE_LINE)
        val expected = listOf(APP_ACTIVITY, APP_INFO_AND_PERFORMANCE, DEVICE_OR_OTHER_IDS)
        assertEquals(expected, l.shared!!.categories)
        assertEquals(expected, l.collected!!.categories)
    }

    @Test
    fun `text that is not a label reads as unreadable, never as no data`() {
        val l = DataSafetyParser.parse(LabelFixtures.NOT_A_LABEL)
        assertFalse(l.readable)
        assertFalse(l.noDataShared)
        assertFalse(l.noDataCollected)
        assertNull(l.shared)
        assertNull(l.collected)
        assertNull(l.encryptedInTransit)
        assertFalse(DataSafetyParser.parse("").readable)
    }

    @Test
    fun `case, curly apostrophes and extra spaces do not matter`() {
        val l = DataSafetyParser.parse("NO DATA SHARED WITH  THIRD PARTIES\nthis app may collect these data types\n  app activity  \nData isn’t encrypted\nData can’t be deleted")
        assertTrue(l.noDataShared)
        assertEquals(listOf(APP_ACTIVITY), l.collected!!.categories)
        assertEquals(false, l.encryptedInTransit)
        assertEquals(false, l.deletionRequestable)
    }

    @Test
    fun `a phrase split over two lines still reads`() {
        val l = DataSafetyParser.parse("This app may share these data types\nwith third parties\nDevice or other IDs")
        assertEquals(listOf(DEVICE_OR_OTHER_IDS), l.shared!!.categories)
    }

    @Test
    fun `a heading with no recognised type is kept and noted`() {
        val l = DataSafetyParser.parse("This app may collect these data types\nGizmo readings")
        assertTrue(l.collectionStated)
        assertEquals(emptyList<DataCategory>(), l.collected!!.categories)
        assertTrue(l.notes.any { it.contains("no Play data type") })
    }

    @Test
    fun `contradicting statements are kept and noted`() {
        val l = DataSafetyParser.parse("No data shared with third parties\nThis app may share these data types with third parties\nLocation")
        assertTrue(l.noDataShared)
        assertEquals(listOf(LOCATION), l.shared!!.categories)
        assertTrue(l.notes.any { it.contains("both") })
    }

    @Test
    fun `very long text is read only up to the limit`() {
        val l = DataSafetyParser.parse("x".repeat(DataSafetyParser.MAX_CHARS) + "\nNo data collected")
        assertFalse(l.noDataCollected)
        assertTrue(l.notes.any { it.contains("first") })
    }

    @Test
    fun `the bundled LeakyShop demo label says nothing shared and collects App activity only`() {
        val l = DataSafetyParser.parse(DemoLabels.LEAKYSHOP)
        assertTrue(l.noDataShared)
        assertFalse(l.noDataCollected)
        assertEquals(listOf(APP_ACTIVITY), l.collected!!.categories)
        assertTrue(DemoLabels.LEAKYSHOP.contains("demo label written by the TraceVeil team", ignoreCase = true))
    }

    @Test
    fun `every Play category and data type name maps back to its category`() {
        DataCategory.entries.forEach { c ->
            assertEquals(c.label, listOf(c), DataSafetyParser.parse("Data collected\n${c.label}").collected!!.categories)
            c.types.forEach { t ->
                assertEquals(t, listOf(c), DataSafetyParser.parse("Data collected\n$t").collected!!.categories)
            }
        }
    }
}
