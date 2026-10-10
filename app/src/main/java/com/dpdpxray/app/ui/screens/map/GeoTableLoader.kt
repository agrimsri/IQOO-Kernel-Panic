package com.dpdpxray.app.ui.screens.map

import android.content.Context
import com.dpdpxray.core.geo.BundledGeoTable
import com.dpdpxray.core.geo.GeoSource
import com.dpdpxray.core.geo.GeoTableChoice
import com.dpdpxray.core.geo.IpCountryLookup
import com.dpdpxray.core.geo.MmdbFormatException
import com.dpdpxray.core.geo.MmdbReader
import com.dpdpxray.core.geo.TableOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Picks the IP-to-country table for the endpoint map, entirely offline:
 * 1. the person's own MaxMind GeoLite2-Country.mmdb, if they placed one on the phone (it is never bundled: its terms
 *    are in docs/ATTRIBUTION.md), e.g. `adb push GeoLite2-Country.mmdb /sdcard/Android/data/com.dpdpxray.app/files/geo/`;
 * 2. otherwise the DB-IP "IP to Country Lite" table shipped in the APK (CC BY 4.0, unmodified).
 * Each table is parsed once and reused (the user's file until it changes).
 *
 * The repository keeps the table gzip-compressed ([BUNDLED_SOURCE]). The Android Gradle Plugin gunzips `*.gz` assets
 * when it merges them, so the APK holds the original file as [BUNDLED_ASSET] (checked on the built APK: 8,348,509 bytes,
 * the documented SHA-256), stored deflate-compressed. A build that kept the `.gz` name is read through gzip instead.
 */
object GeoTableLoader {
    const val FILE_NAME = GeoTableChoice.USER_FILE_NAME
    /** Path under src/main/assets in the repository. */
    const val BUNDLED_SOURCE = "geo/dbip-country-lite.mmdb.gz"
    /** Name inside the APK after the asset merge gunzips [BUNDLED_SOURCE]. */
    const val BUNDLED_ASSET = "geo/dbip-country-lite.mmdb"

    private class Cached(val stamp: Long, val source: GeoSource)
    @Volatile private var cached: Cached? = null
    @Volatile private var bundled: GeoSource? = null

    fun folder(context: Context): File = File(context.getExternalFilesDir(null), "geo")

    fun pushCommand(context: Context) = "adb push $FILE_NAME ${folder(context).path}/"

    /** Never throws: a missing, unreadable or corrupt table becomes a [GeoSource] that says so. */
    suspend fun load(context: Context): GeoSource = withContext(Dispatchers.IO) {
        GeoTableChoice.choose(userTable(context)) { bundledTable(context) }
    }

    /** Null when the person has not placed a file of their own. */
    private fun userTable(context: Context): GeoSource? {
        val file = File(folder(context), FILE_NAME)
        if (!file.isFile) return null.also { cached = null }
        val stamp = file.lastModified() xor (file.length() shl 20)
        cached?.takeIf { it.stamp == stamp }?.let { return it.source }
        return parse(TableOrigin.USER_FILE) { file.readBytes() }.also { cached = Cached(stamp, it) }
    }

    @Synchronized
    private fun bundledTable(context: Context): GeoSource = bundled ?: parse(TableOrigin.BUNDLED) {
        openBundled(context).use { BundledGeoTable.readFully(it) }
    }.also { if (it is GeoSource.Loaded) bundled = it } // a failure is retried next time

    private fun openBundled(context: Context): InputStream = try {
        context.assets.open(BUNDLED_ASSET)
    } catch (_: FileNotFoundException) {
        GZIPInputStream(context.assets.open(BUNDLED_SOURCE), 64 * 1024)
    }

    private fun parse(origin: TableOrigin, bytes: () -> ByteArray): GeoSource = try {
        GeoSource.Loaded(IpCountryLookup(MmdbReader.open(bytes())), origin)
    } catch (e: MmdbFormatException) {
        GeoSource.Unreadable(e.message ?: "not a MaxMind-format database")
    } catch (e: java.io.IOException) {
        GeoSource.Unreadable("could not read the file")
    } catch (e: OutOfMemoryError) {
        GeoSource.Unreadable("not enough memory to load the table")
    }
}
