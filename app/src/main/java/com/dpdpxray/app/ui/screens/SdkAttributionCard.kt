package com.dpdpxray.app.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dpdpxray.app.attribution.AppCodeReader
import com.dpdpxray.app.attribution.CodeScanState
import com.dpdpxray.app.data.AuditBundle
import com.dpdpxray.app.ui.XrayViewModel
import com.dpdpxray.app.ui.components.BreathingDot
import com.dpdpxray.app.ui.components.Callout
import com.dpdpxray.app.ui.components.ChipFlow
import com.dpdpxray.app.ui.components.CodeBlock
import com.dpdpxray.app.ui.components.CollapsibleCard
import com.dpdpxray.app.ui.components.FoldText
import com.dpdpxray.app.ui.components.Hairline
import com.dpdpxray.app.ui.components.KeyCaveat
import com.dpdpxray.app.ui.components.LearnMore
import com.dpdpxray.app.ui.components.Note
import com.dpdpxray.app.ui.components.RuleRow
import com.dpdpxray.app.ui.components.SecondaryAction
import com.dpdpxray.app.ui.components.Tag
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.guide.LinkAction
import com.dpdpxray.app.ui.theme.Brand
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.Mono
import com.dpdpxray.app.ui.theme.categoryColor
import com.dpdpxray.app.ui.theme.severityColor
import com.dpdpxray.core.apk.SdkCodeSignatures
import com.dpdpxray.core.attribution.SdkAttribution
import com.dpdpxray.core.attribution.SdkGroup
import com.dpdpxray.core.fixes.FixTemplate
import com.dpdpxray.core.model.Category
import com.dpdpxray.core.model.EvidenceType
import com.dpdpxray.core.model.Finding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Developer mode, on Results: "Which SDK caused this?". One row per catalogue service, in the order of the score weight
 * of the findings that name it: name and category, company, when it was first seen relative to consent, the checks it
 * triggered, its hosts, the catalogue wording, and the existing fix templates folded behind "How to fix". Services seen
 * without a finding fold away; hosts the catalogue does not list stay "Unknown hosts". An optional, tap-started read of
 * the installed app's code marks SDKs whose class names are bundled (PRESENT IN CODE, static).
 */
@Composable
internal fun SdkAttributionCard(
    vm: XrayViewModel, b: AuditBundle, groups: List<SdkGroup>, modifier: Modifier = Modifier,
    /** A website check: requests instead of lookups, no app-code read and no Android SDK fixes. */
    web: Boolean = false,
) {
    val unit = if (web) "request" else "lookup"
    val fixFor: (String) -> FixTemplate? = if (web) { _ -> null } else { id -> vm.fixTemplate(id) }
    val linked = groups.filter { it.known && it.checks.isNotEmpty() }
    val quiet = groups.filter { it.known && it.checks.isEmpty() }
    val unknown = groups.firstOrNull { !it.known }
    val summary = SdkAttribution.headline(groups, byCompany = false) ?: when {
        groups.isEmpty() -> "No ${unit}s were captured in this audit."
        else -> "No finding in this audit names a catalogued SDK."
    }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var scan by remember(b.meta.id) { mutableStateOf<CodeScanState>(CodeScanState.Idle) }
    val code = (scan as? CodeScanState.Done)?.result?.found
    val signed = remember { SdkCodeSignatures.default.entries.map { it.entity }.toSet() }
    val row: @Composable (SdkGroup) -> Unit = { g ->
        SdkGroupRow(g, b.result.findings, code, signed, fixFor, unit) { label, text -> vm.copy(label, text) }
    }

    CollapsibleCard(if (web) "Which company or tracker?" else "Which SDK caused this?", modifier, summary = summary, glyph = Glyph.SEARCH, accent = Brand.coral) {
        KeyCaveat("A $unit is an attempt to contact a host, not proof that data was sent.", color = Film.scan)
        if (linked.isEmpty()) Note("No finding in this audit names a catalogued SDK.")
        linked.forEach { row(it) }
        if (quiet.isNotEmpty()) {
            LearnMore(label = "Also contacted, no finding (${quiet.size})", openLabel = "Hide") { quiet.forEach { row(it) } }
        }
        unknown?.let { row(it) }
        if (!web) Hairline(Modifier.padding(vertical = 4.dp))
        if (!web) CodeScanSection(b, groups, scan) {
            scope.launch {
                scan = CodeScanState.Running
                scan = withContext(Dispatchers.IO) { AppCodeReader.scan(ctx, b.meta.targetPackage) }
            }
        }
    }
}

private fun categoryLabel(c: Category) = when (c) {
    Category.FIRST_PARTY_SAME_COMPANY -> "Same company"
    Category.COMPANY_SERVICE -> "Company service"
    Category.CONTENT_DELIVERY -> "Content delivery"
    Category.CRASH_REPORTING -> "Crash reporting"
    else -> c.name.lowercase().replaceFirstChar { it.uppercase() }
}

/** One service: what it is, when it was first seen, the checks it triggered, its hosts, and how to fix it. */
@Composable
private fun SdkGroupRow(
    g: SdkGroup,
    findings: List<Finding>,
    code: Map<String, List<String>>?,
    signed: Set<String>,
    fixFor: (String) -> FixTemplate?,
    unit: String,
    copy: (String, String) -> Unit,
) {
    val accent = if (g.known) categoryColor(g.category) else Film.boneDim
    RuleRow(accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(g.name, style = MaterialTheme.typography.titleMedium, color = Film.bone, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Tag(if (g.known) categoryLabel(g.category) else "Not catalogued", accent)
        }
        val who = listOfNotNull(
            g.company?.let { "Company: $it" },
            "same company as the app".takeIf { g.sameCompanyAsApp },
        ).joinToString(" · ")
        if (who.isNotEmpty()) Text(who, style = MaterialTheme.typography.labelMedium, color = Film.boneDim, modifier = Modifier.padding(top = 2.dp))
        val first = SdkAttribution.firstSeenLine(g)
        if (first.isNotEmpty()) {
            val early = first.endsWith(SdkAttribution.BEFORE_CONSENT) || first.endsWith(SdkAttribution.AFTER_REJECT)
            Text(first, style = MaterialTheme.typography.bodyMedium, color = if (early && g.known) Film.warn else Film.boneDim, modifier = Modifier.padding(top = 4.dp))
        }
        if (g.hosts.isNotEmpty()) {
            Text(
                SdkAttribution.hostPreview(g.hosts), fontFamily = Mono, fontSize = 13.sp, color = Film.bone,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
            )
            g.hosts.firstNotNullOfOrNull { h -> h.viaAlias?.let { "${h.host} → $it" } }?.let {
                Text("Alias observed: $it", style = MaterialTheme.typography.labelMedium, color = Film.warn)
            }
        }
        ChipFlow(Modifier.padding(top = 8.dp)) {
            g.checks.forEach { id ->
                val sev = findings.firstOrNull { it.checkId == id }?.severity
                Tag(id.code, sev?.let { severityColor(it) } ?: Film.boneDim)
            }
            if (g.evidence == EvidenceType.OBSERVED) {
                Tag("OBSERVED · ${g.lookups} ${if (g.lookups == 1) unit else "${unit}s"}", Film.scan)
            } else {
                Tag("INFERRED", Film.warn)
            }
            if (code != null && g.known) {
                when {
                    g.name in code -> Tag("PRESENT IN CODE (static)", Brand.peach)
                    g.name in signed -> Tag("Code: not found by name", Film.boneDim)
                }
            }
        }
        Text(g.catalogueMatch, style = MaterialTheme.typography.bodySmall, color = Film.boneDim, modifier = Modifier.padding(top = 6.dp))
        val fixes = g.fixIds.mapNotNull(fixFor)
        if (fixes.isNotEmpty()) {
            LearnMore(label = "How to fix", openLabel = "Hide fix") {
                fixes.forEach { fix -> FixSteps(fix, copy) }
            }
        }
    }
}

/** The same fix template text and copy buttons as the How to fix screen. */
@Composable
private fun FixSteps(fix: FixTemplate, copy: (String, String) -> Unit) {
    Text(fix.title, style = MaterialTheme.typography.titleSmall, color = Film.bone)
    if (!fix.verified) Callout("Check the exact API for your SDK version in the vendor docs.")
    fix.manifest?.let { m ->
        Text("AndroidManifest.xml", style = MaterialTheme.typography.labelMedium, color = Film.boneDim)
        CodeBlock(m)
        TextButton(onClick = { copy("manifest", m) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Copy manifest change", color = Film.scan) }
    }
    fix.code?.let { c ->
        Text("Code", style = MaterialTheme.typography.labelMedium, color = Film.boneDim)
        CodeBlock(c)
        TextButton(onClick = { copy("code", c) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Copy code", color = Film.scan) }
    }
    fix.steps.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, color = Film.bone) }
    fix.docUrl?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Film.scan) }
}

/** The optional static read of the installed app's code, started by a tap. */
@Composable
private fun CodeScanSection(b: AuditBundle, groups: List<SdkGroup>, scan: CodeScanState, onScan: () -> Unit) {
    Text("Found in the app's code", style = MaterialTheme.typography.titleSmall, color = Film.bone)
    if (b.meta.sample) {
        Note("Not available for sample data: there is no installed app behind it.")
        return
    }
    when (scan) {
        CodeScanState.Idle -> {
            FoldText(
                "Optional: read the class names inside the app installed on this phone and mark the SDKs whose code is bundled. " +
                    "Read-only, on this phone. Present in code is not the same as called at runtime, and a name not found does not " +
                    "show an SDK is absent: code shrinkers can rename classes.",
            )
            SecondaryAction("Look for these SDKs in the app's code", onScan, Modifier.fillMaxWidth())
        }
        CodeScanState.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
            BreathingDot(Film.scan)
            Spacer(Modifier.width(10.dp))
            Note("Reading the app's code files on this phone…", Film.scan)
        }
        is CodeScanState.Unavailable -> {
            Note(scan.reason, Film.warn)
            LinkAction("Try again", onScan, glyph = Glyph.RESET)
        }
        is CodeScanState.Done -> {
            val r = scan.result
            Note(
                "Read from the version installed now: ${scan.versionName ?: "version not reported"} (code ${scan.versionCode ?: "not reported"}). " +
                    "${r.dexFilesRead} code ${if (r.dexFilesRead == 1) "file" else "files"} read" +
                    (if (r.dexFilesSkipped > 0) ", ${r.dexFilesSkipped} skipped (too large or unreadable)" else "") + ".",
            )
            val audited = b.sessions.firstNotNullOfOrNull { it.manifestFacts?.versionCode }
            if (audited != null && scan.versionCode != null && audited != scan.versionCode) {
                Callout("The installed version (code ${scan.versionCode}) differs from the audited one (code $audited), so its code may have changed since the check.")
            }
            if (!r.complete) Note("Partial read: some code files were skipped, so a name not found means even less.", Film.warn)
            if (r.found.isEmpty()) {
                Note("None of the SDKs TraceVeil knows by class name were found by name. This does not show they are absent.")
            } else {
                ChipFlow { r.found.forEach { (entity, packages) -> Tag("$entity · ${packages.first()}", Brand.peach) } }
                val codeOnly = r.found.keys.filter { e -> groups.none { it.name == e && it.hosts.isNotEmpty() } }
                if (codeOnly.isNotEmpty()) Note("In the code but not looked up in this audit: ${codeOnly.joinToString()}.")
            }
            KeyCaveat("PRESENT IN CODE (static) means the class names are bundled. It does not show the code runs, or when.", color = Film.boneDim)
        }
    }
}
