package com.dpdpxray.app.automation

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.MainActivity
import com.dpdpxray.app.ui.components.Callout
import com.dpdpxray.app.ui.components.Note
import com.dpdpxray.app.ui.components.PrimaryAction
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.XrayTheme
import com.dpdpxray.core.automation.RunDecision

/**
 * Entry point for TraceVeil Runner (`traceveil-ci run`). The manifest lets only callers holding DUMP (the adb shell and
 * the system) open it, by explicit component; it then checks Developer automation and the pairing token. An accepted
 * request opens the normal live check screen; a refused one stays on screen with the reason and what to fix.
 */
class AutomationActivity : ComponentActivity() {
    private data class Refusal(val reason: String, val fix: String, val reportedToLaptop: Boolean)

    private var refusal: Refusal? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refusal = when {
            // Recreated (rotation): show the same refusal again, never handle the request twice.
            savedInstanceState != null -> savedInstanceState.getString(KEY_REASON)?.let {
                Refusal(it, savedInstanceState.getString(KEY_FIX).orEmpty(), savedInstanceState.getBoolean(KEY_REPORTED))
            }
            // Reopened from Recents: an old request is not a new one.
            intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0 -> null
            else -> when (val decision = AutomationRunner.handle(this, AutomationIntents.input(intent))) {
                is RunDecision.Accepted -> {
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra(AutomationIntents.EXTRA_OPEN_LIVE, true),
                    )
                    null
                }
                is RunDecision.Refused -> Refusal(decision.reason, decision.fix, reportedToLaptop = decision.runId != null)
            }
        }
        val shown = refusal ?: run { finish(); return }
        setContent {
            XrayTheme(stageMode = false) {
                Column(
                    Modifier.fillMaxSize().background(Film.base).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("Runner request refused", style = MaterialTheme.typography.headlineMedium, color = Film.bone)
                    Note("A laptop asked TraceVeil to start a check over USB (Developer automation). Nothing was started.")
                    Callout(shown.reason, title = "Why")
                    if (shown.fix.isNotBlank()) Callout(shown.fix, title = "What to fix", color = Film.scan, glyph = Glyph.INFO)
                    if (shown.reportedToLaptop) Note("The same reason is in this run's status file, so traceveil-ci shows it on the laptop too.")
                    PrimaryAction("Close", { finish() })
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        refusal?.let {
            outState.putString(KEY_REASON, it.reason)
            outState.putString(KEY_FIX, it.fix)
            outState.putBoolean(KEY_REPORTED, it.reportedToLaptop)
        }
    }

    private companion object {
        const val KEY_REASON = "refusal_reason"
        const val KEY_FIX = "refusal_fix"
        const val KEY_REPORTED = "refusal_reported"
    }
}
