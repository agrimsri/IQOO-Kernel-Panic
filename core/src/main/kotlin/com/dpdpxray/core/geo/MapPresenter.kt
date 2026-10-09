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

/** The statute text the owner/legal mode points at. Quoted verbatim from the Act's section 3. */
object LegalReference {
    const val NAME = "Digital Personal Data Protection Act, 2023 (India), section 3"
    const val WITHIN_INDIA = "3(a) apply to the processing of digital personal data within the territory of India where the personal " +
        "data is collected in digital form, or in non-digital form and digitised subsequently"
    const val OUTSIDE_INDIA = "3(b) also apply to processing of digital personal data outside the territory of India, if such processing " +
        "is in connection with any activity related to offering of goods or services to Data Principals within the territory of India"
    const val CAVEAT = "Whether the Act applies to this app depends on facts this audit does not establish. " +
        "The law of the company's home country is not assessed. Not legal advice."
    const val ATTRIBUTION = "This product includes GeoLite Data created by MaxMind, available from https://www.maxmind.com"
}

object MapPresenter {
    private val phaseLabel = mapOf(
        Phase.BEFORE_CONSENT to "Before consent",
        Phase.AFTER_ACCEPT to "After accept",
        Phase.AFTER_REJECT to "After reject",
        Phase.NO_CONSENT_SEEN to "No consent screen seen",
    )

    private val unknownLabel = mapOf(
        UnknownReason.NO_ADDRESS_RECORDED to ("No address recorded" to "The reply was not read, or it held no address."),
        UnknownReason.ADDRESS_NOT_IN_TABLE to ("Address not placed by the table" to "The IP-to-country table has no country for these addresses."),
        UnknownReason.NO_TABLE to ("No IP table available" to "Addresses were recorded, but no IP-to-country table could be used."),
    )

    fun view(model: EndpointMapModel, owners: OwnerRegistry, mode: MapMode): MapView = when (mode) {
        MapMode.SOURCE_DNS -> sourceView(model)
        MapMode.OWNER_LEGAL -> ownerView(model, owners)
    }

    /** ISO codes to colour on the world map for [mode]. Owner mode shows only headquarters that have a cited source. */
    fun highlight(model: EndpointMapModel, owners: OwnerRegistry, mode: MapMode): Set<String> = when (mode) {
        MapMode.SOURCE_DNS -> model.countries.map { it.iso }.toSet()
        MapMode.OWNER_LEGAL -> model.hosts.mapNotNull { it.entity }.distinct().mapNotNull { owners.find(it)?.headquarters?.iso }.toSet()
    }

    private fun name(iso: String) = CountryNames.name(iso)

    private const val MIN_REPEAT_LENGTH = 40

    /**
     * (row, fact) positions whose note repeats a longer note already shown earlier in the same view, so the screen can
     * say "same as above" instead of printing the same paragraph for every vendor.
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

    private fun sourceView(m: EndpointMapModel): MapView {
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
        } + if (m.unknown.isEmpty()) emptyList() else listOf(
            MapRow("Country unknown", Evidence.UNKNOWN, null, m.unknown.map { (reason, hosts) ->
                val (label, why) = unknownLabel.getValue(reason)
                val registered = m.hosts.filter { it.host in hosts && it.unplacedRegistered.isNotEmpty() }
                    .joinToString("; ") { h -> "${h.host}: registered in ${h.unplacedRegistered.sorted().joinToString(", ") { name(it) }}" }
                Fact(label, hosts.joinToString(", "), Evidence.UNKNOWN,
                    if (registered.isEmpty()) why else "$why $registered. A registration is not a location; addresses with no placement are often anycast.")
            }),
        )
        return MapView(
            MapMode.SOURCE_DNS, "Where lookups were answered",
            "Hostnames, aliases and addresses are what this audit observed; countries are placed from those addresses.$tableNote",
            rows, if (m.hosts.isEmpty()) "No tracker lookups were recorded in this audit, so there is nothing to place." else null,
            sourceFooter(m.source),
        )
    }

    private fun dnsLine(h: HostEndpoint): String {
        val addresses = if (h.addresses.isEmpty()) "no address recorded" else h.addresses.joinToString(", ")
        val alias = if (h.cnameChain.size > 1) "; alias chain observed: ${h.cnameChain.joinToString(" → ")}" else ""
        return "${h.host}: $addresses$alias"
    }

    private fun sourceFooter(s: GeoSource): List<String> = buildList {
        val built = ((s as? GeoSource.Loaded)?.lookup?.buildEpochSeconds)?.let { Instant.ofEpochSecond(it).atZone(ZoneOffset.UTC).toLocalDate() }
        add(
            "Countries are inferred from IP addresses" + (built?.let { " using GeoLite2-Country (database built $it)" } ?: "") +
                ". They show where an address is placed, not where data went or whether any law applies. " +
                "CDN and anycast addresses can be answered from a country other than the one listed.",
        )
        if (s is GeoSource.Loaded) add(LegalReference.ATTRIBUTION)
    }

    private fun ownerView(m: EndpointMapModel, owners: OwnerRegistry): MapView {
        val rows = m.hosts.filter { it.entity != null }.groupBy { it.entity!! }.toSortedMap().map { (entity, hosts) ->
            val countries = hosts.flatMap { it.located }.distinct().sorted()
            val reasons = hosts.mapNotNull { it.unknown }.distinct().joinToString(" ") { unknownLabel.getValue(it).second }
            val where = if (countries.isNotEmpty()) Fact("Where its lookups were answered", countries.joinToString(", ") { name(it) }, Evidence.INFERRED,
                "Placed from DNS answer addresses; not a statement about where data was processed.")
            else Fact("Where its lookups were answered", "Unknown", Evidence.UNKNOWN, reasons.ifEmpty { null })
            val facts = listOf(
                where,
                Fact("Domain registration", "Not looked up", Evidence.UNKNOWN,
                    "TraceVeil has no domain-registration data. A domain's registration country is not the server's or the owner's country."),
                headquarters(owners.find(entity)),
                Fact("Legal authority that may apply", LegalReference.NAME, Evidence.SOURCED,
                    "\"${LegalReference.WITHIN_INDIA}; ${LegalReference.OUTSIDE_INDIA}.\" ${LegalReference.CAVEAT}"),
            )
            MapRow(entity, Evidence.INFERRED, null, facts)
        } + m.hosts.filter { it.entity == null }.takeIf { it.isNotEmpty() }?.let { hosts ->
            listOf(MapRow("Owner not identified", Evidence.UNKNOWN, null,
                listOf(Fact("Hosts", hosts.joinToString(", ") { it.host }, Evidence.UNKNOWN, "These names do not match TraceVeil's tracker catalogue."))))
        }.orEmpty()
        return MapView(
            MapMode.OWNER_LEGAL, "Who runs it, and what law may apply",
            "Each vendor is matched from its domain name in TraceVeil's tracker catalogue. Server location, domain registration, " +
                "company headquarters and applicable law are different things, so they are shown separately.",
            rows, if (m.hosts.isEmpty()) "No tracker lookups were recorded in this audit, so there is no vendor to show." else null,
            listOf(
                "A vendor's headquarters appears only when TraceVeil's owner registry cites a source for it. Parent-company ownership is not established.",
                "Nothing here says a vendor or app broke a law; it shows which facts are known and which are not.",
            ),
        )
    }

    private fun headquarters(f: OwnerFacts?): Fact {
        val hq = f?.headquarters
        val inc = f?.incorporation
        if (hq == null && inc == null) return Fact("Company headquarters", "Not established", Evidence.UNKNOWN, "No cited source in TraceVeil's owner registry.")
        fun cite(c: SourcedCountry) = "${c.sourceUrl} (${c.kind.label}, as of ${c.asOf})"
        val note = listOfNotNull(hq?.let { "Source: ${cite(it)}" }, inc?.let { "Incorporated in ${name(it.iso)}. Source: ${cite(it)}" }).joinToString(". ")
        return if (hq != null) Fact("Company headquarters", name(hq.iso), Evidence.SOURCED, note)
        else Fact("Company headquarters", "Not established", Evidence.UNKNOWN, note)
    }
}
