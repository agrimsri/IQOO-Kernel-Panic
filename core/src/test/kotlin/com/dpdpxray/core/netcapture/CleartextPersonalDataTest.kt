package com.dpdpxray.core.netcapture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hand-built cleartext requests in the shapes Android HTTP clients write; every value goes through [Redaction] first. */
class CleartextPersonalDataTest {
    private fun exchange(target: String, headers: Map<String, String> = emptyMap()): HttpExchange {
        val t = Redaction.target(target)
        val (kept, found) = Redaction.headers(headers)
        return HttpExchange(0, "GET", "collect.example.in", t.text, null, kept, t.found + found)
    }

    @Test
    fun `field names map to a personal-data kind across naming styles`() {
        val cases = mapOf(
            "email" to PersonalDataKind.EMAIL, "userEmail" to PersonalDataKind.EMAIL, "e_mail" to PersonalDataKind.EMAIL, "EMAIL_ADDRESS" to PersonalDataKind.EMAIL,
            "phone" to PersonalDataKind.PHONE, "mobileNumber" to PersonalDataKind.PHONE, "msisdn" to PersonalDataKind.PHONE, "phone_no" to PersonalDataKind.PHONE,
            "imei" to PersonalDataKind.DEVICE_ID, "android_id" to PersonalDataKind.DEVICE_ID, "deviceId" to PersonalDataKind.DEVICE_ID, "mac_address" to PersonalDataKind.DEVICE_ID,
            "gaid" to PersonalDataKind.ADVERTISING_ID, "adid" to PersonalDataKind.ADVERTISING_ID, "advertising_id" to PersonalDataKind.ADVERTISING_ID,
            "lat" to PersonalDataKind.LOCATION, "lng" to PersonalDataKind.LOCATION, "latitude" to PersonalDataKind.LOCATION, "geo_location" to PersonalDataKind.LOCATION,
            "first_name" to PersonalDataKind.NAME, "fullName" to PersonalDataKind.NAME, "dob" to PersonalDataKind.DATE_OF_BIRTH,
            "pincode" to PersonalDataKind.ADDRESS, "aadhaar" to PersonalDataKind.GOVERNMENT_ID, "password" to PersonalDataKind.CREDENTIAL,
        )
        cases.forEach { (name, kind) -> assertEquals(name, kind, PersonalFieldNames.kindOf(name)) }
    }

    @Test
    fun `common non-personal names are not flagged`() {
        for (name in listOf("name", "event_name", "app_name", "latency", "long", "phone_model", "device_type", "lang", "advertiser_id", "id", "q", "pan_zoom", "version")) {
            assertNull(name, PersonalFieldNames.kindOf(name))
        }
    }

    @Test
    fun `a field named like personal data keeps no part of its value`() {
        val r = Redaction.target("/signup?first_name=Asha&dob=1990-01-02&city_hint=x&email=asha%40example.in")
        assertEquals("/signup?first_name=[personal]&dob=[personal]&city_hint=x&email=[email]", r.text)
        assertFalse(r.text.contains("Asha"))
        assertFalse(r.text.contains("1990"))
        assertTrue(SensitiveKind.PERSONAL_FIELD in r.found)
        assertTrue("a percent-encoded address is still recognised", SensitiveKind.EMAIL in r.found)
        assertEquals("/a?u=[email]", Redaction.target("/a?u=asha%40example.in").text)
    }

    @Test
    fun `value shapes and field names become hits, with only the field name and kind kept`() {
        val hits = CleartextPersonalData.scan(exchange("/v1/t?email=asha@example.in&imei=490154203237518&lat=12.971599&first_name=Asha&ev=open"))
        assertEquals(
            listOf(
                PersonalHit("email", PersonalDataKind.EMAIL, true),
                PersonalHit("imei", PersonalDataKind.DEVICE_ID, true),
                PersonalHit("lat", PersonalDataKind.LOCATION, true),
                PersonalHit("first_name", PersonalDataKind.NAME, false),
            ),
            hits,
        )
        assertFalse(hits.toString().contains("Asha") || hits.toString().contains("490154203237518"))
    }

    @Test
    fun `IDs, long numbers and coordinates without a matching field name do not count`() {
        val hits = CleartextPersonalData.scan(exchange("/v1/t?request_id=38400000-8cf0-11bd-b23e-10b96e40000d&order=490154203237518&x=12.971599&ts=1791586434810"))
        assertTrue(hits.toString(), hits.isEmpty())
    }

    @Test
    fun `an ad ID under an ad-ID field name counts by its shape`() {
        val hits = CleartextPersonalData.scan(exchange("/a?gaid=38400000-8cf0-11bd-b23e-10b96e40000d"))
        assertEquals(listOf(PersonalHit("gaid", PersonalDataKind.ADVERTISING_ID, true)), hits)
    }

    @Test
    fun `an e-mail address or phone number counts in any field, in the path, or in a header`() {
        assertEquals(listOf(PersonalHit("u", PersonalDataKind.EMAIL, true)), CleartextPersonalData.scan(exchange("/a?u=asha@example.in")))
        assertEquals(listOf(PersonalHit(CleartextPersonalData.PATH, PersonalDataKind.PHONE, true)), CleartextPersonalData.scan(exchange("/users/9876543210/cart")))
        val header = CleartextPersonalData.scan(exchange("/a", mapOf("x-user" to "asha@example.in")))
        assertEquals(listOf(PersonalHit(CleartextPersonalData.HEADER, PersonalDataKind.EMAIL, true)), header)
    }

    @Test
    fun `empty values carry nothing`() {
        assertTrue(CleartextPersonalData.scan(exchange("/a?email=&phone=")).isEmpty())
    }

    @Test
    fun `the description names fields and kinds only`() {
        val d = CleartextPersonalData.describe(listOf(PersonalHit("email", PersonalDataKind.EMAIL, true), PersonalHit("first_name", PersonalDataKind.NAME, false)))
        assertEquals("email (email address), first_name (person's name, field name only)", d)
    }
}
