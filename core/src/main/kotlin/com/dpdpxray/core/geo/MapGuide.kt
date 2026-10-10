package com.dpdpxray.core.geo

import com.dpdpxray.core.model.AuditSession

/** One point of the map's "what it shows and what it doesn't" fold: a title, one short line, and detail behind a tap. */
data class GuidePoint(val title: String, val line: String, val detail: String)

/** Why a server's country can matter in one jurisdiction. Context with its citation, never a finding about an app. */
data class TransferRule(val region: String, val rule: String, val line: String, val citation: String)

/**
 * The honest limits of the endpoint map, shown on the card as a fold. Every statement here describes what TraceVeil's
 * code actually does (see MapGuideTest and the app's map tests for the checks behind them). Kept apart from
 * [MapPresenter]'s view, whose wording rules forbid naming transfers, because the "why it matters" part must name them.
 */
object MapGuide {
    const val TITLE = "What this map shows, and what it doesn't"
    const val WHY_HEADING = "Why a server's country can matter"
    const val WHY_CLOSING = "A server abroad is a prompt to check the company's transfer terms. It is not proof of where your data is " +
        "stored or that any rule was broken. Legal context, not legal advice."

    /** [fallbackResolvers] are the public DNS servers the relay falls back to (the app passes the relay's own list). */
    fun points(fallbackResolvers: List<String>): List<GuidePoint> = listOf(
        GuidePoint(
            "It places servers, never you",
            "The map shows where the servers the checked app looked up are placed. It never shows where you are: TraceVeil holds no location permission.",
            "Each country comes from an IP address in a DNS answer the app received during this check. A lookup is an attempt to contact " +
                "a server, not proof that data was sent to it.",
        ),
        GuidePoint(
            "A country from an IP address is approximate",
            "Large networks (CDNs, anycast) answer from an edge near whoever asks, so a country means where the address the app was " +
                "given is placed, not where your data is stored.",
            "IP-to-country tables are estimates built from address registries and measurements. An anycast address is announced from " +
                "many countries at once, yet a table lists one: the bundled DB-IP table, for example, places 1.1.1.1, a DNS service " +
                "answered worldwide, in Australia.",
        ),
        GuidePoint(
            "The company comes from cited documents",
            "Owner mode shows where a company is only when TraceVeil's tracker catalogue cites a filing or company document for it. " +
                "Otherwise it says Not established.",
            "Server country, company location and incorporation are separate facts, and none is filled in from another.",
        ),
        GuidePoint(
            "Using your own VPN?",
            "Android runs one VPN at a time, so your own VPN stops while a check runs. Turn it off before a check and back on afterwards.",
            "TraceVeil's check VPN stays on this phone: it routes only the checked app's DNS lookups (and Google Play services' " +
                "lookups, if you include them) and relays them from here. " +
                "TraceVeil does not need to know where you are and never works it out. Where you connect from can change which nearby " +
                "edge a CDN picks, not which company runs the service.",
        ),
        GuidePoint(
            "Private DNS and DNS servers abroad",
            "Private DNS, or a DNS server in another country, can change which edge answers and can hide lookups from the check. " +
                "This check's network context is below.",
            "TraceVeil relays each lookup to this phone network's own DNS servers, then to ${fallbackResolvers.joinToString(" or ")} " +
                "if those fail, and does not record which one answered. A DNS server far from you can be given an edge near itself " +
                "rather than near you.",
        ),
        GuidePoint(
            "Hidden lookups are not a pass",
            "Lookups sent inside encrypted DNS (DNS over HTTPS), or skipped with cached or built-in addresses, never reach the map. " +
                "With almost no lookups, the result is Inconclusive rather than a score.",
            "So a country missing from this map is not proof that the app reached no server there.",
        ),
    )

    /** Several jurisdictions, each with its own citation. Context only: none of these is applied to an app. */
    val whyItMatters: List<TransferRule> = listOf(
        TransferRule(
            "European Union and United Kingdom", "GDPR and UK GDPR, Chapter V (Articles 44 to 50)",
            "Personal data may go to a country outside the EU or UK only under an adequacy decision, safeguards such as standard " +
                "contractual clauses, or a listed exception.",
            "Regulation (EU) 2016/679, https://eur-lex.europa.eu/eli/reg/2016/679/oj ; UK GDPR, Chapter V.",
        ),
        TransferRule(
            "India", "Digital Personal Data Protection Act, 2023, section 16",
            "The Government may, by notification, restrict the transfer of personal data to countries it names; stricter Indian laws " +
                "on transfers still apply. Scheduled to take effect on 13 May 2027 with the Act's main provisions.",
            "https://www.indiacode.nic.in/bitstream/123456789/22037/2/a2023-22.pdf ; commencement notification G.S.R. 843(E), 13 November 2025.",
        ),
        TransferRule(
            "China", "Personal Information Protection Law, Articles 38 to 40",
            "Personal information may leave China only after a security assessment, a certification or a standard contract, and some " +
                "operators must keep it in China.",
            "Personal Information Protection Law of the People's Republic of China (2021), Chapter III.",
        ),
        TransferRule(
            "Data-localisation rules", "For example, Reserve Bank of India payment data (2018)",
            "Some sectors must keep data in the country: the RBI requires payment-system data to be stored only in India.",
            "RBI circular DPSS.CO.OD No. 2785/06.08.005/2017-2018, 6 April 2018, Storage of Payment System Data.",
        ),
    )

    /**
     * The network this capture ran on, as far as the saved audit records it: Private DNS per plan, DNS the check could
     * not read, and the resolver (never recorded today, so it says so). Unknown stays unknown, never zero.
     */
    fun networkContext(sessions: List<AuditSession>, fallbackResolvers: List<String>): List<Fact> {
        if (sessions.isEmpty()) return emptyList()
        val n = sessions.size
        fun missing(known: Int) = if (known < n) " (not recorded for ${n - known} of $n plans)" else ""
        val dnsSetting = sessions.mapNotNull { it.privateDnsActive }
        val on = dnsSetting.count { it }
        val privateDns = when {
            dnsSetting.isEmpty() -> Fact("Private DNS during this check", "Not recorded", Evidence.UNKNOWN, "This audit was saved without the Private DNS setting.")
            on > 0 -> Fact("Private DNS during this check", "On in $on of $n plans" + missing(dnsSetting.size), Evidence.OBSERVED,
                "With Private DNS on, Android can send lookups encrypted where the check cannot read them. Turn it off and check again.")
            else -> Fact("Private DNS during this check", "Off" + missing(dnsSetting.size), Evidence.OBSERVED, null)
        }
        val hiddenCounts = sessions.mapNotNull { it.captureHealth?.hiddenDnsPackets }
        val hidden = if (hiddenCounts.isEmpty()) {
            Fact("Encrypted or TCP DNS the check could not read", "Not recorded", Evidence.UNKNOWN, "This audit was saved without this count.")
        } else {
            val total = hiddenCounts.sum()
            Fact(
                "Encrypted or TCP DNS the check could not read", "${if (total == 1L) "1 packet" else "$total packets"}" + missing(hiddenCounts.size), Evidence.OBSERVED,
                "DNS over TLS or TCP sent to the check's VPN is counted here and is not on the map. DNS over HTTPS does not pass through " +
                    "the check's VPN at all, so it cannot be counted.",
            )
        }
        val resolver = Fact(
            "DNS server that answered", "Not recorded", Evidence.UNKNOWN,
            "TraceVeil relays each lookup to this phone network's own DNS servers first, then to ${fallbackResolvers.joinToString(" or ")} " +
                "if they fail. It does not record which one answered.",
        )
        return listOf(privateDns, hidden, resolver)
    }
}
