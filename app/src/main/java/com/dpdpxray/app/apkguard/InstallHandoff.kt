package com.dpdpxray.app.apkguard

import com.dpdpxray.core.apk.Bilingual
import com.dpdpxray.core.apk.InstallBlock
import java.io.File
import java.security.MessageDigest

/**
 * Android's package installer, as "Install anyway" needs it. The real one ([PlatformInstaller]) uses a PackageInstaller
 * session that always asks the person to confirm; tests use a fake.
 */
interface InstallerGateway {
    /** Android's "Install unknown apps" switch for TraceVeil (PackageManager.canRequestPackageInstalls). */
    fun canRequestInstalls(): Boolean

    /** Creates an install session for [packageName] that requires the person's confirmation; returns its id. */
    fun createSession(packageName: String, sizeBytes: Long): Int

    /** Copies [apk] into the session as its only file. */
    fun write(sessionId: Int, apk: File)

    /** Commits the session; Android reports back for [checkKey] (see [InstallResultReceiver]). */
    fun commit(sessionId: Int, checkKey: String)

    fun abandon(sessionId: Int)
}

/** The exact copy APK Guard read, kept in TraceVeil's private cache until the person decides. */
data class HeldCopy(val file: File, val sha256: String, val packageName: String)

/** What tapping "Install anyway" led to. */
sealed interface HandoffStart {
    /** Android's "Install unknown apps" switch is off for TraceVeil: explain and open the setting, never bypass it. */
    data object NeedsPermission : HandoffStart

    /** Android would refuse this file (see [InstallBlock]); nothing was handed over. */
    data class Blocked(val block: InstallBlock) : HandoffStart

    /** The checked copy is gone or changed; nothing was handed over. */
    data class Unavailable(val reason: String) : HandoffStart

    /** The file is in Android's installer, which now asks the person to confirm. */
    data class Started(val sessionId: Int) : HandoffStart

    data class Failed(val reason: String) : HandoffStart
}

/**
 * "Install anyway": hands the exact file APK Guard checked to Android's installer. It refuses a file Android would
 * refuse, a copy that is missing or whose SHA-256 no longer matches the check, and it never works around Android's
 * "Install unknown apps" switch. Android itself then shows its confirmation; nothing is installed until the person
 * confirms there. Blocking (hashes and copies the file): call it off the main thread.
 */
class InstallHandoff(private val gateway: InstallerGateway) {
    fun start(checkKey: String, copy: HeldCopy?, block: InstallBlock?): HandoffStart {
        if (block != null) return HandoffStart.Blocked(block)
        if (copy == null || !copy.file.isFile) return HandoffStart.Unavailable(COPY_GONE)
        if (!gateway.canRequestInstalls()) return HandoffStart.NeedsPermission
        if (!sha256(copy.file).equals(copy.sha256, ignoreCase = true)) return HandoffStart.Unavailable(COPY_CHANGED)
        val session = try {
            gateway.createSession(copy.packageName, copy.file.length())
        } catch (e: Exception) {
            return HandoffStart.Failed("Android did not start an install (${e.javaClass.simpleName}). Nothing was installed.")
        }
        return try {
            gateway.write(session, copy.file)
            gateway.commit(session, checkKey)
            HandoffStart.Started(session)
        } catch (e: Exception) {
            runCatching { gateway.abandon(session) }
            HandoffStart.Failed("The file could not be handed to Android's installer (${e.javaClass.simpleName}). Nothing was installed.")
        }
    }

    companion object {
        const val COPY_GONE = "TraceVeil no longer has the copy it checked (for example after it was closed). Open the APK file again to check it."
        const val COPY_CHANGED = "The copy changed after it was checked, so TraceVeil will not install it. Open the APK file again to check it."

        /** Upper-case hex SHA-256 of [file], or null when it cannot be read. */
        fun sha256(file: File): String? = runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            digest.digest().joinToString("") { "%02X".format(it) }
        }.getOrNull()
    }
}

/** Where one "Install anyway" hand-off stands, as shown on the check screen. */
sealed interface InstallState {
    /** Copying the checked file into Android's installer. */
    data object Preparing : InstallState

    /** Android's own confirmation is on screen (or will be when TraceVeil is in front). */
    data object WaitingForYou : InstallState

    data class Installed(val packageName: String?) : InstallState

    /** The person cancelled in Android's installer. */
    data object Cancelled : InstallState

    /** Android did not install it; [detail] is Android's own status message, when it gave one. */
    data class Failed(val reason: Bilingual, val detail: String? = null) : InstallState
}

/** Maps what Android's installer reports (PackageInstaller.EXTRA_STATUS) to a plain outcome. */
object InstallOutcome {
    fun from(status: Int, packageName: String?, message: String?): InstallState {
        val detail = message?.trim()?.takeIf { it.isNotEmpty() }
        return when (status) {
            STATUS_PENDING_USER_ACTION -> InstallState.WaitingForYou
            STATUS_SUCCESS -> InstallState.Installed(packageName)
            STATUS_FAILURE_ABORTED -> InstallState.Cancelled
            STATUS_FAILURE_BLOCKED -> InstallState.Failed(Bilingual(
                "Not installed: Android or a device policy blocked this install.",
                "इंस्टॉल नहीं हुआ: Android या डिवाइस नीति ने यह इंस्टॉल रोक दिया।"), detail)
            STATUS_FAILURE_INVALID -> InstallState.Failed(Bilingual(
                "Not installed: Android says the file is not a valid app package. It may be damaged or incomplete.",
                "इंस्टॉल नहीं हुआ: Android के अनुसार यह फ़ाइल सही ऐप पैकेज नहीं है। हो सकता है यह ख़राब या अधूरी हो।"), detail)
            STATUS_FAILURE_CONFLICT -> InstallState.Failed(Bilingual(
                "Not installed: it conflicts with an app already on the phone, for example the same package signed with a different key.",
                "इंस्टॉल नहीं हुआ: यह फ़ोन में पहले से मौजूद किसी ऐप से टकराता है, जैसे वही पैकेज किसी दूसरी चाबी से साइन हो।"), detail)
            STATUS_FAILURE_STORAGE -> InstallState.Failed(Bilingual(
                "Not installed: there is not enough storage space on the phone.",
                "इंस्टॉल नहीं हुआ: फ़ोन में पर्याप्त जगह नहीं है।"), detail)
            STATUS_FAILURE_INCOMPATIBLE -> InstallState.Failed(Bilingual(
                "Not installed: this app does not work on this phone (for example, built for an older Android version or a different processor).",
                "इंस्टॉल नहीं हुआ: यह ऐप इस फ़ोन पर नहीं चलता (जैसे पुराने Android वर्ज़न या दूसरे प्रोसेसर के लिए बना)।"), detail)
            STATUS_FAILURE_TIMEOUT -> InstallState.Failed(Bilingual(
                "Not installed: Android's installer timed out. Try again.",
                "इंस्टॉल नहीं हुआ: Android के इंस्टॉलर का समय ख़त्म हो गया। फिर से कोशिश करें।"), detail)
            else -> InstallState.Failed(Bilingual("Not installed: Android did not install it.", "इंस्टॉल नहीं हुआ: Android ने इसे इंस्टॉल नहीं किया।"), detail)
        }
    }

    /** True once the hand-off has ended (installed, cancelled or failed). */
    fun isFinal(state: InstallState): Boolean = state !is InstallState.Preparing && state !is InstallState.WaitingForYou

    // PackageInstaller.STATUS_* values (stable public API constants).
    private const val STATUS_PENDING_USER_ACTION = -1
    private const val STATUS_SUCCESS = 0
    private const val STATUS_FAILURE_BLOCKED = 2
    private const val STATUS_FAILURE_ABORTED = 3
    private const val STATUS_FAILURE_INVALID = 4
    private const val STATUS_FAILURE_CONFLICT = 5
    private const val STATUS_FAILURE_STORAGE = 6
    private const val STATUS_FAILURE_INCOMPATIBLE = 7
    private const val STATUS_FAILURE_TIMEOUT = 8
}

/**
 * Packages the person just installed through "Install anyway", so the new-install alert does not ask them to check an
 * app they checked a moment ago. Kept in memory for [WINDOW_MS].
 */
class ExpectedInstalls {
    private val expected = HashMap<String, Long>()

    @Synchronized fun expect(packageName: String, now: Long) { expected[packageName] = now }

    @Synchronized fun isExpected(packageName: String?, now: Long): Boolean {
        val at = expected[packageName ?: return false] ?: return false
        return now - at in 0..WINDOW_MS
    }

    companion object {
        const val WINDOW_MS = 30 * 60_000L
    }
}
