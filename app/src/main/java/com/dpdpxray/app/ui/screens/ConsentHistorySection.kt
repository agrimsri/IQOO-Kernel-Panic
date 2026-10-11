package com.dpdpxray.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.dpdpxray.app.audit.DiagnosisCopy
import com.dpdpxray.app.data.AuditBundle
import com.dpdpxray.app.data.ConsentAnswers
import com.dpdpxray.app.ui.guide.Bi
import com.dpdpxray.app.ui.guide.GuideLang
import com.dpdpxray.app.ui.system.Group
import com.dpdpxray.app.ui.system.GroupDivider
import com.dpdpxray.app.ui.system.ListRow
import com.dpdpxray.app.ui.system.Notice
import com.dpdpxray.app.ui.system.NoticeKind
import com.dpdpxray.app.ui.system.StatusDot
import com.dpdpxray.app.ui.system.TextAction
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.core.model.ConsentHistory
import com.dpdpxray.core.model.HistoryBounds

/**
 * Words for the result's consent-history part, in both guidance languages. The everyday question uses plainer words;
 * the developer one is the exact question. Every answer is labelled as the person's: TraceVeil cannot check it.
 */
object ConsentHistoryCopy {
    val heading = Bi("Your consent history", "आपकी सहमति का इतिहास")

    fun question(app: String, everyday: Boolean) = if (everyday) Bi(
        "Did you ever say yes to tracking or personalised ads in $app? For example in a popup or in its settings.",
        "क्या आपने कभी $app में ट्रैकिंग या आपके लिए चुने गए विज्ञापनों को 'हाँ' कहा था? जैसे किसी पॉपअप में या उसकी सेटिंग में।",
    ) else Bi(
        "Have you ever agreed to tracking or personalised ads in $app (for example in a popup or its settings)?",
        "क्या आपने कभी $app में ट्रैकिंग या पर्सनलाइज़्ड विज्ञापनों के लिए सहमति दी है (जैसे किसी पॉपअप में या उसकी सेटिंग में)?",
    )

    fun why(everyday: Boolean) = if (everyday) Bi(
        "This app remembered you and didn't ask in this check, so TraceVeil can't tell if you said yes before. Your answer is saved with this check as yours, and you can change it.",
        "इस ऐप ने आपको याद रखा और इस जाँच में नहीं पूछा, इसलिए TraceVeil नहीं जान सकता कि आपने पहले 'हाँ' कहा था या नहीं। आपका जवाब इस जाँच के साथ आपके जवाब के रूप में सहेजा जाता है, और आप इसे बदल सकते हैं।",
    ) else Bi(
        "The app kept its data and showed no consent screen in this check, so whether you agreed earlier can't be seen. Your answer picks one end of the range; it is saved with this check, marked as yours, and you can change it.",
        "ऐप ने अपना डेटा रखा और इस जाँच में सहमति स्क्रीन नहीं दिखाई, इसलिए यह नहीं दिखता कि आपने पहले सहमति दी थी या नहीं। आपका जवाब दायरे का एक सिरा चुनता है; यह इस जाँच के साथ आपके जवाब के रूप में सहेजा जाता है, और आप इसे बदल सकते हैं।",
    )

    /** Why there is a range at all, in one sentence. */
    val limit = Bi(
        "The app kept its data and showed no consent popup in this check, so TraceVeil can't see whether you agreed earlier.",
        "ऐप ने अपना डेटा रखा और इस जाँच में सहमति पॉपअप नहीं दिखाया, इसलिए TraceVeil नहीं देख सकता कि आपने पहले सहमति दी थी या नहीं।",
    )

    val never = Bi("No, never", "नहीं, कभी नहीं")
    val agreed = Bi("Yes, I agreed", "हाँ, मैंने सहमति दी थी")
    val notSure = Bi("Not sure", "पता नहीं")
    val change = Bi("Change answer", "जवाब बदलें")
    val basedOnAnswer = Bi("Based on your answer about consent history", "सहमति के इतिहास के बारे में आपके जवाब के आधार पर")
    val clear = Bi("Clear my answer", "मेरा जवाब हटाएँ")

    private fun hi(b: com.dpdpxray.core.model.ScoreBand) = HistoryBounds.bandHi(b)
    private fun cap(b: HistoryBounds) = b.bestBand != b.highBand

    fun neverDetail(b: HistoryBounds) = Bi(
        "Score ${b.lowScore} · ${b.lowBand.label}: its tracker lookups count as made without your consent",
        "स्कोर ${b.lowScore} · ${hi(b.lowBand)}: इसके ट्रैकर लुकअप आपकी सहमति के बिना किए गए माने जाएँगे",
    )

    fun agreedDetail(b: HistoryBounds) = Bi(
        "Score ${b.highScore} · ${b.bestBand.label}: checked as after you agreed" + if (cap(b)) " (never Ready on an answer alone)" else "",
        "स्कोर ${b.highScore} · ${hi(b.bestBand)}: आपकी सहमति के बाद की तरह जाँचा जाएगा" + if (cap(b)) " (सिर्फ़ जवाब के आधार पर कभी 'तैयार' नहीं)" else "",
    )

    fun notSureDetail(b: HistoryBounds) = Bi("Keeps the range ${b.range}", "दायरा ${b.range} ही रहेगा")

    /** Both answers give the same band: said once, no question. */
    fun settled(b: HistoryBounds) = Bi(
        "${b.lowBand.label} whichever way you answer: ${b.lowScore} if you never agreed to tracking, ${b.highScore} if you agreed earlier" +
            (if (cap(b)) " (never Ready on an answer alone)." else "."),
        "आप जो भी जवाब दें, नतीजा ${hi(b.lowBand)} रहेगा: अगर आपने कभी ट्रैकिंग की सहमति नहीं दी तो ${b.lowScore}, अगर पहले दी थी तो ${b.highScore}" +
            (if (cap(b)) " (सिर्फ़ जवाब के आधार पर कभी 'तैयार' नहीं)।" else "।"),
    )

    /** The result's one-line verdict for a range not yet settled by an answer. */
    fun headline(b: HistoryBounds) = if (b.bandSettled) Bi(
        "${b.lowBand.label} whichever way you answer: ${b.lowScore} if you never agreed to tracking, ${b.highScore} if you agreed earlier.",
        "आप जो भी जवाब दें, ${hi(b.lowBand)}: अगर आपने कभी ट्रैकिंग की सहमति नहीं दी तो ${b.lowScore}, अगर पहले दी थी तो ${b.highScore}।",
    ) else Bi(
        "At best ${b.bestBand.label} (${b.range}): one answer about your consent history decides it, just below.",
        "ज़्यादा से ज़्यादा ${hi(b.bestBand)} (${b.range}): आपकी सहमति के इतिहास के बारे में एक जवाब इसे तय करता है, ठीक नीचे।",
    )

    /** The answered result, always labelled as the person's answer, with what the other answer would give. */
    fun answered(b: HistoryBounds, app: String) = if (b.answer == ConsentHistory.DECLARED_NEVER) Bi(
        "Based on your answer: you never agreed to tracking in $app. Findings marked \"Based on your answer\" rest on it; TraceVeil can't check it. " +
            "If you had agreed earlier: ${b.highScore}.",
        "आपके जवाब के आधार पर: आपने $app में कभी ट्रैकिंग की सहमति नहीं दी। \"आपके जवाब के आधार पर\" वाली फ़ाइंडिंग इसी पर टिकी हैं; TraceVeil इसे जाँच नहीं सकता। " +
            "अगर आपने पहले सहमति दी होती: ${b.highScore}।",
    ) else Bi(
        "Checked as after you agreed: you said you agreed to tracking in $app earlier, so its tracker lookups aren't counted as before consent. " +
            "On your answer alone it is never Ready. If you never agreed: ${b.lowScore}.",
        "आपकी सहमति के बाद की तरह जाँचा गया: आपने कहा कि आपने पहले $app में ट्रैकिंग की सहमति दी थी, इसलिए इसके ट्रैकर लुकअप सहमति से पहले के नहीं गिने गए। " +
            "सिर्फ़ आपके जवाब पर यह कभी 'तैयार' नहीं होता। अगर आपने कभी सहमति नहीं दी: ${b.lowScore}।",
    )

    fun notSureLine(b: HistoryBounds, resetOk: Boolean) = Bi(
        "You're not sure, so the result stays a range: ${b.range}. Only a fresh start shows the app's first-launch consent." +
            if (resetOk) " Check again with a reset (your own test app only)." else "",
        "आपको पक्का नहीं पता, इसलिए नतीजा एक दायरा ही रहता है: ${b.range}। ऐप की पहली बार वाली सहमति सिर्फ़ नई शुरुआत पर दिखती है।" +
            if (resetOk) " रीसेट करके फिर से जाँचें (सिर्फ़ आपका अपना टेस्ट ऐप)।" else "",
    )

    fun all(open: HistoryBounds, settledBounds: HistoryBounds): List<Bi> = listOf(
        heading, limit, basedOnAnswer, question("App", true), question("App", false), why(true), why(false), never, agreed, notSure, change, clear,
        neverDetail(open), agreedDetail(open), agreedDetail(settledBounds), notSureDetail(open), settled(settledBounds), headline(open),
        headline(settledBounds), answered(open.copy(answer = ConsentHistory.DECLARED_NEVER), "App"),
        answered(open.copy(answer = ConsentHistory.DECLARED_AGREED), "App"), notSureLine(open, true), notSureLine(open, false),
    )
}

/**
 * The result's consent-history part, for a kept-data check that showed no consent screen. When the two possible answers
 * give the same band it only says so; when they differ it asks the one question (No, never / Yes, I agreed / Not
 * sure). An answer re-scores the check and stays labelled as the person's; it can be changed or cleared. Nothing here
 * is ever set without a tap.
 */
@Composable
internal fun ConsentHistorySection(
    b: AuditBundle,
    lang: GuideLang,
    everyday: Boolean,
    resetOk: Boolean,
    onAnswer: (ConsentHistory?) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = ConsentAnswers.state(b)
    val bounds = b.result.historyBounds ?: return
    if (state == ConsentAnswers.State.NONE) return
    val c = ConsentHistoryCopy
    val app = b.meta.targetLabel
    var editing by rememberSaveable(b.meta.id, bounds.answer) { mutableStateOf(false) }
    var confirmReset by rememberSaveable(b.meta.id) { mutableStateOf(false) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state) {
            ConsentAnswers.State.SETTLED -> Group(title = c.heading.pick(lang)) {
                ListRow(c.settled(bounds).pick(lang), subtitle = c.limit.pick(lang), subtitleMaxLines = 4,
                    leading = { StatusDot(Film.boneDim) })
            }
            ConsentAnswers.State.ANSWERED -> Notice(
                c.answered(bounds, app).pick(lang), kind = NoticeKind.INFO,
                action = (if (editing) c.clear else c.change).pick(lang) to { if (editing) onAnswer(null) else editing = true },
            )
            ConsentAnswers.State.NOT_SURE -> Group(title = c.heading.pick(lang)) {
                ListRow(c.notSure.pick(lang), subtitle = c.notSureLine(bounds, resetOk).pick(lang), subtitleMaxLines = 5,
                    leading = { StatusDot(Film.boneDim) })
                if (!editing) {
                    GroupDivider()
                    ListRow(c.change.pick(lang), onClick = { editing = true })
                }
            }
            ConsentAnswers.State.ASK, ConsentAnswers.State.NONE -> Unit
        }
        if (state == ConsentAnswers.State.ASK || editing) Question(bounds, app, lang, everyday, onAnswer)
        if (state == ConsentAnswers.State.NOT_SURE && resetOk) TextAction(DiagnosisCopy.reset.pick(lang), { confirmReset = true })
    }

    // A reset clears the app's data, so it is offered only for an app the person develops and always confirmed first.
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text(DiagnosisCopy.resetConfirmTitle(app).pick(lang)) },
        text = { Text(DiagnosisCopy.resetConfirmBody(app).pick(lang)) },
        confirmButton = { TextButton(onClick = { confirmReset = false; onReset() }) { Text(DiagnosisCopy.resetConfirm.pick(lang)) } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(DiagnosisCopy.cancel.pick(lang)) } },
        containerColor = Film.raised,
    )
}

@Composable
private fun Question(bounds: HistoryBounds, app: String, lang: GuideLang, everyday: Boolean, onAnswer: (ConsentHistory?) -> Unit) {
    val c = ConsentHistoryCopy
    Group(title = c.heading.pick(lang)) {
        ListRow(c.question(app, everyday).pick(lang), subtitle = c.why(everyday).pick(lang), subtitleMaxLines = 6)
        listOf(
            Triple(c.never, c.neverDetail(bounds), ConsentHistory.DECLARED_NEVER),
            Triple(c.agreed, c.agreedDetail(bounds), ConsentHistory.DECLARED_AGREED),
            Triple(c.notSure, c.notSureDetail(bounds), ConsentHistory.NOT_SURE),
        ).forEach { (label, detail, answer) ->
            GroupDivider()
            ListRow(
                label.pick(lang), subtitle = detail.pick(lang), subtitleMaxLines = 3,
                modifier = Modifier.semantics { role = Role.RadioButton },
                leading = { StatusDot(if (bounds.answer == answer) Film.scan else Film.line, size = 12.dp) },
                onClick = { onAnswer(answer) }, chevron = false,
            )
        }
    }
}
