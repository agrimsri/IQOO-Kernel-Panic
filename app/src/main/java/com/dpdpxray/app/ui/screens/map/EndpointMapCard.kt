package com.dpdpxray.app.ui.screens.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.capture.DnsForwarder
import com.dpdpxray.app.ui.components.FoldText
import com.dpdpxray.app.ui.components.KeyCaveat
import com.dpdpxray.app.ui.components.LearnMore
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.Mono
import com.dpdpxray.core.geo.Evidence
import com.dpdpxray.core.geo.EndpointGeoAggregator
import com.dpdpxray.core.geo.EndpointMapModel
import com.dpdpxray.core.geo.Fact
import com.dpdpxray.core.geo.GeoSource
import com.dpdpxray.core.geo.GeoTableInfo
import com.dpdpxray.core.geo.MapGuide
import com.dpdpxray.core.geo.MapMode
import com.dpdpxray.core.geo.MapPresenter
import com.dpdpxray.core.geo.MapRow
import com.dpdpxray.core.geo.OwnerRegistry
import com.dpdpxray.core.geo.LegalContext
import com.dpdpxray.core.geo.TableOrigin
import com.dpdpxray.app.ui.EndpointMapIntegration
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.trackers.TrackerCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val VISIBLE_ROWS = 4
private const val SHORT_NOTE = 120

private fun c(argb: Long) = Color(argb)

/**
 * Endpoint map for one audit: where tracker lookups were answered, or who runs those services and what law may apply.
 * One call mounts it: `EndpointMapCard(bundle.sessions, catalog)`. It loads the IP-to-country table itself (the person's
 * own GeoLite2 file if present, else the DB-IP Lite table bundled in the APK) and never touches the network.
 */
@Composable
fun EndpointMapCard(
    sessions: List<AuditSession>,
    catalog: TrackerCatalog,
    modifier: Modifier = Modifier,
    owners: OwnerRegistry = remember(catalog) { OwnerRegistry.fromCatalog(catalog) },
    legal: LegalContext = EndpointMapIntegration.legalContext(),
) {
    val ctx = LocalContext.current
    val geo by produceState<GeoSource?>(null) { value = GeoTableLoader.load(ctx) }
    val model by produceState<EndpointMapModel?>(null, sessions, geo, catalog) {
        value = geo?.let { g -> withContext(Dispatchers.Default) { EndpointGeoAggregator.build(sessions, catalog, g) } }
    }
    var mode by rememberSaveable { mutableStateOf(MapMode.SOURCE_DNS) }
    val m = model
    val network = remember(sessions) { MapGuide.networkContext(sessions, DnsForwarder.FALLBACKS) }
    EndpointMapCardContent(m, owners, mode, { mode = it }, GeoTableLoader.pushCommand(ctx), modifier, legal, network)
}

/** Stateless body, so the layout is the same for any model. */
@Composable
fun EndpointMapCardContent(
    model: EndpointMapModel?,
    owners: OwnerRegistry,
    mode: MapMode,
    onMode: (MapMode) -> Unit,
    pushCommand: String,
    modifier: Modifier = Modifier,
    legal: LegalContext = EndpointMapIntegration.legalContext(),
    network: List<Fact> = emptyList(),
) {
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(Film.raised).border(1.dp, Film.line, shape)) {
        Box(Modifier.fillMaxWidth().height(3.dp).background(Brush.horizontalGradient(
            listOf(Brand.TangerineDream.color, Brand.CoralGlow.color, Brand.BlushRose.color, Brand.BerryBlush.color))))
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Endpoint map", style = MaterialTheme.typography.titleMedium)
            // Which table placed the countries, so the source is visible before any country is read.
            (model?.source as? GeoSource.Loaded)?.let { Text(GeoTableInfo.of(it).headline, style = MaterialTheme.typography.labelMedium, color = Film.boneDim) }
            ModeSwitch(mode, onMode)
            if (model == null) {
                Text("Placing lookups…", style = MaterialTheme.typography.bodyMedium, color = Film.boneDim)
                return@Column
            }
            val view = remember(model, owners, mode, legal) { MapPresenter.view(model, owners, mode, legal) }
            FoldText(view.explanation)

            val highlighted = remember(model, owners, mode) { MapPresenter.highlight(model, owners, mode) }
            val uncertain = remember(model, mode) {
                if (mode == MapMode.SOURCE_DNS) model.countries.filter { it.allUncertain }.map { it.iso }.toSet() else emptySet()
            }
            if (highlighted.isNotEmpty()) {
                val fill = if (mode == MapMode.SOURCE_DNS) c(MapColors.LOCATED) else c(MapColors.COMPANY_LOCATION)
                WorldMapCanvas(highlighted - uncertain, uncertain, fill)
                Text(EndpointMapIntegration.OUTLINE_CREDIT, style = MaterialTheme.typography.labelSmall, color = Film.boneDim)
            }
            if (mode == MapMode.SOURCE_DNS && model.source !is GeoSource.Loaded) {
                Text("To place addresses in countries, copy your own GeoLite2-Country.mmdb to the phone:", style = MaterialTheme.typography.bodyMedium, color = Film.boneDim)
                Text(pushCommand, fontFamily = Mono, style = MaterialTheme.typography.bodyMedium)
            }

            view.empty?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Film.boneDim) }
            var expanded by rememberSaveable(mode) { mutableStateOf(false) }
            val shown = if (expanded) view.rows else view.rows.take(VISIBLE_ROWS)
            val rule = if (mode == MapMode.SOURCE_DNS) c(MapColors.accent(mode)) else Brand.BlushRose.color
            val repeats = remember(view) { MapPresenter.repeatedNotes(view.rows) }
            shown.forEachIndexed { r, row -> RowBlock(row, rule) { f -> (r to f) in repeats } }
            if (view.rows.size > VISIBLE_ROWS) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Show fewer" else "Show all ${view.rows.size}", color = c(MapColors.accent(mode)))
                }
            }

            // Text diet: the one caveat that must stay in view, then the table's own notes and licence credit as before.
            // The long explanation of each mode and the evidence legend fold into "What this map shows, and what it doesn't".
            KeyCaveat(
                if (mode == MapMode.SOURCE_DNS) "A country shows where an address is placed, not where data went."
                else "Nothing here says a vendor or app broke a law.",
                color = Brand.CoralGlow.color,
            )
            val folded = if (mode == MapMode.SOURCE_DNS) view.footer.take(1) else view.footer
            view.footer.drop(folded.size).forEach { Text(it, style = MaterialTheme.typography.labelMedium, color = Film.boneDim) }
            val legend = if (view.rows.isNotEmpty()) {
                "Observed: seen in this audit's DNS. Inferred: derived from it. Sourced: from a cited document. Unknown: no evidence."
            } else null
            MapLimitsFold(network, (model.source as? GeoSource.Loaded)?.origin == TableOrigin.BUNDLED, pushCommand, folded, legend)
        }
    }
}

/**
 * "What this map shows, and what it doesn't": this view's own notes ([footer]) and the evidence [legend] first, then short
 * lines, the capture's network, and why a country can matter.
 */
@Composable
private fun MapLimitsFold(network: List<Fact>, bundledTable: Boolean, pushCommand: String, footer: List<String>, legend: String?) {
    LearnMore(label = MapGuide.TITLE, openLabel = "Hide: ${MapGuide.TITLE.replaceFirstChar { it.lowercase() }}") {
        footer.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = Film.bone) }
        legend?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Film.boneDim) }
        MapGuide.points(DnsForwarder.FALLBACKS).forEach { p ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(p.title, style = MaterialTheme.typography.titleSmall, color = Film.bone)
                Text(p.line, style = MaterialTheme.typography.bodyMedium, color = Film.bone)
                Text(p.detail, style = MaterialTheme.typography.labelMedium, color = Film.boneDim)
            }
        }
        if (network.isNotEmpty()) {
            Text("This check's network", style = MaterialTheme.typography.titleSmall, color = Film.bone)
            network.forEachIndexed { i, f -> FactLine(f, "network#$i", repeat = false) }
        }
        if (bundledTable) {
            Text("Use your own table instead", style = MaterialTheme.typography.titleSmall, color = Film.bone)
            Text("If you have a MaxMind GeoLite2-Country.mmdb of your own, copy it to the phone and the map uses it in place of the bundled DB-IP table:",
                style = MaterialTheme.typography.labelMedium, color = Film.boneDim)
            Text(pushCommand, fontFamily = Mono, style = MaterialTheme.typography.labelMedium)
        }
        Text(MapGuide.WHY_HEADING, style = MaterialTheme.typography.titleSmall, color = Film.bone)
        MapGuide.whyItMatters.forEach { r ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${r.region}: ${r.rule}", style = MaterialTheme.typography.labelLarge, color = Film.bone)
                Text(r.line, style = MaterialTheme.typography.bodyMedium, color = Film.bone)
                Text(r.citation, style = MaterialTheme.typography.labelSmall, color = Film.boneDim)
            }
        }
        Text(MapGuide.WHY_CLOSING, style = MaterialTheme.typography.bodyMedium, color = Brand.CoralGlow.color)
    }
}

@Composable
private fun ModeSwitch(mode: MapMode, onMode: (MapMode) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Brand.MauveShadow.color).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        MapMode.entries.forEach { m ->
            val selected = m == mode
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (selected) c(MapColors.accent(m)) else Color.Transparent)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onMode(m) })
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(m.label, style = MaterialTheme.typography.labelLarge, color = if (selected) Film.base else Film.bone)
            }
        }
    }
}

@Composable
private fun RowBlock(row: MapRow, rule: Color, isRepeat: (Int) -> Boolean) {
    Row(Modifier.fillMaxWidth().drawBehind { drawRect(rule, size = Size(2.dp.toPx(), size.height)) }) {
        Column(Modifier.padding(start = 14.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(row.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                EvidenceChip(row.evidence)
            }
            row.facts.forEachIndexed { i, f -> FactLine(f, "${row.title}#$i", isRepeat(i)) }
        }
    }
}

@Composable
private fun FactLine(f: Fact, key: String, repeat: Boolean) {
    Column {
        Text(f.label, style = MaterialTheme.typography.labelMedium, color = Film.boneDim)
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                f.value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                color = if (f.label == "Confidence") Brand.CoralGlow.color else Film.bone,
            )
            EvidenceChip(f.evidence)
        }
        // A note already shown for an earlier row is not printed again.
        if (!repeat) f.note?.let { note ->
            var open by rememberSaveable(key) { mutableStateOf(false) }
            if (note.length <= SHORT_NOTE || open) Text(note, style = MaterialTheme.typography.labelMedium, color = Film.boneDim)
            if (note.length > SHORT_NOTE) TextButton(onClick = { open = !open }) {
                Text(if (open) "Hide basis" else "Show basis", color = Brand.LightBlue.color)
            }
        }
    }
}

@Composable
private fun EvidenceChip(e: Evidence) {
    val chip = MapColors.chip(e)
    val shape = RoundedCornerShape(6.dp)
    Text(
        e.label, style = MaterialTheme.typography.labelMedium, color = c(chip.text),
        modifier = Modifier.clip(shape)
            .then(if (chip.fill != null) Modifier.background(c(chip.fill)) else Modifier.border(1.dp, c(chip.text), shape))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
