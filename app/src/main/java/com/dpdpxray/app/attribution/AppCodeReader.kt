package com.dpdpxray.app.attribution

import android.content.Context
import android.content.pm.PackageManager
import com.dpdpxray.core.apk.ApkCodeScanner
import com.dpdpxray.core.apk.CodeScanResult
import java.io.File

/** State of the optional "Found in the app's code" read on the Results screen. */
sealed interface CodeScanState {
    data object Idle : CodeScanState
    data object Running : CodeScanState

    /** [versionCode] is the version installed now, which may differ from the audited one. */
    data class Done(val result: CodeScanResult, val versionName: String?, val versionCode: Long?) : CodeScanState

    data class Unavailable(val reason: String) : CodeScanState
}

/**
 * Reads SDK class names from the APK files of an app installed on this phone (read-only: Android lets any app read
 * installed APK files, and TraceVeil already declares QUERY_ALL_PACKAGES to see the app). Started only when the person
 * taps the button; blocking and bounded by core's CodeScanLimits, so call it from Dispatchers.IO.
 */
object AppCodeReader {
    fun scan(context: Context, pkg: String): CodeScanState {
        val info = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS)
        }.getOrNull() ?: return CodeScanState.Unavailable("The app is not installed on this phone now, so its code could not be read.")
        val ai = info.applicationInfo ?: return CodeScanState.Unavailable("Android did not report where the app's files are.")
        val files = (listOfNotNull(ai.sourceDir) + ai.splitSourceDirs.orEmpty()).distinct().map(::File)
        if (files.isEmpty()) return CodeScanState.Unavailable("Android did not report where the app's files are.")
        val result = runCatching { ApkCodeScanner.scan(files) }.getOrElse {
            return CodeScanState.Unavailable("The app's code files could not be read on this phone.")
        }
        if (result.dexFilesRead == 0) {
            return CodeScanState.Unavailable(
                if (result.apksUnreadable > 0) "Android did not let TraceVeil open the app's files." else "No readable code files were found in the app's files.",
            )
        }
        return CodeScanState.Done(result, info.versionName, info.longVersionCode)
    }
}
