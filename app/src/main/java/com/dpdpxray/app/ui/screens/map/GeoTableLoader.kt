package com.dpdpxray.app.ui.screens.map

import android.content.Context
import com.dpdpxray.core.geo.GeoSource
import com.dpdpxray.core.geo.IpCountryLookup
import com.dpdpxray.core.geo.MmdbFormatException
import com.dpdpxray.core.geo.MmdbReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Finds the MaxMind GeoLite2-Country database on the phone. The file is not part of the APK or the repository (its
 * licence does not allow public redistribution); it is placed next to the AI models, like them:
 *   adb push GeoLite2-Country.mmdb /sdcard/Android/data/com.dpdpxray.app/files/geo/
 * Parsed once and reused until the file changes.
 */
object GeoTableLoader {
    const val FILE_NAME = "GeoLite2-Country.mmdb"

    private class Cached(val stamp: Long, val source: GeoSource)
    @Volatile private var cached: Cached? = null

    fun folder(context: Context): File = File(context.getExternalFilesDir(null), "geo")

    fun pushCommand(context: Context) = "adb push $FILE_NAME ${folder(context).path}/"

    /** Never throws: a missing, unreadable or corrupt file becomes a [GeoSource] that says so. */
    suspend fun load(context: Context): GeoSource = withContext(Dispatchers.IO) {
        val file = File(folder(context), FILE_NAME)
        if (!file.isFile) return@withContext GeoSource.NotInstalled.also { cached = null }
        val stamp = file.lastModified() xor (file.length() shl 20)
        cached?.takeIf { it.stamp == stamp }?.let { return@withContext it.source }
        val source = try {
            GeoSource.Loaded(IpCountryLookup(MmdbReader.open(file.readBytes())))
        } catch (e: MmdbFormatException) {
            GeoSource.Unreadable(e.message ?: "not a MaxMind database")
        } catch (e: java.io.IOException) {
            GeoSource.Unreadable("could not read the file")
        } catch (e: OutOfMemoryError) {
            GeoSource.Unreadable("not enough memory to load the database")
        }
        source.also { cached = Cached(stamp, it) }
    }
}
