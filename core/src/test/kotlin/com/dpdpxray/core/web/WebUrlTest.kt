package com.dpdpxray.core.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebUrlTest {
    private fun valid(input: String) = WebUrl.check(input) as? WebUrlCheck.Valid ?: error("expected valid: $input -> ${WebUrl.check(input)}")
    private fun invalid(input: String) = WebUrl.check(input) as? WebUrlCheck.Invalid ?: error("expected invalid: $input -> ${WebUrl.check(input)}")

    @Test
    fun `a bare site name opens over https`() {
        val v = valid("example.com")
        assertEquals("https://example.com/", v.url)
        assertEquals("example.com", v.host)
        assertFalse(v.upgradedFromHttp)
    }

    @Test
    fun `scheme and host are lower-cased, path and query are kept, the fragment is dropped`() {
        val v = valid("  HTTPS://WWW.Example.COM/News/Today?id=7#top  ")
        assertEquals("https://www.example.com/News/Today?id=7", v.url)
        assertEquals("www.example.com", v.host)
    }

    @Test
    fun `http is accepted and checked over https, and says so`() {
        val v = valid("http://example.org")
        assertEquals("https://example.org/", v.url)
        assertTrue(v.upgradedFromHttp)
    }

    @Test
    fun `a port is kept`() {
        assertEquals("https://shop.example.in:8443/cart", valid("shop.example.in:8443/cart").url)
    }

    @Test
    fun `protocol-relative addresses become https`() {
        assertEquals("https://example.net/", valid("//example.net").url)
    }

    @Test
    fun `international site names are converted to their ASCII form`() {
        val v = valid("https://bücher.example/")
        assertEquals("xn--bcher-kva.example", v.host)
    }

    @Test
    fun `other schemes are refused`() {
        for (s in listOf("javascript:alert(1)", "file:///sdcard/a.html", "content://x/y", "intent://scan#Intent;end", "data:text/html,hi",
            "ftp://example.com", "about:blank", "chrome://settings", "mailto:a@example.com", "ws://example.com")) {
            assertTrue(s, invalid(s).reason.contains("http"))
        }
    }

    @Test
    fun `empty, spaced and over-long input is refused`() {
        invalid("")
        invalid("   ")
        invalid("exa mple.com")
        invalid("https://example.com/" + "a".repeat(WebUrl.MAX_LENGTH))
    }

    @Test
    fun `a user name or password in the address is refused`() {
        assertTrue(invalid("https://user:pass@example.com/").reason.contains("user name"))
        invalid("https://bank.example@evil.example/")
    }

    @Test
    fun `local names and private addresses are refused`() {
        for (s in listOf("localhost", "http://localhost:8080", "intranet", "127.0.0.1", "http://10.0.0.5/", "192.168.1.1", "172.16.4.2",
            "169.254.1.1", "0.0.0.0", "[::1]", "https://[fe80::1]/")) {
            invalid(s)
        }
    }

    @Test
    fun `a public IPv4 address is accepted`() {
        assertEquals("8.8.8.8", valid("8.8.8.8").host)
    }

    @Test
    fun `malformed host names are refused`() {
        for (s in listOf("-bad.example.com", "bad-.example.com", "a..b.com", "example.123", "https:///path", "https://.com")) invalid(s)
    }

    @Test
    fun `the label drops the scheme's slash at the end only`() {
        assertEquals("https://example.com", WebUrl.label("https://example.com/"))
        assertEquals("https://example.com/news", WebUrl.label("https://example.com/news"))
    }
}
