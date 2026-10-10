package com.dpdpxray.core.apk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServiceNameHeuristicTest {
    private fun m(label: String?, pkg: String = "org.example.app") = ServiceNameHeuristic.match(label, pkg)

    @Test fun `bank and payment words match`() {
        assertEquals(ServiceCategory.BANK_OR_PAYMENT, m("SBI YONO")?.category)
        assertEquals(ServiceCategory.BANK_OR_PAYMENT, m("HDFCBank Rewards")?.category)
        assertEquals(ServiceCategory.BANK_OR_PAYMENT, m("UPI Pay")?.category)
        assertEquals(ServiceCategory.BANK_OR_PAYMENT, m(null, "com.netbanking.update")?.category)
        assertEquals(ServiceCategory.BANK_OR_PAYMENT, m("SBIYono")?.category)
    }

    @Test fun `government service words match`() {
        listOf("RTO e-Challan", "eChallan Pay", "Pension Verification", "PM Kisan Status", "Aadhaar Update", "mParivahan", "EPFO Passbook", "Digilocker KYC")
            .forEach { assertEquals(it, ServiceCategory.GOVERNMENT, m(it)?.category) }
        assertEquals(ServiceCategory.GOVERNMENT, m("Status", "com.pmkisan.status")?.category)
    }

    @Test fun `the matched keyword is reported`() {
        assertEquals("challan", m("e-Challan Pay")?.keyword)
        assertEquals("pension", m("Pension Seva")?.keyword)
        assertEquals("sbi", m("SBI Rewards")?.keyword)
    }

    @Test fun `short words only match whole words`() {
        assertNull(m("Cupid Dating"))
        assertNull(m("Porto Racing"))
        assertNull(m("Stupid Puzzle", "com.games.stupid"))
        assertNull(m("Itsbig Games"))
    }

    @Test fun `ordinary names do not match`() {
        assertNull(m("Notes", "org.example.notes"))
        assertNull(m("Block Blast!", "com.block.juggle"))
        assertNull(m(null, "com.leakyshop.demo"))
    }
}
