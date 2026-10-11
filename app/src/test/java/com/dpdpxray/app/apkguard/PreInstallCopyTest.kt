package com.dpdpxray.app.apkguard

import com.dpdpxray.core.model.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreInstallCopyTest {
    @Test fun `every line is in both languages`() {
        PreInstallCopy.all().forEach {
            assertTrue(it.en, it.en.isNotBlank())
            assertTrue(it.en, it.hi.isNotBlank())
        }
    }

    @Test fun `the decision buttons say exactly what they do`() {
        assertEquals("Install anyway", PreInstallCopy.installAnyway.en)
        assertEquals("Don't install", PreInstallCopy.dontInstall.en)
        assertTrue(PreInstallCopy.footnote.en.contains("only after you confirm in Android's installer"))
        assertTrue(PreInstallCopy.needsPermission.en.contains("cannot switch this on by itself"))
    }

    @Test fun `nothing calls a file safe, harmful or fake, and SDK presence is never called proof`() {
        PreInstallCopy.all().forEach { line ->
            listOf("malicious", "malware", "is safe", "safe to install", "harmless", "fake", "virus").forEach {
                assertFalse("$it in ${line.en}", line.en.lowercase().contains(it))
            }
        }
        assertTrue(PreInstallCopy.codeFooter.en.contains("does not prove"))
    }

    @Test fun `tracking categories are labelled as tracking, crash reporting is not`() {
        listOf(Category.ANALYTICS, Category.ADVERTISING, Category.ATTRIBUTION, Category.SOCIAL, Category.ENGAGEMENT).forEach {
            assertTrue(it.name, PreInstallCopy.categoryLabel(it).en.startsWith("Tracking"))
        }
        assertFalse(PreInstallCopy.categoryLabel(Category.CRASH_REPORTING).en.startsWith("Tracking"))
        assertEquals("Not catalogued", PreInstallCopy.categoryLabel(null).en)
    }
}
