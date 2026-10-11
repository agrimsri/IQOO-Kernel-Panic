package com.dpdpxray.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.audit.BlockedSteps
import com.dpdpxray.app.audit.CheckAgain
import com.dpdpxray.app.audit.DiagnosisCopy
import com.dpdpxray.app.control.DeviceChecks
import com.dpdpxray.app.data.AuditBundle
import com.dpdpxray.app.ui.XrayViewModel
import com.dpdpxray.app.ui.guide.Bi
import com.dpdpxray.app.ui.guide.GuideLang
import com.dpdpxray.app.ui.system.Group
import com.dpdpxray.app.ui.system.GroupDivider
import com.dpdpxray.app.ui.system.ListRow
import com.dpdpxray.app.ui.system.Notice
import com.dpdpxray.app.ui.system.NoticeKind
import com.dpdpxray.app.ui.system.PrimaryButton
import com.dpdpxray.app.ui.system.StatusDot
import com.dpdpxray.app.ui.system.TextAction
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.core.rules.Inconclusive
import com.dpdpxray.core.rules.VisibilityDiagnosis
import com.dpdpxray.core.rules.VisibilityDiagnosis.Action

/** What the result's "Why there's no score" section offers, decided from the saved audit alone. Pure, so it is tested. */
internal object NoScoreHelp {
    /** The diagnosis items (too few lookups), or one consent-history item, or nothing for a scored result or a sample. */
    fun items(b: AuditBundle): List<VisibilityDiagnosis.Item> = if (b.meta.sample) emptyList()
        else VisibilityDiagnosis.forResult(b.result, b.sessions)

    fun historyUnknown(b: AuditBundle): Boolean =
        !b.meta.sample && Inconclusive.reason(b.result.findings) == Inconclusive.Reason.CONSENT_HISTORY_UNKNOWN

    /** True when this section brings its own filled action, so the result's next steps must not add a second one. */
    fun hasPrimary(b: AuditBundle): Boolean = VisibilityDiagnosis.primaryAction(items(b)) != Action.NONE

    /**
     * The text-link actions under the filled one: every other cause's action, plus a restart (the way to check again
     * after fixing a setting). "Check again" and "Check again with a restart" do the same thing, so only one shows.
     * Nothing for a consent-history-only section: a restart keeps the data, so it cannot change that result.
     */
    fun otherActions(items: List<VisibilityDiagnosis.Item>): List<Action> {
        if (items.isEmpty()) return emptyList()
        val primary = VisibilityDiagnosis.primaryAction(items)
        val restartLike = setOf(Action.CHECK_AGAIN, Action.CHECK_AGAIN_WITH_RESTART)
        return (items.map { it.action } + Action.CHECK_AGAIN_WITH_RESTART).distinct()
            .filter { it != Action.NONE && it != primary && it != Action.CHECK_AGAIN }
            .filterNot { it == Action.CHECK_AGAIN_WITH_RESTART && primary in restartLike }
    }

    val historyTitle = Bi("Consent history unknown", "सहमति का इतिहास अज्ञात")
    val historyBody = Bi(
        "The app kept its data from earlier use and showed no consent screen in this check, so whether you agreed earlier (for example at sign-up) " +
            "can't be seen. That is a real limit, not a capture problem: only a fresh install shows its first-launch consent.",
        "ऐप ने पहले इस्तेमाल का डेटा रखा और इस जाँच में सहमति स्क्रीन नहीं दिखाई, इसलिए यह नहीं दिखता कि आपने पहले (जैसे साइन-अप पर) सहमति दी थी या नहीं। " +
            "यह असली सीमा है, कैप्चर की समस्या नहीं: पहली बार की सहमति सिर्फ़ नए इंस्टॉल पर दिखती है।",
    )
    val noShizuku = Bi(
        "Without the Shizuku helper TraceVeil can't restart apps. You can close it yourself: App info › Force stop (your data stays), then check again.",
        "Shizuku सहायक के बिना TraceVeil ऐप रीस्टार्ट नहीं कर सकता। आप खुद बंद कर सकते हैं: App info › Force stop (आपका डेटा बना रहता है), फिर दोबारा जाँचें।",
    )
    val openAppInfo = Bi("Open App info", "App info खोलें")
}

/**
 * "Why there's no score": the causes the phone recorded for too few lookups (or the consent-history limit), each in one
 * row, then the one action most likely to help as the only filled button, the other actions as text links, and a reset
 * only for an app the person develops, after a confirmation. Never a score: the section only explains and offers.
 */
@Composable
internal fun NoScoreSection(vm: XrayViewModel, b: AuditBundle, lang: GuideLang, onLive: () -> Unit, onQuarantine: (String) -> Unit, modifier: Modifier = Modifier) {
    val items = remember(b.meta.id) { NoScoreHelp.items(b) }
    val history = remember(b.meta.id) { NoScoreHelp.historyUnknown(b) }
    if (items.isEmpty() && !history) return
    val ctx = LocalContext.current
    val pf by vm.preflight.collectAsState()
    val pkg = b.meta.targetPackage
    val resetOk = remember(pkg) { vm.resetEligible(pkg) }
    var confirmReset by remember { mutableStateOf(false) }
    val restart: () -> Unit = { vm.checkAgain(b, CheckAgain.Mode.RESTART, onLive) }
    fun run(a: Action) = when (a) {
        Action.CHECK_AGAIN_WITH_RESTART, Action.CHECK_AGAIN, Action.NONE -> restart()
        Action.TURN_OFF_PRIVATE_DNS -> DeviceChecks.openPrivateDnsSettings(ctx)
        Action.TURN_OFF_OTHER_VPN -> DeviceChecks.openVpnSettings(ctx)
        Action.CONNECT_TO_INTERNET -> DeviceChecks.openInternetSettings(ctx)
        Action.RUN_WITH_INTERNET_CUT_OFF -> onQuarantine(pkg)
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Group(title = DiagnosisCopy.heading.pick(lang)) {
            items.forEachIndexed { i, item ->
                if (i > 0) GroupDivider()
                ListRow(
                    DiagnosisCopy.title(item.cause).pick(lang), subtitle = DiagnosisCopy.body(item).pick(lang), subtitleMaxLines = 8,
                    leading = { StatusDot(Film.warn) },
                )
            }
            if (history) {
                if (items.isNotEmpty()) GroupDivider()
                ListRow(NoScoreHelp.historyTitle.pick(lang), subtitle = NoScoreHelp.historyBody.pick(lang), subtitleMaxLines = 8, leading = { StatusDot(Film.boneDim) })
            }
        }
        val primary = VisibilityDiagnosis.primaryAction(items)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (primary != Action.NONE) {
                PrimaryButton(DiagnosisCopy.action(primary)!!.pick(lang), { run(primary) })
                if (primary == Action.CHECK_AGAIN_WITH_RESTART || primary == Action.CHECK_AGAIN) {
                    Text(
                        DiagnosisCopy.restartNote.pick(lang), style = MaterialTheme.typography.bodySmall, color = Film.boneDim, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp, start = 8.dp, end = 8.dp),
                    )
                }
            }
            NoScoreHelp.otherActions(items).forEach { a -> TextAction(DiagnosisCopy.action(a)!!.pick(lang), { run(a) }) }
            if (resetOk) TextAction(DiagnosisCopy.reset.pick(lang), { confirmReset = true })
        }
        if (!pf.shizukuGranted) Notice(NoScoreHelp.noShizuku.pick(lang), kind = NoticeKind.INFO, action = NoScoreHelp.openAppInfo.pick(lang) to { DeviceChecks.openAppInfo(ctx, pkg) })
    }

    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text(DiagnosisCopy.resetConfirmTitle(b.meta.targetLabel).pick(lang)) },
        text = { Text(DiagnosisCopy.resetConfirmBody(b.meta.targetLabel).pick(lang)) },
        confirmButton = { TextButton(onClick = { confirmReset = false; vm.checkAgain(b, CheckAgain.Mode.RESET, onLive) }) { Text(DiagnosisCopy.resetConfirm.pick(lang)) } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(DiagnosisCopy.cancel.pick(lang)) } },
        containerColor = Film.raised,
    )
}

/** "Steps that needed you": which plans met a sign-in, code, form or Android permission request, and what was done. */
@Composable
internal fun BlockedStepsSection(b: AuditBundle, lang: GuideLang, modifier: Modifier = Modifier) {
    val lines = remember(b.meta.id) { BlockedSteps.lines(b.sessions) }
    if (lines.isEmpty()) return
    Group(modifier, title = BlockedSteps.heading.pick(lang)) {
        lines.forEachIndexed { i, line ->
            if (i > 0) GroupDivider()
            ListRow(line.title.pick(lang), subtitle = line.detail.pick(lang), subtitleMaxLines = 6)
        }
    }
}
