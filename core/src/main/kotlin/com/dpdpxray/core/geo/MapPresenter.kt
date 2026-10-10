package com.dpdpxray.core.geo

import com.dpdpxray.core.model.Phase
import java.time.Instant
import java.time.ZoneOffset

enum class MapMode(val label: String) {
    SOURCE_DNS("Source country / DNS"),
    OWNER_LEGAL("Owner / legal authority"),
}

/** How a displayed fact is known. Kept to four classes so a viewer can always tell evidence from inference. */
enum class Evidence(val label: String) {
    /** Seen in this audit's DNS traffic. */
    OBSERVED("Observed"),
    /** Derived from observed data by a lookup or a catalogue match; not seen directly. */
    INFERRED("Inferred"),
    /** Stated by a cited document (a company page, a registry, the statute text). */
    SOURCED("Sourced"),
    /** No evidence available. Never filled with a default. */
    UNKNOWN("Unknown"),
}

data class Fact(val label: String, val value: String, val evidence: Evidence, val note: String? = null)
data class MapRow(val title: String, val evidence: Evidence, val iso: String?, val facts: List<Fact>)
data class MapView(val mode: MapMode, val heading: String, val explanation: String, val rows: List<MapRow>, val empty: String?, val footer: List<String>)

/**
 * Legal text shown beside an owner, as context. It is the law's own statement of its scope, not a verdict that the law
 * applies. Any jurisdiction's context can be supplied; [DPDP_INDIA] is the one TraceVeil ships with.
 */
data class LegalContext(val label: String, val name: String, val quotes: List<String>, val caveat: String) {
    companion object {
        val DPDP_INDIA = LegalContext(
            label = "Legal context",
            name = "DPDP Act, 2023 (India), section 3: scope",
            quotes = listOf(
                "3(a) apply to the processing of digital personal data within the territory of India where the personal data is collected " +
                    "in digital form, or in non-digital form and digitised subsequently",
                "3(b) also apply to processing of digital personal data outside the territory of India, if such processing is in connection " +
                    "with any activity related to offering of goods or services to Data Principals within the territory of India",
            ),
            caveat = "This is the Act's own statement of when it applies, shown for context. It is not a finding that it applies to this vendor or app; " +
                "that depends on facts this audit does not establish. TraceVeil's legal guidance is India-specific, and the law of other " +
                "jurisdictions, including the company's home country, is not assessed. Not legal advice.",
        )
    }
}

object MapPresenter {
    const val ATTRIBUTION = "This product includes GeoLite Data created by MaxMind, available from https://www.maxmind.com"
    private const val STALE_AFTER_DAYS = 30L
    private const val MIN_REPEAT_LENGTH = 40

    private val phaseLabel = mapOf(
        Phase.BEFORE_CONSENT to "Before consent",
        Phase.AFTER_ACCEPT to "After accept",
        Phase.AFTER_REJECT to "After reject",
        Phase.NO_CONSENT_SEEN to "No consent screen seen",
    )

    /** (label, explanation) for each reason. "Observed:" opens exactly the reasons that are real outcomes, not gaps. */
    fun unknownText(r: UnknownReason): Pair<String, String> = when (r) {
        UnknownReason.NO_SUCH_NAME -> "Name does not exist" to "Observed: the resolver answered NXDOMAIN, so there was no address to place."
        UnknownReason.NO_ADDRESS_RECORDS -> "No address records" to "Observed: the resolver answered, but with no address record for this name and type (NODATA)."
        UnknownReason.RESOLVER_ERROR -> "Resolver returned an error" to "Observed: the resolver answered with an error code and no address."
        UnknownReason.PARTIAL_NO_ADDRESS -> "Partial reply, no address" to "The reply was cut short and no address could be read from it, so it does not show that the name has no address."
        UnknownReason.UNREADABLE_REPLY -> "Reply not usable" to "A reply arrived but it could not be read or did not answer this lookup, so it says nothing about the name."
        UnknownReason.NO_REPLY -> "No reply received" to "No resolver replied, so there is no evidence about this name either way."
        UnknownReason.NOT_RECORDED -> "Reply not recorded" to "The reply was not recorded (an older audit, or it had not arrived when the audit was saved)."
        UnknownReason.PRIVATE_OR_RESERVED -> "Private or reserved address" to
            "Observed: the answer was a private or reserved address, which has no public location (it is often a block or a sinkhole)."
        UnknownReason.ADDRESS_NOT_IN_TABLE -> "Address not placed by the table" to "The IP-to-country table has no country for these addresses."
        UnknownReason.NO_TABLE -> "No IP table available" to "Addresses were recorded, but no IP-to-country table could be used."
    }

    fun view(
        model: EndpointMapModel, owners: OwnerRegistry, mode: MapMode,
        legal: LegalContext = LegalContext.DPDP_INDIA,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
    ): MapView = when (mode) {
        MapMode.SOURCE_DNS -> sourceView(model, nowEpochSeconds)
        MapMode.OWNER_LEGAL -> ownerView(model, owners, legal)
    }

    /** ISO codes to colour on the world map for [mode]. Owner mode shows only locations that have a cited source. */
    fun highlight(model: EndpointMapModel, owners: OwnerRegistry, mode: MapMode): Set<String> = when (mode) {
        MapMode.SOURCE_DNS -> model.countries.map { it.iso }.toSet()
        MapMode.OWNER_LEGAL -> model.hosts.mapNotNull { it.entity }.distinct().mapNotNull { owners.find(it)?.location?.iso }.toSet()
    }

    /**
     * (row, fact) positions whose note repeats a longer note already shown earlier in the same view, so the screen can
     * print it once instead of for every vendor.
     */
    fun repeatedNotes(rows: List<MapRow>): Set<Pair<Int, Int>> {
        val seen = HashSet<String>()
        val repeats = LinkedHashSet<Pair<Int, Int>>()
        rows.forEachIndexed { r, row ->
            row.facts.forEachIndexed { f, fact ->
                val note = fact.note ?: return@forEachIndexed
                if (note.length > MIN_REPEAT_LENGTH && !seen.add(note)) repeats += r to f
            }
        }
        return repeats
    }

    private fun name(iso: String) = CountryNames.name(iso)

    // ---- source country / DNS -----------------------------------------------------------------------------------------

    private fun sourceView(m: EndpointMapModel, now: Long): MapView {
        val tableNote = when (val s = m.source) {
            GeoSource.NotInstalled -> " No IP-to-country table is installed (GeoLite2-Country.mmdb), so lookups are listed without countries."
            is GeoSource.Unreadable -> " The IP-to-country table could not be read: ${s.reason}. Lookups are listed without countries."
            is GeoSource.Loaded -> ""
        }
        val rows = m.countries.map { c ->
            val hostsOfCountry = m.hosts.filter { c.iso in it.located }
            val facts = buildList {
                c.hostsByPhase.forEach { (phase, hosts) -> add(Fact(phaseLabel.getValue(phase), hosts.joinToString(", "), Evidence.OBSERVED)) }
                add(Fact("DNS answers", hostsOfCountry.joinToString("; ") { dnsLine(it) }, Evidence.OBSERVED))
                if (c.registeredElsewhere.isNotEmpty()) add(
                    Fact("Address block registered in", c.registeredElsewhere.sorted().joinToString(", ") { name(it) }, Evidence.INFERRED,
                        "The registered country can differ from where an address is placed."),
                )
                if (c.uncertainHosts.isNotEmpty()) add(Fact("Confidence", "Uncertain", Evidence.INFERRED, c.uncertainHosts.values.distinct().joinToString(". ")))
            }
            MapRow(listOf(CountryNames.flag(c.iso), name(c.iso)).filter { it.isNotEmpty() }.joinToString(" "), Evidence.INFERRED, c.iso, facts)
        } + if (m.unknown.isEmpty()) emptyList() else listOf(unknownRow(m))
        return MapView(
            MapMode.SOURCE_DNS, "Where lookups were answered",
            "Hostnames, aliases and addresses are what this audit observed; countries are inferred from the IP addresses the resolver returned.$tableNote",
            rows, if (m.hosts.isEmpty()) "No tracker lookups were recorded in this audit, so there is nothing to place." else null,
            sourceFooter(m.source, now),
        )
    }

    private fun unknownRow(m: EndpointMapModel): MapRow {
        val facts = m.unknown.map { (reason, hosts) ->
            val (label, why) = unknownText(reason)
            val registered = m.hosts.filter { it.host in hosts && it.unplacedRegistered.isNotEmpty() }
                .joinToString("; ") { h -> "${h.host}: registered in ${h.unplacedRegistered.sorted().joinToString(", ") { name(it) }}" }
            Fact(label, hosts.joinToString(", "), Evidence.UNKNOWN,
                if (registered.isEmpty()) why else "$why $registered. A registration is not a location; addresses with no placement are often anycast.")
        }
        val replies = m.hosts.filter { it.unknown != null && it.unknown != UnknownReason.NO_TABLE && it.unknown != UnknownReason.ADDRESS_NOT_IN_TABLE }
            .joinToString("; ") { "${it.host}: ${it.answerSummary}" }
        return MapRow("Country unknown", Evidence.UNKNOWN, null,
            facts + if (replies.isEmpty()) emptyList() else listOf(Fact("Resolver replies", replies, Evidence.OBSERVED)))
    }

    private fun dnsLine(h: HostEndpoint): String {
        val addresses = if (h.addresses.isEmpty()) "no address recorded" else h.addresses.joinToString(", ")
        val alias = if (h.cnameChain.size > 1) "; alias chain observed: ${h.cnameChain.joinToString(" → ")}" else ""
        return "${h.host}: $addresses$alias"
    }

    private fun sourceFooter(s: GeoSource, now: Long): List<String> = buildList {
        val built = (s as? GeoSource.Loaded)?.lookup?.buildEpochSeconds
        add(
            "Countries are inferred from IP addresses" + (built?.let { " using GeoLite2-Country (database built ${Instant.ofEpochSecond(it).atZone(ZoneOffset.UTC).toLocalDate()})" } ?: "") +
                ". They show where an address is placed, not where data went, and are not proof of where a server is or of whether any law applies. " +
                "CDN and anycast addresses can be answered from a country other than the one listed.",
        )
        if (built != null) {
            val days = (now - built) / 86_400
            if (days > STALE_AFTER_DAYS) add("This database was built $days days ago. MaxMind publishes newer releases and the GeoLite EULA asks users to move to the updated version.")
            add(ATTRIBUTION)
        }
    }

    // ---- owner / legal authority --------------------------------------------------------------------------------------

    private fun ownerView(m: EndpointMapModel, owners: OwnerRegistry, legal: LegalContext): MapView {
        val rows = m.hosts.filter { it.entity != null }.groupBy { it.entity!! }.toSortedMap().map { (entity, hosts) ->
            val countries = hosts.flatMap { it.located }.distinct().sorted()
            val where = if (countries.isNotEmpty()) Fact("Where its lookups were answered", countries.joinToString(", ") { name(it) }, Evidence.INFERRED,
                "Placed from the IP addresses in DNS answers; not a statement about where data was processed.")
            else Fact("Where its lookups were answered", "Unknown", Evidence.UNKNOWN, hosts.mapNotNull { it.unknown }.distinct().joinToString(" ") { unknownText(it).first + "." })
            val facts = listOf(
                where,
                Fact("Domain registration", "Not looked up", Evidence.UNKNOWN,
                    "TraceVeil has no domain-registration data. A domain's registration country is not the server's or the owner's country."),
                companyLocation(owners.find(entity)),
                incorporation(owners.find(entity)),
                Fact(legal.label, legal.name, Evidence.SOURCED, "\"${legal.quotes.joinToString("; ")}.\" ${legal.caveat}"),
            )
            MapRow(entity, Evidence.INFERRED, null, facts)
        } + m.hosts.filter { it.entity == null }.takeIf { it.isNotEmpty() }?.let { hosts ->
            listOf(MapRow("Owner not identified", Evidence.UNKNOWN, null,
                listOf(Fact("Hosts", hosts.joinToString(", ") { it.host }, Evidence.UNKNOWN, "These names do not match TraceVeil's tracker catalogue."))))
        }.orEmpty()
        return MapView(
            MapMode.OWNER_LEGAL, "Who runs it, and the legal context",
            "Each vendor is matched from its domain name in TraceVeil's tracker catalogue. Server location, domain registration, " +
                "the company's location, where it is incorporated and legal context are different things, so they are shown separately.",
            rows, if (m.hosts.isEmpty()) "No tracker lookups were recorded in this audit, so there is no vendor to show." else null,
            listOf(
                "A company location appears only when TraceVeil's tracker catalogue cites a source for it; an address a company gives for contracts is not necessarily its headquarters. A parent company is named only where the cited source names it.",
                "Nothing here says a vendor or app broke a law; it shows which facts are known and which are not.",
            ),
        )
    }

    private fun cite(c: SourcedCountry) = "Source: ${c.sourceUrl} (${c.kind.label}, as of ${c.asOf}). Quote: \"${c.quote}\""

    /** The company's location, labelled for what the source actually says: a headquarters or a stated address. */
    private fun companyLocation(f: OwnerFacts?): Fact {
        val label = "Company location"
        val loc = f?.location ?: return Fact(label, "Not established", Evidence.UNKNOWN, f?.note ?: "No cited source in TraceVeil's tracker catalogue.")
        val kind = if (loc.basis == LocationBasis.HEADQUARTERS) "headquarters" else "stated address"
        return Fact(label, "${name(loc.iso)} ($kind)", Evidence.SOURCED, listOfNotNull("${loc.basis!!.phrase}. ${cite(loc)}", f.note).joinToString(" "))
    }

    /** Where a source says the company is incorporated; a different fact from where it is located. */
    private fun incorporation(f: OwnerFacts?): Fact {
        val label = "Incorporation"
        val inc = f?.incorporation ?: return Fact(label, "Not established", Evidence.UNKNOWN, "No cited source in TraceVeil's tracker catalogue.")
        return Fact(label, name(inc.iso), Evidence.SOURCED, cite(inc))
    }
}
