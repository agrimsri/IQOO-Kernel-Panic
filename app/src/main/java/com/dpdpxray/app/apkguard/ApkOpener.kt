package com.dpdpxray.app.apkguard

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.dpdpxray.app.ui.guide.Bi

/**
 * Which app Android uses when the person taps an APK file. When TraceVeil is the default ("Always"), every tapped APK
 * is checked before Android's installer sees it; that is the closest Android allows to "check before every install".
 */
object ApkOpener {
    enum class State {
        /** TraceVeil was chosen with "Always": tapped APK files open in TraceVeil first. */
        TRACEVEIL,

        /** Android shows its "Open with" choice each time. */
        ASK_EACH_TIME,

        /** Another app (usually Android's package installer) was chosen as the default. */
        OTHER_APP,

        /** Android does not offer TraceVeil for APK files (for example a build without the filter). */
        NOT_OFFERED,

        /** Android did not report a handler. */
        UNKNOWN,
    }

    /**
     * [resolved] is the package of the activity Android picks for a tapped APK (MATCH_DEFAULT_ONLY), [handlers] the
     * packages of every activity that can open one. A resolved package that is not a handler is Android's chooser.
     */
    fun state(resolved: String?, handlers: Set<String>, ownPackage: String): State = when {
        resolved == null -> State.UNKNOWN
        ownPackage !in handlers -> State.NOT_OFFERED
        resolved == ownPackage -> State.TRACEVEIL
        resolved !in handlers -> State.ASK_EACH_TIME
        else -> State.OTHER_APP
    }

    fun summary(state: State, otherLabel: String?): Bi {
        val other = otherLabel?.takeIf { it.isNotBlank() } ?: "another app"
        return when (state) {
            State.TRACEVEIL -> Bi(
                "On: every APK you tap opens in TraceVeil first, then Android's installer only if you tap Install anyway.",
                "चालू: जिस भी APK पर आप टैप करें, वह पहले TraceVeil में खुलती है; Android का इंस्टॉलर तभी, जब आप Install anyway दबाएँ।",
            )
            State.ASK_EACH_TIME -> Bi(
                "Android asks each time. Next time you tap an APK file, choose TraceVeil and then Always, so every APK is checked first.",
                "Android हर बार पूछता है। अगली बार APK फ़ाइल पर टैप करें तो TraceVeil और फिर Always चुनें, ताकि हर APK पहले जाँची जाए।",
            )
            State.OTHER_APP -> Bi(
                "$other opens APK files directly. To check them first: Settings › Apps › $other › Open by default › Clear defaults, then tap an APK and choose TraceVeil.",
                "$other APK फ़ाइलें सीधे खोलता है। पहले जाँचने के लिए: सेटिंग्स › ऐप्स › $other › डिफ़ॉल्ट रूप से खोलें › डिफ़ॉल्ट हटाएँ, फिर APK पर टैप करके TraceVeil चुनें।",
            )
            State.NOT_OFFERED -> Bi(
                "Android does not offer TraceVeil for APK files on this phone. Share the APK to TraceVeil or use Choose a file.",
                "इस फ़ोन पर Android APK फ़ाइलों के लिए TraceVeil नहीं दिखाता। APK को TraceVeil पर शेयर करें या Choose a file इस्तेमाल करें।",
            )
            State.UNKNOWN -> Bi(
                "Android did not say which app opens APK files.",
                "Android ने नहीं बताया कि APK फ़ाइलें कौन-सा ऐप खोलता है।",
            )
        }
    }

    /** Asks Android which app opens a tapped APK now, and that app's name. Read-only. */
    fun read(context: Context): Pair<State, String?> = runCatching {
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://com.dpdpxray.app.probe/check.apk"), IncomingApk.APK_MIME)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.resolveActivity(probe, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else @Suppress("DEPRECATION") pm.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)
        val handlers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(probe, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else @Suppress("DEPRECATION") pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
        val resolvedPackage = resolved?.activityInfo?.packageName
        val state = state(resolvedPackage, handlers.mapNotNull { it.activityInfo?.packageName }.toSet(), context.packageName)
        val label = if (state == State.OTHER_APP) resolved?.loadLabel(pm)?.toString() else null
        state to label
    }.getOrDefault(State.UNKNOWN to null)
}
