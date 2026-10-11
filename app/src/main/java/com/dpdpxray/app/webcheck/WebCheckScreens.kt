package com.dpdpxray.app.webcheck

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.graph
import com.dpdpxray.app.ui.components.RecordingDot
import com.dpdpxray.app.ui.components.SwitchRow
import com.dpdpxray.app.ui.components.TopBar
import com.dpdpxray.app.ui.components.cardSurface
import com.dpdpxray.app.ui.guide.GuideLang
import com.dpdpxray.app.ui.guide.guideLang
import com.dpdpxray.app.ui.system.Group
import com.dpdpxray.app.ui.system.Notice
import com.dpdpxray.app.ui.system.NoticeKind
import com.dpdpxray.app.ui.system.PrimaryButton
import com.dpdpxray.app.ui.system.SecondaryButton
import com.dpdpxray.app.ui.system.SegmentedControl
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.Radii
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.web.RawWebRequest
import com.dpdpxray.core.web.WebUrl
import com.dpdpxray.core.web.WebUrlCheck

/** The Check tab's third view: a website address, one option, the disclosure, and the start button. */
@Composable
fun WebCheckScreen(
    onBack: () -> Unit,
    onStarted: () -> Unit,
    /** Shows the Live check / APK Guard / Website switch; null hides it. */
    onSwitchToLive: (() -> Unit)? = null,
    onSwitchToApk: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val lang = guideLang()
    var input by rememberSaveable { mutableStateOf("") }
    var agentTaps by rememberSaveable { mutableStateOf(true) }
    var problem by remember { mutableStateOf<String?>(null) }
    val parsed = remember(input) { if (input.isBlank()) null else WebUrl.check(input) }
    val start = {
        when (val c = WebUrl.check(input)) {
            is WebUrlCheck.Invalid -> problem = c.reason
            is WebUrlCheck.Valid -> {
                val why = WebCheckRunner.start(ctx, WebCheckConfig(c.url, c.host, agentTaps))
                if (why == null) { problem = null; onStarted() } else problem = why.pick(lang)
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(WebCheckCopy.title.pick(lang), onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (onSwitchToLive != null || onSwitchToApk != null) {
                SegmentedControl(
                    listOf("Live check", "APK Guard", WebCheckCopy.segment.pick(lang)), 2,
                    { when (it) { 0 -> onSwitchToLive?.invoke(); 1 -> onSwitchToApk?.invoke() } },
                    Modifier.padding(top = 8.dp),
                )
            }
            Text(WebCheckCopy.lead.pick(lang), style = MaterialTheme.typography.bodyLarge, color = Film.boneDim)
            OutlinedTextField(
                value = input, onValueChange = { input = it.take(WebUrl.MAX_LENGTH); problem = null }, singleLine = true,
                label = { Text(WebCheckCopy.fieldLabel.pick(lang)) },
                placeholder = { Text(WebCheckCopy.fieldHint.pick(lang), color = Film.boneDim) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = { start() }),
                isError = problem != null,
                shape = RoundedCornerShape(Radii.small),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Film.scan, unfocusedBorderColor = Film.line, cursorColor = Film.scan,
                    focusedContainerColor = Film.fill, unfocusedContainerColor = Film.fill, focusedTextColor = Film.bone, unfocusedTextColor = Film.bone,
                ),
                textStyle = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth(),
            )
            problem?.let { Notice(it, kind = NoticeKind.CAUTION) }
            if ((parsed as? WebUrlCheck.Valid)?.upgradedFromHttp == true) Notice(WebCheckCopy.upgraded.pick(lang), kind = NoticeKind.INFO)
            Group {
                SwitchRow(WebCheckCopy.tapsTitle.pick(lang), WebCheckCopy.tapsBody.pick(lang), agentTaps, { agentTaps = it }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            Text(WebCheckCopy.plansLine.pick(lang), style = MaterialTheme.typography.bodyMedium, color = Film.boneDim)
            // The new network path, said on screen before anything opens.
            Notice(WebCheckCopy.disclosure.pick(lang) + " " + WebCheckCopy.addressesNote.pick(lang), kind = NoticeKind.INFO)
            PrimaryButton(WebCheckCopy.start.pick(lang), start, enabled = input.isNotBlank())
            Text(WebCheckCopy.limits.pick(lang), style = MaterialTheme.typography.bodySmall, color = Film.boneDim)
        }
    }
}

/**
 * The running website check: the site in TraceVeil's browser, the run and step, and three counters. Leaving the screen
 * asks first and stops the check, because the page has to be on screen to be read.
 */
@Composable
fun WebCheckLiveScreen(onDone: (String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val lang = guideLang()
    val state by WebCheckRunner.state.collectAsState()
    val browser by WebCheckRunner.browser.collectAsState()
    var askStop by remember { mutableStateOf(false) }
    // Set once the result opens, so the idle state that follows never navigates back over the result.
    var finished by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        when (val s = state) {
            is WebCheckState.Done -> { finished = true; WebCheckRunner.acknowledge(); onDone(s.auditId) }
            is WebCheckState.Idle -> if (!finished) onBack()
            else -> {}
        }
    }
    BackHandler(enabled = state is WebCheckState.Running) { askStop = true }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        when (val s = state) {
            is WebCheckState.Failed -> {
                Text(WebCheckCopy.stopped.pick(lang), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 32.dp).semantics { heading() })
                Notice(s.message.pick(lang), Modifier.padding(vertical = 12.dp), kind = NoticeKind.CAUTION)
                PrimaryButton(WebCheckCopy.backToCheck.pick(lang), { WebCheckRunner.acknowledge() })
            }
            is WebCheckState.Running -> {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    RecordingDot(Film.leak, Modifier.semantics { contentDescription = "Recording" })
                    Spacer(Modifier.width(6.dp))
                    Text(s.config.host, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).semantics { heading() })
                }
                Text(WebCheckCopy.live.pick(lang), style = MaterialTheme.typography.bodySmall, color = Film.boneDim, modifier = Modifier.padding(top = 2.dp))
                Runs(s.planIndex, s.config.plans.size, lang, Modifier.padding(top = 10.dp))
                Text(s.step.pick(lang), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
                val seenFlow = remember(browser) { browser?.seen ?: kotlinx.coroutines.flow.MutableStateFlow<List<RawWebRequest>>(emptyList()) }
                val seen by seenFlow.collectAsState()
                val catalog = remember { ctx.graph.catalog() }
                val counts = remember(seen.size, s.choiceNanos) { WebCheckView.counts(seen, catalog, s.choiceNanos) }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Counter(counts.requests, WebCheckCopy.requests.pick(lang), Film.bone, Modifier.weight(1f))
                    Counter(counts.toTrackers, WebCheckCopy.toTrackers.pick(lang), if (counts.toTrackers == 0) Film.bone else Film.high, Modifier.weight(1f))
                    Counter(counts.beforeChoice, WebCheckCopy.beforeChoice.pick(lang), if (counts.beforeChoice > 0) Film.leak else Film.bone, Modifier.weight(1f))
                }
                Text(WebCheckCopy.caveat.pick(lang), style = MaterialTheme.typography.bodySmall, color = Film.boneDim, modifier = Modifier.padding(top = 6.dp))
                s.choiceKind?.let { k ->
                    Text(
                        if (k == ConsentKind.REJECT) "Refused · Reject" else "Accepted · Accept",
                        style = MaterialTheme.typography.labelLarge, color = if (k == ConsentKind.REJECT) Film.warn else Film.scan, modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(Radii.card)).background(Film.raised)) {
                    browser?.let { WebCheckBrowserView(it, Modifier.fillMaxSize()) }
                }
                SecondaryButton(WebCheckCopy.stop.pick(lang), { askStop = true }, Modifier.fillMaxWidth().padding(vertical = 12.dp))
            }
            else -> Spacer(Modifier.height(1.dp))
        }
    }
    if (askStop) AlertDialog(
        onDismissRequest = { askStop = false },
        title = { Text(WebCheckCopy.stopTitle.pick(lang)) },
        text = { Text(WebCheckCopy.stopBody.pick(lang)) },
        confirmButton = { TextButton(onClick = { askStop = false; WebCheckRunner.cancel() }) { Text(WebCheckCopy.stop.pick(lang), color = Film.leak) } },
        dismissButton = { TextButton(onClick = { askStop = false }) { Text(WebCheckCopy.keepChecking.pick(lang)) } },
        containerColor = Film.raised,
    )
}

/** The three runs as a quiet row: done, now, next. */
@Composable
private fun Runs(current: Int, total: Int, lang: GuideLang, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().semantics { contentDescription = "Run ${current + 1} of $total" }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WebCheckView.planNames.take(total).forEachIndexed { i, name ->
            val now = i == current
            Box(
                Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(12.dp)).background(if (now) Film.fill else Film.raised.copy(alpha = 0f)).padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    (if (i < current) "✓ " else "${i + 1} ") + name.pick(lang),
                    style = MaterialTheme.typography.labelLarge, color = if (now) Film.bone else Film.boneDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Counter(value: Int, label: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxHeight().cardSurface(14.dp).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text("$value", style = MaterialTheme.typography.headlineSmall, color = color)
        Text(label, style = MaterialTheme.typography.labelMedium, color = Film.boneDim)
    }
}
