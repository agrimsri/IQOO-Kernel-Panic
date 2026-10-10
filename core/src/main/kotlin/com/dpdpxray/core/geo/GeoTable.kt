package com.dpdpxray.core.geo

import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.time.ZoneOffset

/** Where the IP-to-country table in use came from. */
enum class TableOrigin(val label: String) {
    BUNDLED("bundled with TraceVeil"),
    USER_FILE("your own file on this phone"),
}

/**
 * Which IP-to-country table is in use, decided by the database's own metadata (`database_type`), never by its file
 * name, so a renamed file is still credited correctly. A table TraceVeil does not recognise gets no attribution claim.
 */
enum class GeoTableKind(val label: String, val shortLabel: String, val attribution: String?, val licence: String?) {
    DBIP_LITE("DB-IP IP to Country Lite", "DB-IP Lite", BundledGeoTable.ATTRIBUTION, "CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/)"),
    GEOLITE2("MaxMind GeoLite2 Country", "GeoLite2", MapPresenter.ATTRIBUTION, "GeoLite End User License Agreement"),
    OTHER("an IP-to-country table TraceVeil does not recognise", "Unrecognised table", null, null);

    companion object {
        fun of(databaseType: String?): GeoTableKind = when {
            databaseType == null -> OTHER
            databaseType.startsWith("DBIP-", ignoreCase = true) && databaseType.endsWith("-Lite", ignoreCase = true) -> DBIP_LITE
            databaseType.startsWith("GeoLite2-", ignoreCase = true) -> GEOLITE2
            else -> OTHER
        }
    }
}

/** What the map says about the table it used: which one, where it came from, when it was built, and its credit. */
data class GeoTableInfo(val kind: GeoTableKind, val origin: TableOrigin, val databaseType: String?, val builtEpochSeconds: Long?) {
    val builtDate: String? get() = builtEpochSeconds?.let { Instant.ofEpochSecond(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }

    /** e.g. "DB-IP IP to Country Lite (bundled with TraceVeil, database built 2026-10-01)". */
    val description: String
        get() {
            val type = if (kind == GeoTableKind.OTHER && databaseType != null) " \"$databaseType\"" else ""
            val built = builtDate?.let { ", database built $it" } ?: ", build date not recorded in the file"
            return "${kind.label}$type (${origin.label}$built)"
        }

    /** One short line for the top of the map card, e.g. "Countries from DB-IP Lite · bundled · built 2026-10-01". */
    val headline: String
        get() = listOfNotNull("Countries from ${kind.shortLabel}", if (origin == TableOrigin.BUNDLED) "bundled" else "your file", builtDate?.let { "built $it" })
            .joinToString(" · ")

    /** The attribution line the table's licence asks for, or null when TraceVeil does not know the table. */
    val credit: String?
        get() = when (kind) {
            GeoTableKind.DBIP_LITE -> "${kind.attribution}. Used under ${kind.licence}, unmodified."
            GeoTableKind.GEOLITE2 -> kind.attribution
            GeoTableKind.OTHER -> null
        }

    /** A note once the table is older than [STALE_AFTER_DAYS], worded for the table's own terms. */
    fun ageNote(nowEpochSeconds: Long): String? {
        val built = builtEpochSeconds ?: return null
        val days = (nowEpochSeconds - built) / 86_400
        if (days <= STALE_AFTER_DAYS) return null
        return if (kind == GeoTableKind.GEOLITE2) {
            "This database was built $days days ago. MaxMind publishes newer releases and the GeoLite EULA asks users to move to the updated version."
        } else {
            "This table was built $days days ago. Address assignments change over time, so an older table can place some addresses in the wrong country."
        }
    }

    companion object {
        const val STALE_AFTER_DAYS = 30L

        fun of(source: GeoSource.Loaded) = GeoTableInfo(
            GeoTableKind.of(source.lookup.databaseType), source.origin, source.lookup.databaseType, source.lookup.buildEpochSeconds,
        )
    }
}

/**
 * The table shipped inside the app: DB-IP "IP to Country Lite", which DB-IP licenses under CC BY 4.0 and so may be
 * redistributed with attribution. Stored gzip-compressed and unmodified; its decompressed bytes match [SHA256].
 * Details and the update procedure are in docs/ATTRIBUTION.md.
 */
object BundledGeoTable {
    const val ATTRIBUTION = "IP Geolocation by DB-IP (https://db-ip.com)"
    const val DATABASE_TYPE = "DBIP-Country-Lite"
    const val BYTES = 8_348_509
    const val SHA256 = "dbd70ccfa2a13627eaf4913d19920a1a229f1b3c5439ac01c509995b6364202a"
    /** 2026-10-01T01:36:12Z, the file's own `build_epoch`. */
    const val BUILD_EPOCH = 1_790_818_572L
    private const val MAX_BYTES = 64 * 1024 * 1024

    /**
     * Reads a whole (already decompressed) stream into one array sized for [expected] bytes, so loading the table never
     * holds two copies of it. A longer stream still reads correctly; one larger than [limit] (64 MiB) is refused.
     */
    fun readFully(input: InputStream, expected: Int = BYTES, limit: Int = MAX_BYTES): ByteArray {
        var buf = ByteArray(expected.coerceIn(1, limit))
        var n = 0
        while (true) {
            if (n == buf.size) {
                val next = input.read()
                if (next < 0) break
                if (buf.size >= limit) throw IOException("IP-to-country table larger than $limit bytes")
                buf = buf.copyOf(minOf(buf.size * 2, limit))
                buf[n++] = next.toByte()
                continue
            }
            val read = input.read(buf, n, buf.size - n)
            if (read < 0) break
            n += read
        }
        return if (n == buf.size) buf else buf.copyOf(n)
    }
}

/** Picks the table the map uses. */
object GeoTableChoice {
    const val USER_FILE_NAME = "GeoLite2-Country.mmdb"

    /**
     * The person's own table wins when it loads. Otherwise the bundled one is used, with a note when their file was
     * present but could not be read, so a failure is never silent. [bundled] is only called when it is needed.
     */
    fun choose(user: GeoSource?, bundled: () -> GeoSource): GeoSource {
        if (user is GeoSource.Loaded) return user
        val fallback = bundled()
        val failed = user as? GeoSource.Unreadable
        return when {
            fallback is GeoSource.Loaded && failed != null ->
                fallback.copy(notes = fallback.notes + "Your $USER_FILE_NAME could not be read (${failed.reason}), so the bundled table was used instead.")
            fallback is GeoSource.Loaded -> fallback
            failed != null && fallback is GeoSource.Unreadable -> GeoSource.Unreadable("your $USER_FILE_NAME: ${failed.reason}; bundled table: ${fallback.reason}")
            failed != null -> failed
            else -> fallback
        }
    }
}
