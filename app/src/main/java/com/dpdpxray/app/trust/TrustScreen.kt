package com.dpdpxray.app.trust

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.dpdpxray.app.capture.DnsForwarder
import com.dpdpxray.app.control.DeviceChecks
import com.dpdpxray.app.report.ExportManager
import com.dpdpxray.app.ui.components.BreathingDot
import com.dpdpxray.app.ui.components.Callout
import com.dpdpxray.app.ui.components.ChipFlow
import com.dpdpxray.app.ui.components.CollapsibleCard
import com.dpdpxray.app.ui.components.EmptyHint
import com.dpdpxray.app.ui.components.Hairline
import com.dpdpxray.app.ui.components.IconBadge
import com.dpdpxray.app.ui.components.LearnMore
import com.dpdpxray.app.ui.components.Note
import com.dpdpxray.app.ui.components.SectionCard
import com.dpdpxray.app.ui.components.SkeletonBar
import com.dpdpxray.app.ui.components.SkeletonCard
import com.dpdpxray.app.ui.components.Tag
import com.dpdpxray.app.ui.components.TopBar
import com.dpdpxray.app.ui.components.cardSurface
import com.dpdpxray.app.ui.components.reveal
import com.dpdpxray.app.ui.components.shimmer
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.guide.GlyphIcon
import com.dpdpxray.app.ui.guide.GradientAction
import com.dpdpxray.app.ui.guide.LinkAction
import com.dpdpxray.app.ui.guide.OutlineAction
import com.dpdpxray.app.ui.theme.Brand
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.FilmExtra
import com.dpdpxray.app.ui.theme.Mono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Why you can trust TraceVeil": what stays on the phone, the permissions it holds (read live), a self-check with its
 * own APK Guard, its own network use (Android's counters), the identity to compare, and how findings are made. Every
 * sentence comes from [TrustText], whose tests tie it to the code; every value is read from this install.
 *
 * Layout: a hero with the self-check, then the two cards a reader checks first (what stays on the phone, the live
 * network counters). Permissions, identity and method fold behind cards whose summaries show the live numbers.
 */
@Composable
fun TrustScreen(onBack: () -> Unit, onSelfCheck: () -> Unit) {
    val ctx = LocalContext.current
    // Re-read on return from Android settings, so a changed permission or switch shows at once.
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }
    val live by produceState<LiveFacts?>(null, resumes) {
        value = withContext(Dispatchers.IO) {
            LiveFacts(
                permissions = TrustText.permissionLines(TrustReader.permissions(ctx), ctx.packageName),
                onlineVoice = TrustReader.onlineVoiceOn(ctx),
                backup = TrustReader.backupAllowed(ctx),
                accessibility = TrustReader.accessibilityOn(ctx),
                vpnFor = TrustReader.vpnRoutingNow(),
                identity = TrustReader.identity(ctx),
            )
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(TrustText.TITLE, onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TrustHero(onSelfCheck, Modifier.reveal(0))
            val f = live
            if (f == null) {
                SkeletonCard(label = "Reading this install…")
                SkeletonCard()
                SkeletonCard()
                return@Column
            }
            StaysOnPhoneCard(f, Modifier.reveal(1))
            NetworkCard(f.onlineVoice, Modifier.reveal(2))
            PermissionsCard(f, Modifier.reveal(3))
            IdentityCard(f.identity, Modifier.reveal(4))
            CollapsibleCard(
                "How findings are made", Modifier.reveal(5),
                summary = TrustText.findings.joinToString(" · ") { it.title }, glyph = Glyph.BOOK,
            ) {
                ClaimList(TrustText.findings)
            }
        }
    }
}

private data class LiveFacts(
    val permissions: List<PermissionLine>,
    val onlineVoice: Boolean,
    val backup: Boolean?,
    val accessibility: Boolean,
    val vpnFor: String?,
    val identity: OwnIdentity,
)

/**
 * The hero: one line on how to read the screen, three chips that restate tested claims, and the self-check as the
 * main action. What the self-check reads folds below it.
 */
@Composable
private fun TrustHero(onSelfCheck: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().cardSurface().background(Brand.heroWash).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Glyph.LOCK, Film.clear, size = 48.dp)
            Spacer(Modifier.width(14.dp))
            Text("Check TraceVeil yourself", style = MaterialTheme.typography.titleLarge, color = Film.bone, modifier = Modifier.weight(1f).semantics { heading() })
        }
        Spacer(Modifier.height(10.dp))
        // The heading already says "check it yourself"; the line under it says how.
        Note("Each value below is read from this phone now, and each sentence matches TraceVeil's code.")
        Spacer(Modifier.height(12.dp))
        ChipFlow {
            Tag("No account", Film.clear)
            Tag("No analytics or ad libraries", Film.clear)
            Tag("AI runs on this phone", Film.scan)
        }
        Spacer(Modifier.height(16.dp))
        GradientAction("Open APK Guard for TraceVeil", onSelfCheck, glyph = Glyph.APK)
        LearnMore(Modifier.padding(top = 8.dp), label = "What the self-check reads") {
            Note("APK Guard reads what Android shows about TraceVeil itself: its permissions, special services, installer and signer, with the same rules it uses for any app.")
        }
    }
}

/** Short lines always visible, each with a small marker; every detail behind one "Learn more" at the end. */
@Composable
private fun ClaimList(claims: List<Claim>, accent: Color = Film.scan) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        claims.forEach { c ->
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 7.dp).size(7.dp).clip(CircleShape).background(accent))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.title, style = MaterialTheme.typography.titleSmall, color = Film.bone)
                    Text(c.line, style = MaterialTheme.typography.bodyMedium, color = Film.boneDim)
                }
            }
        }
    }
    val details = claims.mapNotNull { c -> c.detail?.let { c.title to it } }
    if (details.isNotEmpty()) LearnMore(Modifier.padding(top = 4.dp)) {
        details.forEach { (title, detail) -> Note("$title: $detail") }
    }
}

@Composable
private fun StaysOnPhoneCard(f: LiveFacts, modifier: Modifier = Modifier) {
    SectionCard(modifier, title = "What stays on this phone", glyph = Glyph.LOCK, accent = Film.clear) {
        // The online-voice disclosure is the one thing that leaves the phone, so it stays in view when it is on.
        if (f.onlineVoice) Callout("Online voice is on for this phone, so spoken answers' text is sent to Sarvam AI.", Modifier.padding(bottom = 12.dp), color = Film.high)
        ClaimList(TrustText.staysOnPhone(f.onlineVoice, f.backup), accent = Film.clear)
    }
}

@Composable
private fun PermissionsCard(f: LiveFacts, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val granted = f.permissions.count { it.status == "Granted" }
    val summary = if (f.permissions.isEmpty()) "Android did not return the list" else
        "${f.permissions.size} requested · $granted granted · VPN ${if (f.vpnFor != null) "running" else "not running"} · watcher ${if (f.accessibility) "on" else "off"}"
    CollapsibleCard("Permissions TraceVeil holds", modifier, summary = summary, glyph = Glyph.SETTINGS) {
        Note("Read from Android just now: everything TraceVeil requests, including what its libraries add.")
        f.permissions.forEach { p ->
            Hairline(Modifier.padding(vertical = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.title, style = MaterialTheme.typography.titleSmall, color = Film.bone, modifier = Modifier.weight(1f))
                Tag(p.status, if (p.status == "Granted") Film.clear else Film.boneDim)
            }
            Text(p.purpose, style = MaterialTheme.typography.bodyMedium, color = if (p.described) Film.bone else Film.warn)
            Text(p.name, fontFamily = Mono, fontSize = 11.sp, color = Film.boneDim)
        }
        if (f.permissions.isEmpty()) EmptyHint("Android did not return TraceVeil's permission list.", glyph = Glyph.WARN)
        Text("Special access", style = MaterialTheme.typography.titleMedium, color = Film.bone, modifier = Modifier.padding(top = 12.dp).semantics { heading() })
        val access = listOf(
            TrustText.vpnAccess to (f.vpnFor?.let { "Running now for $it" } ?: "Not running now"),
            TrustText.accessibilityAccess to (if (f.accessibility) "On" else "Off"),
        )
        access.forEach { (c, state) ->
            Hairline(Modifier.padding(vertical = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.title, style = MaterialTheme.typography.titleSmall, color = Film.bone, modifier = Modifier.weight(1f))
                Tag(state, Film.boneDim)
            }
            Text(c.line, style = MaterialTheme.typography.bodyMedium, color = Film.bone)
        }
        LearnMore(Modifier.padding(top = 4.dp)) {
            access.forEach { (c, _) -> c.detail?.let { Note("${c.title}: $it") } }
        }
        OutlineAction("Open Android's settings for TraceVeil", { DeviceChecks.openAppInfo(ctx, ctx.packageName) }, glyph = Glyph.SETTINGS)
    }
}

/**
 * Android's byte counters as two big numbers, the "nothing else uses the network" claim in view, and every network
 * path in TraceVeil's code behind one disclosure.
 */
@Composable
private fun NetworkCard(onlineVoice: Boolean, modifier: Modifier = Modifier) {
    var reads by remember { mutableIntStateOf(0) }
    val traffic by produceState<OwnTraffic?>(null, reads) { value = withContext(Dispatchers.IO) { TrustReader.traffic() } }
    SectionCard(modifier, title = "TraceVeil's own network use", glyph = Glyph.PRIVATE_DNS, glow = Film.scan) {
        val t = traffic
        Text(
            if (t == null) "Reading Android's counters…" else "Android's counters for TraceVeil, since the phone started ${TrustText.formatUptime(t.uptimeMillis)} ago:",
            style = MaterialTheme.typography.labelMedium, color = Film.boneDim,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CounterTile("Received", t?.let { TrustText.formatBytes(it.rxBytes) }, Modifier.weight(1f))
            CounterTile("Sent", t?.let { TrustText.formatBytes(it.txBytes) }, Modifier.weight(1f))
        }
        LinkAction("Read again", { reads++ }, glyph = Glyph.RESET)
        Row(verticalAlignment = Alignment.Top) {
            GlyphIcon(Glyph.LOCK, Film.clear, Modifier.padding(top = 2.dp), size = 18.dp)
            Spacer(Modifier.width(10.dp))
            Text(TrustText.NETWORK_NOTHING_ELSE, style = MaterialTheme.typography.bodyMedium, color = Film.bone, modifier = Modifier.weight(1f))
        }
        LearnMore(Modifier.padding(top = 4.dp), label = "Where TraceVeil's code uses the network") {
            TrustText.networkPaths(onlineVoice, DnsForwarder.FALLBACKS).forEachIndexed { i, p ->
                if (i > 0) Hairline(Modifier.padding(vertical = 4.dp))
                Text(p.title, style = MaterialTheme.typography.titleSmall, color = Film.bone)
                Text(p.line, style = MaterialTheme.typography.bodyMedium, color = Film.boneDim)
            }
            Hairline(Modifier.padding(vertical = 4.dp))
            Note(TrustText.NETWORK_COUNTER_DETAIL)
        }
    }
}

/** One counter: label over a big number; a shimmering bar while Android's counters are read. */
@Composable
private fun CounterTile(label: String, value: String?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.clip(shape).background(Film.base.copy(alpha = 0.45f)).border(1.dp, FilmExtra.hairline, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp).semantics(mergeDescendants = true) {},
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Film.boneDim)
        Spacer(Modifier.height(4.dp))
        if (value == null) {
            SkeletonBar(Modifier.fillMaxWidth(0.7f).shimmer(), height = 22.dp)
        } else {
            Text(value, style = MaterialTheme.typography.headlineSmall, color = Film.bone, maxLines = 1)
        }
    }
}

@Composable
private fun IdentityCard(identity: OwnIdentity, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val hashes by produceState<List<Pair<String, String?>>?>(null, identity) {
        value = withContext(Dispatchers.IO) { identity.apkFiles.map { it.name to TrustReader.sha256(it) } }
    }
    fun copy(label: String, value: String) {
        ExportManager.copy(ctx, label, value)
        Toast.makeText(ctx, "$label copied", Toast.LENGTH_SHORT).show()
    }
    val debugSigned = identity.signers.any { TrustText.isDebugCertificate(it.subject) }
    CollapsibleCard(
        "Identity to compare", modifier,
        summary = "Version ${identity.versionName ?: "not reported"} (${identity.versionCode})" + if (debugSigned) " · signed with Android's shared debug key" else "",
        summaryColor = if (debugSigned) Film.warn else Film.boneDim,
        glyph = Glyph.CHECK,
    ) {
        Text("Version", style = MaterialTheme.typography.labelMedium, color = Film.boneDim)
        Text("${identity.versionName ?: "Not reported"} (${identity.versionCode})", style = MaterialTheme.typography.bodyLarge, color = Film.bone)
        Hairline(Modifier.padding(vertical = 4.dp))
        Text("Signing certificate SHA-256", style = MaterialTheme.typography.labelMedium, color = Film.boneDim)
        if (identity.signers.isEmpty()) Note("Not readable (not established).")
        identity.signers.forEach { s -> HashRow(s.sha256) { copy("Signing certificate SHA-256", s.sha256) } }
        if (debugSigned) Callout(TrustText.DEBUG_KEY_NOTE)
        Hairline(Modifier.padding(vertical = 4.dp))
        Text("Installed APK file SHA-256", style = MaterialTheme.typography.labelMedium, color = Film.boneDim)
        val h = hashes
        when {
            h == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                BreathingDot(Film.scan)
                Spacer(Modifier.width(10.dp))
                Note("Computing… (reading the whole APK file on this phone)")
            }
            h.isEmpty() -> Note("Android did not report the APK file's location.")
            else -> h.forEach { (name, sha) ->
                if (h.size > 1) Text(name, fontFamily = Mono, fontSize = 11.sp, color = Film.boneDim)
                if (sha == null) Note("Could not read $name.") else HashRow(sha) { copy("APK file SHA-256", sha) }
            }
        }
        LearnMore(Modifier.padding(top = 4.dp)) { Note(TrustText.COMPARE_DETAIL) }
    }
}

@Composable
private fun HashRow(hex: String, onCopy: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(hex, fontFamily = Mono, fontSize = 12.sp, color = Film.bone, modifier = Modifier.weight(1f))
        TextButton(onClick = onCopy) { Text("Copy", color = Film.scan) }
    }
}
