package com.dpdpxray.app.trust

import android.content.Context
import android.net.VpnService
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.dpdpxray.app.ui.components.ChipFlow
import com.dpdpxray.app.ui.components.chevronTurn
import com.dpdpxray.app.ui.components.foldSpec
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.guide.GlyphIcon
import com.dpdpxray.app.ui.guide.GuideLang
import com.dpdpxray.app.ui.guide.LinkAction
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.Radii
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the card reads from this install; null until the first read finishes. */
private data class HomeTrustLive(val facts: List<TrustFact>, val permissions: PermissionSummary)

/**
 * "Why you can trust TraceVeil" on Home (both modes): a flat card with four short, code-checked facts (HomeTrust)
 * and a live line of TraceVeil's own permissions ("2 of 4 permissions on · see why") that opens the trust screen. Folded,
 * the facts are small chips; open, each says what it means. Open on the card's first view, folded after that.
 */
@Composable
fun HomeTrustCard(lang: GuideLang, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }
    val live by produceState<HomeTrustLive?>(null, resumes) {
        value = withContext(Dispatchers.IO) { readLive(ctx) }
    }
    val firstView = remember { HomeTrustPrefs.firstView(ctx) }
    var open by rememberSaveable { mutableStateOf(firstView) }
    val shape = RoundedCornerShape(Radii.card)
    // No fact is shown before this install has been read, so an exception (online voice, backup) is never hidden.
    val current = live

    // Same flat surface as every other group: no border, no wash; status colour stays on the small check marks.
    Column(modifier.fillMaxWidth().clip(shape).background(Film.raised).animateContentSize(foldSpec())) {
        val turn = chevronTurn(open)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .clickable(role = Role.Button, onClickLabel = if (open) "Hide details" else "Show details") { open = !open }
                .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphIcon(Glyph.LOCK, Film.boneDim, size = 24.dp)
            Spacer(Modifier.width(14.dp))
            Text(
                HomeTrust.title.pick(lang), style = MaterialTheme.typography.titleMedium, color = Film.bone,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            GlyphIcon(Glyph.CHEVRON_DOWN, Film.boneDim, Modifier.graphicsLayer { rotationZ = turn }, size = 20.dp)
        }
        // The live permission line opens the full trust page, where each permission's purpose is listed.
        val line = current?.permissions?.line?.pick(lang) ?: if (lang == GuideLang.HI) "अनुमतियाँ पढ़ी जा रही हैं…" else "Reading permissions…"
        LinkAction("$line · ${HomeTrust.seeWhy.pick(lang)}", onOpen, Modifier.padding(start = 8.dp), color = Film.scan, glyph = Glyph.SETTINGS)
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (current == null) return@Column
            if (!open) {
                ChipFlow { current.facts.forEach { FactChip(it, lang) } }
            } else {
                current.facts.forEach { FactRow(it, lang) }
                LinkAction(HomeTrust.open.pick(lang), onOpen, glyph = Glyph.LOCK)
            }
        }
    }
}

/** An open fact: a check (or warning) mark, the statement, and what it means. */
@Composable
private fun FactRow(f: TrustFact, lang: GuideLang) {
    val color = if (f.holds) Film.clear else Film.warn
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 2.dp).size(18.dp), contentAlignment = Alignment.Center) { GlyphIcon(if (f.holds) Glyph.CHECK else Glyph.WARN, color, size = 16.dp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(f.title.pick(lang), style = MaterialTheme.typography.titleSmall, color = if (f.holds) Film.bone else Film.warn)
            Text(f.detail.pick(lang), style = MaterialTheme.typography.bodyMedium, color = Film.boneDim)
        }
    }
}

/** A fact as a quiet pill: a check for a fact that holds, a warning mark for an exception on this install. */
@Composable
private fun FactChip(f: TrustFact, lang: GuideLang) {
    val color = if (f.holds) Film.clear else Film.warn
    Row(
        Modifier.clip(CircleShape).background(Film.fill).padding(start = 8.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) { GlyphIcon(if (f.holds) Glyph.CHECK else Glyph.WARN, color, size = 16.dp) }
        Spacer(Modifier.width(6.dp))
        Text(f.short.pick(lang), style = MaterialTheme.typography.labelMedium, color = Film.bone)
    }
}

/** Reads the facts' inputs and the permission states. Read-only: it never asks for anything or changes a setting. */
private fun readLive(ctx: Context): HomeTrustLive {
    val facts = HomeTrust.facts(TrustReader.onlineVoiceOn(ctx), TrustReader.backupAllowed(ctx))
    val vpnAllowed = runCatching { VpnService.prepare(ctx) == null }.getOrNull()
    val permissions = HomeTrust.permissionSummary(TrustReader.permissions(ctx), vpnAllowed, runCatching { TrustReader.accessibilityOn(ctx) }.getOrNull())
    return HomeTrustLive(facts, permissions)
}

/** The card opens on its first view only; afterwards it starts folded. Stored in TraceVeil's private preferences. */
private object HomeTrustPrefs {
    private const val FILE = "traceveil_home_trust"
    private const val SEEN = "seen"

    fun firstView(ctx: Context): Boolean {
        val prefs = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val seen = prefs.getBoolean(SEEN, false)
        if (!seen) prefs.edit().putBoolean(SEEN, true).apply()
        return !seen
    }
}
