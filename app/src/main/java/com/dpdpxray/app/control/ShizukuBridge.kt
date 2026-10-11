package com.dpdpxray.app.control

import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Shell-level control through Shizuku (paired on-device via wireless debugging; no root, no PC).
 * Used to restart the audited app before each plan (`am force-stop`, data kept), and, only with approval for a test app,
 * to reset it (`pm clear`) so a plan starts like a fresh install.
 */
object ShizukuBridge {
    const val REQUEST_CODE = 4711

    fun isRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean = isRunning() && runCatching {
        !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestPermission() {
        if (isRunning()) runCatching { Shizuku.requestPermission(REQUEST_CODE) }
    }

    /** Runs a shell command as the `shell` user. */
    suspend fun exec(vararg command: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            check(hasPermission()) { "Shizuku is not running or not authorised" }
            // newProcess is hidden in Shizuku API 13; it remains the simplest way to run a one-shot command.
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
            ).apply { isAccessible = true }
            val process = method.invoke(null, arrayOf(*command), null, null) as Process
            val out = process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            val code = process.waitFor()
            check(code == 0) { "exit $code: ${err.ifBlank { out }}".trim() }
            out.trim()
        }
    }

    suspend fun clearAppData(pkg: String) = exec("pm", "clear", pkg)

    /** Stops the app's process (a restart once it is opened again). Its data, sign-in and settings are untouched. */
    suspend fun forceStop(pkg: String) = exec("am", "force-stop", pkg)

    /** Whether [pkg] has a running process; null when that cannot be read (no Shizuku, or the shell failed). */
    suspend fun processRunning(pkg: String): Boolean? {
        if (!hasPermission()) return null
        return exec("pidof", pkg).fold({ it.isNotBlank() }, { e -> if (e.message?.startsWith("exit 1") == true) false else null })
    }

    /**
     * The kernel's TCP and UDP socket tables (IPv4 and IPv6) as text, read by the shell; null when unreadable. Only
     * counted (core SocketTable), never stored or logged.
     */
    suspend fun socketTables(): String? =
        exec("sh", "-c", "cat /proc/net/tcp /proc/net/tcp6 /proc/net/udp /proc/net/udp6 2>/dev/null; true").getOrNull()?.takeIf { it.isNotBlank() }
}
