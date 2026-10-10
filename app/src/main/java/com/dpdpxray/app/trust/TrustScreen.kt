package com.dpdpxray.app.trust

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.dpdpxray.app.capture.DnsForwarder
import com.dpdpxray.app.control.DeviceChecks
import com.dpdpxray.app.report.ExportManager
import com.dpdpxray.app.ui.components.Callout
import com.dpdpxray.app.ui.components.Hairline
import com.dpdpxray.app.ui.components.LearnMore
import com.dpdpxray.app.ui.components.Note
import com.dpdpxray.app.ui.components.PrimaryAction
import com.dpdpxray.app.ui.components.SecondaryAction
import com.dpdpxray.app.ui.components.SectionCard
import com.dpdpxray.app.ui.components.Tag
import com.dpdpxray.app.ui.components.TopBar
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.Mono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Why you can trust TraceVeil": what stays on the phone, the permissions it holds (read live), a self-check with its
 * own APK Guard, its own network use (Android's counters), the identity to compare, and how findings are made. Every
 * sentence comes from [TrustText], whose tests tie it to the code; every value is read from this install.
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
            Note("Each value below is read from this phone now, and each sentence matches TraceVeil's code. Check them yourself.")
            val f = live
            if (f == null) {
                Note("Reading this install…")
                return@Column
            }
            StaysOnPhoneCard(f)
            PermissionsCard(f)
            SectionCard(title = "Check TraceVeil with its own APK Guard", glyph = Glyph.APK) {
                Note("APK Guard reads what Android shows about TraceVeil itself: its permissions, special services, installer and signer, with the same rules it uses for any app.")
                Spacer(Modifier.height(12.dp))
                PrimaryAction("Open APK Guard for TraceVeil", onSelfCheck)
            }
            NetworkCard(f.onlineVoice)
            IdentityCard(f.identity)
            SectionCard(title = "How findings are made", glyph = Glyph.BOOK) {
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

/** Short lines always visible; every detail behind one "Learn more" at the end of the card. */
@Composable
private fun ClaimList(claims: List<Claim>) {
    claims.forEachIndexed { i, c ->
        if (i > 0) Hairline(Modifier.padding(vertical = 8.dp))
        Text(c.title, style = MaterialTheme.typography.titleSmall, color = Film.bone)
        Text(c.line, style = MaterialTheme.typography.bodyMedium, color = Film.bone)
    }
    val details = claims.mapNotNull { c -> c.detail?.let { c.title to it } }
    if (details.isNotEmpty()) LearnMore(Modifier.padding(top = 4.dp)) {
        details.forEach { (title, detail) -> Note("$title: $detail") }
    }
}

@Composable
private fun StaysOnPhoneCard(f: LiveFacts) {
    SectionCard(title = "What stays on this phone", glyph = Glyph.LOCK, accent = Film.clear) {
        ClaimList(TrustText.staysOnPhone(f.onlineVoice, f.backup))
        if (f.onlineVoice) Callout("Online voice is on for this phone, so spoken answers' text is sent to Sarvam AI.", Modifier.padding(top = 8.dp), color = Film.high)
    }
}

@Composable
private fun PermissionsCard(f: LiveFacts) {
    val ctx = LocalContext.current
    SectionCard(title = "Permissions TraceVeil holds", glyph = Glyph.SETTINGS) {
        Note("Read from Android just now: everything TraceVeil requests, including what its libraries add.")
        f.permissions.forEach { p ->
            Hairline(Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.title, style = MaterialTheme.typography.titleSmall, color = Film.bone, modifier = Modifier.weight(1f))
                Tag(p.status, if (p.status == "Granted") Film.clear else Film.boneDim)
            }
            Text(p.purpose, style = MaterialTheme.typography.bodyMedium, color = if (p.described) Film.bone else Film.warn)
            Text(p.name, fontFamily = Mono, fontSize = 11.sp, color = Film.boneDim)
        }
        if (f.permissions.isEmpty()) Note("Android did not return TraceVeil's permission list.")
        Spacer(Modifier.height(16.dp))
        Text("Special access", style = MaterialTheme.typography.titleMedium, color = Film.bone)
        val access = listOf(
            TrustText.vpnAccess to (f.vpnFor?.let { "Running now for $it" } ?: "Not running now"),
            TrustText.accessibilityAccess to (if (f.accessibility) "On" else "Off"),
        )
        access.forEach { (c, state) ->
            Hairline(Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.title, style = MaterialTheme.typography.titleSmall, color = Film.bone, modifier = Modifier.weight(1f))
                Tag(state, Film.boneDim)
            }
            Text(c.line, style = MaterialTheme.typography.bodyMedium, color = Film.bone)
        }
        LearnMore(Modifier.padding(top = 4.dp)) {
            access.forEach { (c, _) -> c.detail?.let { Note("${c.title}: $it") } }
        }
        SecondaryAction("Open Android's settings for TraceVeil", { DeviceChecks.openAppInfo(ctx, ctx.packageName) }, Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}

@Composable
private fun NetworkCard(onlineVoice: Boolean) {
    var reads by remember { mutableIntStateOf(0) }
    val traffic by produceState<OwnTraffic?>(null, reads) { value = withContext(Dispatchers.IO) { TrustReader.traffic() } }
    SectionCard(title = "TraceVeil's own network use", glyph = Glyph.PRIVATE_DNS) {
        val t = traffic
        Note(if (t == null) "Reading Android's counters…" else "Android's counters for TraceVeil, since the phone started ${TrustText.formatUptime(t.uptimeMillis)} ago:")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column {
                Text("Received", style = MaterialTheme.typography.labelMedium, color = Film.boneDim)
                Text(t?.let { TrustText.formatBytes(it.rxBytes) } ?: "…", style = MaterialTheme.typography.titleLarge, color = Film.bone)
            }
            Column {
                Text("Sent", style = MaterialTheme.typography.labelMedium, color = Film.boneDim)
                Text(t?.let { TrustText.formatBytes(it.txBytes) } ?: "…", style = MaterialTheme.typography.titleLarge, color = Film.bone)
            }
        }
        TextButton(onClick = { reads++ }) { Text("Read again", color = Film.scan) }
        Text("Where TraceVeil's code uses the network", style = MaterialTheme.typography.titleSmall, color = Film.bone)
        TrustText.networkPaths(onlineVoice, DnsForwarder.FALLBACKS).forEach { p ->
            Hairline(Modifier.padding(vertical = 8.dp))
            Text(p.title, style = MaterialTheme.typography.titleSmall, color = Film.bone)
            Text(p.line, style = MaterialTheme.typography.bodyMedium, color = Film.bone)
        }
        Spacer(Modifier.height(8.dp))
        Note(TrustText.NETWORK_NOTHING_ELSE, Film.bone)
        LearnMore(Modifier.padding(top = 4.dp)) { Note(TrustText.NETWORK_COUNTER_DETAIL) }
    }
}

@Composable
private fun IdentityCard(identity: OwnIdentity) {
    val ctx = LocalContext.current
    val hashes by produceState<List<Pair<String, String?>>?>(null, identity) {
        value = withContext(Dispatchers.IO) { identity.apkFiles.map { it.name to TrustReader.sha256(it) } }
    }
    fun copy(label: String, value: String) {
        ExportManager.copy(ctx, label, value)
        Toast.makeText(ctx, "$label copied", Toast.LENGTH_SHORT).show()
    }
    SectionCard(title = "Identity to compare", glyph = Glyph.CHECK) {
        Text("Version", style = MaterialTheme.typography.labelMedium, color = Film.boneDim)
        Text("${identity.versionName ?: "Not reported"} (${identity.versionCode})", style = MaterialTheme.typography.bodyLarge, color = Film.bone)
        Hairline(Modifier.padding(vertical = 8.dp))
        Text("Signing certificate SHA-256", style = MaterialTheme.typography.labelMedium, color = Film.boneDim)
        if (identity.signers.isEmpty()) Note("Not readable (not established).")
        identity.signers.forEach { s -> HashRow(s.sha256) { copy("Signing certificate SHA-256", s.sha256) } }
        if (identity.signers.any { TrustText.isDebugCertificate(it.subject) }) Callout(TrustText.DEBUG_KEY_NOTE, Modifier.padding(top = 6.dp))
        Hairline(Modifier.padding(vertical = 8.dp))
        Text("Installed APK file SHA-256", style = MaterialTheme.typography.labelMedium, color = Film.boneDim)
        val h = hashes
        when {
            h == null -> Note("Computing… (reading the whole APK file on this phone)")
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
