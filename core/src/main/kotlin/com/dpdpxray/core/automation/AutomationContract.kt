package com.dpdpxray.core.automation

import com.dpdpxray.core.model.AuditPlan
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Random

/*
 * TraceVeil Runner: a developer starts a consent audit on a USB-connected test phone from a laptop or CI runner
 * (`traceveil-ci run`), and the phone runs the same audit a person starts from the app.
 *
 * The laptop sends one explicit intent to [AutomationContract.COMPONENT] with string/boolean extras only. The phone
 * validates it ([RunRequests.decide]) and writes its progress to getExternalFilesDir(null)/automation/<runId>/:
 * status.json ([AutomationStatus]) at once, and bundle.json (the standard traceveil.audit.v1 export) when the audit is
 * saved. The laptop reads both over adb. The intent never carries file paths or commands; the only path element taken
 * from it is the run id, and only after it matches [A-Za-z0-9_-]{1,40}.
 *
 * This file is shared by the app (writer) and the CLI (reader), so both sides use the same names.
 */
object AutomationContract {
    const val APP_PACKAGE = "com.dpdpxray.app"
    const val ACTIVITY_CLASS = "com.dpdpxray.app.automation.AutomationActivity"
    const val COMPONENT = "$APP_PACKAGE/$ACTIVITY_CLASS"

    const val EXTRA_TOKEN = "token"
    const val EXTRA_PACKAGE = "package"
    const val EXTRA_MODE = "mode"
    const val EXTRA_AUTO_TAP = "autoTap"
    const val EXTRA_CHILD_FACING = "childFacing"
    const val EXTRA_RUN_ID = "runId"

    /** Folder under the app's external files directory; one subfolder per run id. */
    const val RUNS_DIR = "automation"
    const val STATUS_FILE = "status.json"
    const val BUNDLE_FILE = "bundle.json"

    /** getExternalFilesDir(null) for the primary user, as `adb shell` and `adb pull` see it. */
    const val DEVICE_FILES_DIR = "/sdcard/Android/data/$APP_PACKAGE/files"

    const val MAX_RUN_ID = 40
    private val RUN_ID = Regex("[A-Za-z0-9_-]{1,$MAX_RUN_ID}")
    // Android package names: two or more segments, each starting with a letter.
    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val TOKEN = Regex("[A-Za-z0-9_-]{16,128}")

    fun isValidRunId(value: String?): Boolean = value != null && RUN_ID.matches(value)
    fun isValidPackage(value: String?): Boolean = value != null && value.length <= 255 && PACKAGE.matches(value)
    fun isValidToken(value: String?): Boolean = value != null && TOKEN.matches(value)

    fun deviceRunDir(runId: String): String {
        require(isValidRunId(runId)) { "invalid run id" }
        return "$DEVICE_FILES_DIR/$RUNS_DIR/$runId"
    }

    fun deviceStatusPath(runId: String): String = "${deviceRunDir(runId)}/$STATUS_FILE"
    fun deviceBundlePath(runId: String): String = "${deviceRunDir(runId)}/$BUNDLE_FILE"
}

/** What the runner asks for. The plans and timing are the same as the matching choice in the app's check setup. */
enum class AutomationMode(val wire: String, val plans: List<AuditPlan>, val fastPreview: Boolean, val label: String) {
    /** Silent, Reject, Accept, each after a fresh reset of an allowlisted test app (needs Shizuku). */
    FULL("full", listOf(AuditPlan.SILENT, AuditPlan.REJECT, AuditPlan.ACCEPT), false, "Full audit"),
    /** One Accept plan with normal timing. */
    QUICK("quick", listOf(AuditPlan.ACCEPT), false, "Quick audit"),
    /** One Accept plan with the shorter Fast Preview timing: a first look, not a full audit. */
    PREVIEW("preview", listOf(AuditPlan.ACCEPT), true, "Fast Preview");

    companion object {
        fun parse(value: String?): AutomationMode? = entries.firstOrNull { it.wire == value }
    }
}

/** Random pairing token shown in the app's Settings. 24 symbols from a 32-symbol alphabet: 120 bits. */
object PairingTokens {
    // No l, o, 0 or 1, so the token survives being read aloud or typed from the screen.
    private const val ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"
    private const val GROUPS = 4
    private const val GROUP_SIZE = 6
    const val ENTROPY_BITS = GROUPS * GROUP_SIZE * 5

    fun generate(random: Random = SecureRandom()): String =
        (0 until GROUPS).joinToString("-") { (0 until GROUP_SIZE).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("") }

    /** Exact match, compared in constant time for equal lengths. A missing or empty token never matches. */
    fun matches(expected: String?, presented: String?): Boolean {
        if (expected.isNullOrEmpty() || presented.isNullOrEmpty()) return false
        return MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), presented.toByteArray(Charsets.UTF_8))
    }
}
