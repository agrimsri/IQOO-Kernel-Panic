package com.dpdpxray.app.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.data.Audience
import com.dpdpxray.app.ui.components.BrandMark
import com.dpdpxray.app.ui.components.reveal
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.guide.GlyphIcon
import com.dpdpxray.app.ui.guide.GuideLang
import com.dpdpxray.app.ui.guide.LocalGuide
import com.dpdpxray.app.ui.modes.ModeGlyph
import com.dpdpxray.app.ui.modes.ModeGlyphIcon
import com.dpdpxray.app.ui.system.Group
import com.dpdpxray.app.ui.system.GroupDivider
import com.dpdpxray.app.ui.system.LangVoiceButton
import com.dpdpxray.app.ui.system.ListRow
import com.dpdpxray.app.ui.system.PrimaryButton
import com.dpdpxray.app.ui.system.TextAction
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.LocalReduceMotion
import com.dpdpxray.app.ui.theme.Motion
import kotlinx.coroutines.launch

/** The three pages that are shown, in order. The other pages in [OnboardingCopy] are kept for the copy tests. */
private val SHOWN = listOf("promise", "needs", "audience")

/**
 * First-run onboarding in three calm pages (UI_UX_REFACTOR_GUIDE.md 7.1): the promise, what TraceVeil needs and why,
 * and how you will use it. Language and read-aloud live in one menu at the top, not on every page. Permission reasons
 * and the privacy line stay on the page; they are not folded away.
 *
 * [audience] is the mode already chosen (a replay shows it); on a first run "I use apps" is preselected. Both Skip and
 * "Go to Home" save the selected mode. [onSample] is the "See a sample" link on the last page. [onDemo] and [onApk]
 * are kept so callers compile; those first steps now live on Home.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    onSample: () -> Unit,
    onDemo: () -> Unit,
    onApk: (() -> Unit)?,
    audience: Audience? = null,
    onAudience: (Audience) -> Unit = {},
) {
    val guide = LocalGuide.current
    val lang = guide?.lang ?: GuideLang.EN
    var picked by rememberSaveable { mutableStateOf(audience ?: Audience.USER) }
    val all = OnboardingCopy.pagesFor(picked)
    val pages = SHOWN.map { key -> all.first { it.key == key } }
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val page = pager.currentPage
    val last = page == pages.lastIndex
    val finish = { onAudience(picked); onDone() }

    BackHandler(enabled = page > 0) { scope.launch { pager.animateScrollToPage(page - 1) } }

    val speaker = guide?.speaker
    val spokenOn = guide?.spoken == true
    if (speaker != null) {
        val key = "onboarding.${pages[page].key}"
        val text = pages[page].spoken.pick(lang)
        LaunchedEffect(key, spokenOn, lang) { if (spokenOn) speaker.say(key, text, lang) }
        DisposableEffect(key, speaker) { onDispose { speaker.stop(key) } }
    }

    Column(Modifier.fillMaxSize().background(Film.base)) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ProgressDots(page, pages.size, Modifier.weight(1f))
            LangVoiceButton()
            TextAction(OnboardingCopy.skip.pick(lang), finish, color = Film.boneDim)
        }

        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { i ->
            val p = pages[i]
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                Spacer(Modifier.height(if (p.key == "promise") 72.dp else 24.dp))
                if (p.key == "promise") {
                    BrandMark(size = 72.dp)
                    Spacer(Modifier.height(28.dp))
                }
                Text(
                    p.title.pick(lang), style = MaterialTheme.typography.headlineMedium, color = Film.bone,
                    modifier = Modifier.reveal(0).semantics { heading() },
                )
                Text(p.body.pick(lang), style = MaterialTheme.typography.bodyLarge, color = Film.boneDim, modifier = Modifier.padding(top = 12.dp).reveal(1))
                when (p.key) {
                    "needs" -> Needs(lang, OnboardingCopy.needsFor(picked))
                    "audience" -> {
                        AudienceChoices(lang, picked) { a -> picked = a }
                        TextAction(OnboardingCopy.sample.title.pick(lang), { onAudience(picked); onSample() }, Modifier.padding(top = 8.dp))
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (page > 0) TextAction(OnboardingCopy.back.pick(lang), { scope.launch { pager.animateScrollToPage(page - 1) } }, color = Film.boneDim)
            Spacer(Modifier.weight(1f))
            PrimaryButton(
                (if (last) OnboardingCopy.done else OnboardingCopy.next).pick(lang),
                { if (last) finish() else scope.launch { pager.animateScrollToPage(page + 1) } },
                Modifier.width(184.dp),
            )
        }
    }
}

/** Page dots: the current one is a short accent bar, the others small neutral dots. */
@Composable
private fun ProgressDots(current: Int, total: Int, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    Row(modifier.semantics { contentDescription = "Page ${current + 1} of $total" }, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        (0 until total).forEach { i ->
            val w by animateDpAsState(if (i == current) 22.dp else 6.dp, if (reduce) snap() else tween(Motion.MEDIUM, easing = Motion.emphasized), label = "dot")
            val c by animateColorAsState(if (i == current) Film.scan else Film.fill, if (reduce) snap() else tween(Motion.SHORT), label = "dot-color")
            Box(Modifier.size(width = w, height = 6.dp).clip(CircleShape).background(c))
        }
    }
}

/** What we need and why: one grouped list, a short reason per permission, the privacy line as the footnote. */
@Composable
private fun Needs(lang: GuideLang, needs: List<OnboardingNeed>) {
    val glyphs = mapOf("vpn" to Glyph.VPN, "watcher" to Glyph.WATCHER, "shizuku" to Glyph.RESET)
    Column(Modifier.padding(top = 28.dp).reveal(2)) {
        Group(footer = OnboardingCopy.privacy.pick(lang)) {
            needs.forEachIndexed { i, need ->
                if (i > 0) GroupDivider(60.dp)
                ListRow(
                    need.title.pick(lang), subtitle = need.body.pick(lang), subtitleMaxLines = 5,
                    leading = { GlyphIcon(glyphs.getValue(need.key), Film.scan, size = 26.dp) },
                    trailing = { Text((if (need.required) OnboardingCopy.required else OnboardingCopy.optional).pick(lang), style = MaterialTheme.typography.labelMedium, color = Film.boneDim) },
                )
            }
        }
    }
}

/** The two ways to use TraceVeil as one grouped radio list. "I use apps" is preselected on a first run. */
@Composable
private fun AudienceChoices(lang: GuideLang, picked: Audience, onPick: (Audience) -> Unit) {
    Column(Modifier.padding(top = 28.dp).reveal(2)) {
        Group(Modifier.selectableGroup()) {
            OnboardingCopy.audiences.forEachIndexed { i, option ->
                if (i > 0) GroupDivider(60.dp)
                val selected = option.audience == picked
                ListRow(
                    option.title.pick(lang),
                    subtitle = option.tagline.pick(lang) + ". " + option.detail.pick(lang), subtitleMaxLines = 4,
                    modifier = Modifier.selectable(selected = selected, role = Role.RadioButton) { onPick(option.audience) },
                    leading = { ModeGlyphIcon(if (option.audience == Audience.USER) ModeGlyph.PHONE else ModeGlyph.CODE, Film.scan, size = 28.dp) },
                    trailing = { if (selected) GlyphIcon(Glyph.CHECK, Film.scan, size = 22.dp) },
                )
            }
        }
    }
}
