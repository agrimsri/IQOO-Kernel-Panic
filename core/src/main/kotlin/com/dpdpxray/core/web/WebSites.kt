package com.dpdpxray.core.web

import java.util.Locale

/**
 * First-party versus third-party for a website check: a host belongs to the page's site when both share the same site
 * name, the last two labels of the host ("example.com"), or three when the last two are a shared suffix from a short
 * built-in list ("example.co.in", "ministry.gov.in"). This is a heuristic, not the full Public Suffix List; hosting
 * suffixes in the list (github.io, cloudfront.net, ...) make each customer's host its own site.
 */
object WebSites {
    const val RULE = "First- and third-party are judged by site name: the last two labels of a host, or three for a short " +
        "built-in list of shared suffixes such as co.in and gov.in. This is not the full Public Suffix List."

    private val SHARED_SUFFIXES = setOf(
        // India
        "co.in", "net.in", "org.in", "gov.in", "nic.in", "ac.in", "edu.in", "res.in", "firm.in", "gen.in", "ind.in", "mil.in",
        // Elsewhere, common in practice
        "co.uk", "org.uk", "gov.uk", "ac.uk", "ltd.uk", "plc.uk", "me.uk", "net.uk", "nhs.uk", "police.uk",
        "com.au", "net.au", "org.au", "gov.au", "edu.au", "co.nz", "org.nz", "govt.nz",
        "co.jp", "ne.jp", "or.jp", "go.jp", "ac.jp", "co.kr", "or.kr", "go.kr",
        "com.br", "gov.br", "com.cn", "gov.cn", "com.sg", "gov.sg", "edu.sg", "com.my", "gov.my", "com.hk", "gov.hk", "com.tw",
        "com.tr", "gov.tr", "com.mx", "gob.mx", "com.ar", "com.pk", "gov.pk", "com.bd", "gov.bd", "com.np", "gov.np", "com.lk",
        "gov.lk", "co.za", "gov.za", "co.id", "go.id", "co.th", "go.th", "co.il", "gov.il", "com.ng", "gov.ng", "co.ke", "go.ke",
        "com.eg", "com.sa", "gov.sa", "ac.ae", "gov.ae", "co.ae",
        // Hosting suffixes: each customer host is its own site
        "github.io", "gitlab.io", "blogspot.com", "wordpress.com", "appspot.com", "cloudfront.net", "herokuapp.com",
        "vercel.app", "netlify.app", "pages.dev", "workers.dev", "web.app", "firebaseapp.com", "azurewebsites.net",
        "azureedge.net", "amazonaws.com", "fastly.net", "akamaized.net", "edgekey.net", "edgesuite.net",
    )

    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    /** The site a host belongs to (see the rule above). An IP address is its own site. */
    fun siteOf(host: String): String {
        val h = host.lowercase(Locale.ROOT).trim().trimEnd('.')
        if (h.isEmpty() || IPV4.matches(h) || h.startsWith("[")) return h
        val labels = h.split('.').filter { it.isNotEmpty() }
        if (labels.size <= 2) return labels.joinToString(".")
        val lastTwo = labels.takeLast(2).joinToString(".")
        return if (lastTwo in SHARED_SUFFIXES) labels.takeLast(3).joinToString(".") else lastTwo
    }

    fun sameSite(a: String, b: String): Boolean = siteOf(a) == siteOf(b)

    fun isThirdParty(host: String, pageHost: String): Boolean = !sameSite(host, pageHost)
}
