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
import androidx.lifecycle.lifecycleScope
import com.dpdpxray.app.MainActivity
import com.dpdpxray.app.ui.components.Callout
import com.dpdpxray.app.ui.components.Note
import com.dpdpxray.app.ui.components.PrimaryAction
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.XrayTheme
import com.dpdpxray.core.automation.RunDecision
import kotlinx.coroutines.launch

/**
 * Entry point for TraceVeil Runner (`traceveil-ci run`). The manifest lets only callers holding DUMP (the adb shell and
 * the system) open it, by explicit component, in its own task; it then checks Developer automation and the pairing
 * token. An accepted request opens the normal live check screen; a refused one stays on screen with the reason and what
 * to fix.
 */
class AutomationActivity : ComponentActivity() {
    private data class Refusal(val reason: String, val fix: String, val reportedToLaptop: Boolean)

    private var refusal: Refusal? = null
    /** The request is still being decided (a short wait for Shizuku); a recreated screen decides it again. */
    private var deciding = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val saved = savedInstanceState?.getString(KEY_REASON)?.let {
            Refusal(it, savedInstanceState.getString(KEY_FIX).orEmpty(), savedInstanceState.getBoolean(KEY_REPORTED))
        }
        when {
            // Recreated (rotation) after a refusal: show it again, never handle the request twice.
            saved != null -> show(saved)
            // Recreated after the request was handled: nothing more to do. (Recreated while it was still being decided:
            // nothing was started or written yet, so it is decided below.)
            savedInstanceState != null && !savedInstanceState.getBoolean(KEY_DECIDING) -> finish()
            // Reopened from Recents: an old request is not a new one.
            intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0 -> finish()
            else -> decide()
        }
    }

    private fun decide() {
        deciding = true
        val input = AutomationIntents.input(intent)
        lifecycleScope.launch {
            val decision = AutomationRunner.handle(this@AutomationActivity, input)
            deciding = false
            when (decision) {
                is RunDecision.Accepted -> {
                    startActivity(
                        Intent(this@AutomationActivity, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra(AutomationIntents.EXTRA_OPEN_LIVE, true),
                    )
                    finish()
                }
                is RunDecision.Refused -> show(Refusal(decision.reason, decision.fix, reportedToLaptop = decision.runId != null))
            }
        }
    }

    private fun show(shown: Refusal) {
        refusal = shown
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
        outState.putBoolean(KEY_DECIDING, deciding)
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
        const val KEY_DECIDING = "deciding"
    }
}
