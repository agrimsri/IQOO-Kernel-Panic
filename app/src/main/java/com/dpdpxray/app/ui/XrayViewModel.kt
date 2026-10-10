package com.dpdpxray.app.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dpdpxray.app.ai.BackendKind
import com.dpdpxray.app.ai.BenchResult
import com.dpdpxray.app.ai.Explanation
import com.dpdpxray.app.ai.Explanations
import com.dpdpxray.app.ai.LlmStatus
import com.dpdpxray.app.audit.AuditConfig
import com.dpdpxray.app.capture.CaptureBus
import com.dpdpxray.app.consent.ConsentAccessibilityService
import com.dpdpxray.app.consent.ConsentBus
import com.dpdpxray.app.control.AuditableApp
import com.dpdpxray.app.control.DeviceChecks
import com.dpdpxray.app.control.Preflight
import com.dpdpxray.app.control.ShizukuBridge
import com.dpdpxray.app.data.AuditBundle
import com.dpdpxray.app.data.AuditMeta
import com.dpdpxray.app.data.AuditComparison
import com.dpdpxray.app.data.AuditComparisonResult
import com.dpdpxray.app.data.LegalLensSetting
import com.dpdpxray.app.data.LensChoice
import com.dpdpxray.app.data.SampleAudit
import com.dpdpxray.app.data.CheckBackups
import com.dpdpxray.app.BuildConfig
import com.dpdpxray.app.evidence.AndroidEvidenceKey
import com.dpdpxray.app.evidence.EvidenceFiles
import com.dpdpxray.app.evidence.EvidenceSigning
import com.dpdpxray.app.graph
import com.dpdpxray.app.report.AuditJsonExport
import com.dpdpxray.app.report.ExportManager
import com.dpdpxray.app.report.EvidenceReport
import com.dpdpxray.app.report.HtmlExporter
import com.dpdpxray.app.report.PdfExporter
import com.dpdpxray.app.report.SampleReport
import com.dpdpxray.core.attribution.SdkAttribution
import com.dpdpxray.core.attribution.SdkGroup
import com.dpdpxray.core.audit.PhaseAssigner
import com.dpdpxray.core.datasafety.DataSafetyParser
import com.dpdpxray.core.datasafety.PlayLabelCheck
import com.dpdpxray.core.datasafety.PlayLabelComparator
import com.dpdpxray.core.datasafety.PlayLabelExport
import com.dpdpxray.core.datasafety.PlayLabelRecord
import com.dpdpxray.core.datasafety.PlayLabelReport
import com.dpdpxray.core.export.AuditBackups
import com.dpdpxray.core.export.AuditBundles
import com.dpdpxray.core.export.BackupFormatException
import com.dpdpxray.core.export.SdkAttributionExport
import com.dpdpxray.core.evidence.EvidenceSignatures
import com.dpdpxray.core.dpdp.Passage
import com.dpdpxray.core.fixes.FixTemplate
import com.dpdpxray.core.law.LegalLens
import com.dpdpxray.core.law.LegalProfile
import com.dpdpxray.core.law.LensPassage
import com.dpdpxray.core.law.asLensPassage
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.Finding
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.model.ScreenAudit
import com.dpdpxray.core.notice.RightsNotice
import com.dpdpxray.core.report.MarkdownReport
import com.dpdpxray.core.rules.RulesEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class XrayViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    val graph = app.graph

    val audits: StateFlow<List<AuditMeta>> = graph.repository.audits
    val progress = graph.orchestrator.progress
    val llmStatus: StateFlow<LlmStatus> = graph.llm.status
    val settings = graph.settings.settings
    val liveNet = CaptureBus.events
    val liveConsent = ConsentBus.events

    private val _updatedApps = MutableStateFlow<List<com.dpdpxray.core.update.UpdatedApp>>(emptyList())

    /** Update Watch: checked apps whose installed version differs from their latest check (empty until read). */
    val updatedApps: StateFlow<List<com.dpdpxray.core.update.UpdatedApp>> = _updatedApps.asStateFlow()

    /** Re-reads the installed versions of checked apps (on this phone, read-only). */
    fun refreshUpdates() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { com.dpdpxray.app.autoguard.UpdateWatchSource.updates(ctx) }.onSuccess { _updatedApps.value = it }
        }
    }

    init {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(audits, com.dpdpxray.app.autoguard.UpdateWatchSource.changes) { a, c -> a.map { it.id } to c }
                .collect { refreshUpdates() }
        }
    }

    private val _preflight = MutableStateFlow(Preflight(false, false, false, false, false, false, false))
    val preflight = _preflight.asStateFlow()

    private val _apps = MutableStateFlow<List<AuditableApp>>(emptyList())
    val apps = _apps.asStateFlow()

    private val _bundle = MutableStateFlow<AuditBundle?>(null)
    val bundle = _bundle.asStateFlow()

    private val _explanations = MutableStateFlow<Map<String, Explanation>>(emptyMap())
    val explanations = _explanations.asStateFlow()
    private val _explaining = MutableStateFlow<String?>(null)
    val explaining = _explaining.asStateFlow()

    private val _bench = MutableStateFlow<List<BenchResult>>(emptyList())
    val bench = _bench.asStateFlow()
    private val _benchRunning = MutableStateFlow<BackendKind?>(null)
    val benchRunning = _benchRunning.asStateFlow()

    private val _cameraResult = MutableStateFlow<CameraCheckState>(CameraCheckState.Idle)
    val cameraResult = _cameraResult.asStateFlow()

    /** The last audit-data export's signing line, as (audit id, line), for the Report screen. */
    private val _evidenceLine = MutableStateFlow<Pair<String, String>?>(null)
    val evidenceLine = _evidenceLine.asStateFlow()
    private val evidenceKey by lazy { AndroidEvidenceKey(ctx) }

    private val _toast = MutableStateFlow<String?>(null)
    val toast = _toast.asStateFlow()
    fun toastShown() { _toast.value = null }

    fun refreshPreflight() {
        graph.llm.refreshAvailability()
        _preflight.value = Preflight(
            vpnReady = DeviceChecks.vpnReady(ctx),
            accessibilityOn = DeviceChecks.accessibilityOn(ctx),
            shizukuRunning = ShizukuBridge.isRunning(),
            shizukuGranted = ShizukuBridge.hasPermission(),
            privateDnsActive = DeviceChecks.privateDnsActive(ctx),
            modelFound = llmStatus.value !is LlmStatus.NoModel,
            modelLoaded = graph.llm.isReady,
            otherVpnActive = DeviceChecks.otherVpnActive(ctx),
        )
    }

    fun prepareAudit(): Boolean {
        refreshPreflight()
        val problem = AuditStartReadiness.problem(_preflight.value)
        if (problem != null) _toast.value = problem
        return problem == null
    }

    fun requestShizuku() = ShizukuBridge.requestPermission()

    fun loadApps() = viewModelScope.launch {
        _apps.value = withContext(Dispatchers.IO) { DeviceChecks.launchableApps(ctx) }
    }

    fun startAudit(config: AuditConfig): Boolean {
        // One tunnel at a time: an audit would replace a running quarantine run's tunnel.
        if (com.dpdpxray.app.quarantine.QuarantineController.isActive) { _toast.value = "Stop the quarantine run before starting an audit."; return false }
        if (!prepareAudit()) return false
        graph.orchestrator.start(config)
        return true
    }
    fun cancelAudit() = graph.orchestrator.cancel()
    fun acknowledgeProgress() = graph.orchestrator.acknowledge()

    fun markConsentNow() = viewModelScope.launch {
        val ok = ConsentAccessibilityService.instance?.markNow() == true
        _toast.value = if (ok) "Consent moment marked" else "Turn on the TraceVeil accessibility service first"
    }

    /** Builds the sample audit through the same rules/report pipeline as a live one. */
    fun openSample(onReady: (String) -> Unit) = openSample(SampleAudit.Variant.BASELINE, onReady)

    fun openSample(variant: SampleAudit.Variant, onReady: (String) -> Unit) = viewModelScope.launch(Dispatchers.Default) {
        val id = "sample-${System.currentTimeMillis()}"
        val sessions = SampleAudit.sessions(System.currentTimeMillis(), variant)
        val catalog = graph.catalog()
        val result = RulesEngine(catalog).evaluate(sessions)
        val fixes = graph.fixCatalog.fixesFor(result, catalog)
        val md = EvidenceReport.annotate(SampleReport.annotate(MarkdownReport.build(result, sessions, fixes, graph.citations()), sessions), sessions, sample = true)
        graph.repository.save(
            AuditMeta(id, sessions.first().targetPackage, sessions.first().targetLabel, System.currentTimeMillis(), result.score, result.band,
                result.findings.size, sessions.map { it.plan.label }, sample = true),
            sessions, result, md,
        )
        withContext(Dispatchers.Main) { onReady(id) }
    }

    fun open(id: String) = viewModelScope.launch {
        if (_bundle.value?.meta?.id != id) _bundle.value = graph.repository.load(id)
    }

    fun delete(id: String) = viewModelScope.launch { graph.repository.delete(id) }

    fun fixesFor(bundle: AuditBundle): List<FixTemplate> = graph.fixCatalog.fixesFor(bundle.result, graph.catalog())

    /** "Which SDK caused this?": the audit's lookups grouped by catalogue service, in order of the findings' weight. */
    fun sdkGroups(bundle: AuditBundle): List<SdkGroup> =
        runCatching { SdkAttribution(graph.catalog(), graph.fixCatalog).groups(bundle.result, bundle.sessions) }.getOrDefault(emptyList())

    fun fixTemplate(id: String): FixTemplate? = graph.fixCatalog.template(id)

    /**
     * "Play label vs what we saw": the attached Play Data safety label set against this audit's SDK groups. Offline and
     * deterministic; it is never part of the score or the findings.
     */
    fun playLabelCheck(record: PlayLabelRecord, groups: List<SdkGroup>): PlayLabelCheck =
        PlayLabelComparator(graph.catalog()).compare(record.parsed, groups)

    /** Saves [text] (pasted, read from a file, or the demo label) as this check's Play label. Nothing is fetched or sent. */
    fun attachPlayLabel(bundle: AuditBundle, text: String, source: String) = viewModelScope.launch {
        val trimmed = text.trim().take(DataSafetyParser.MAX_CHARS)
        if (trimmed.isEmpty()) { _toast.value = "The label text is empty"; return@launch }
        val record = PlayLabelRecord(trimmed, source, System.currentTimeMillis())
        val saved = withContext(Dispatchers.IO) { runCatching { graph.repository.savePlayLabel(bundle.meta.id, record) }.isSuccess }
        if (!saved) { _toast.value = "Could not save the label with this check"; return@launch }
        _bundle.update { b -> if (b?.meta?.id == bundle.meta.id) b.copy(playLabel = record) else b }
        _toast.value = if (record.parsed.readable) "Label saved with this check" else "Saved, but no Data safety statement was found in that text"
    }

    /** Reads a text file the person picked with Android's file picker (at most 256 KB) and attaches it as the label. */
    fun attachPlayLabelFile(bundle: AuditBundle, uri: android.net.Uri) = viewModelScope.launch {
        val text = withContext(Dispatchers.IO) {
            runCatching { ctx.contentResolver.openInputStream(uri)?.use { CheckBackups.readBounded(it, MAX_LABEL_FILE_BYTES) }?.toString(Charsets.UTF_8) }.getOrNull()
        }
        if (text == null) { _toast.value = "Could not read that file as text (the limit is 256 KB)"; return@launch }
        attachPlayLabel(bundle, text, PlayLabelRecord.FILE)
    }

    fun removePlayLabel(bundle: AuditBundle) = viewModelScope.launch {
        withContext(Dispatchers.IO) { runCatching { graph.repository.deletePlayLabel(bundle.meta.id) } }
        _bundle.update { b -> if (b?.meta?.id == bundle.meta.id) b.copy(playLabel = null) else b }
        _toast.value = "Label removed from this check"
    }

    /** The attached label with its comparison for exports; the label alone if the comparison cannot be built. */
    private fun playLabelExport(bundle: AuditBundle, groups: List<SdkGroup>): PlayLabelExport? = bundle.playLabel?.let { rec ->
        runCatching { PlayLabelExport.of(rec, playLabelCheck(rec, groups)) }.getOrElse { PlayLabelExport.of(rec, null) }
    }

    private val _backup = MutableStateFlow<BackupUi>(BackupUi.Idle)
    /** Progress and outcome of the last "Back up my checks" or "Restore from a backup". */
    val backup = _backup.asStateFlow()

    /** Writes every saved check into the file the person created with Android's file picker. Never uploads anything. */
    fun backupTo(uri: android.net.Uri) = viewModelScope.launch {
        if (_backup.value is BackupUi.Working) return@launch
        _backup.value = BackupUi.Working("Backing up your checks…")
        graph.repository.refresh()
        val ids = audits.value.map { it.id }
        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                val stream = runCatching { ctx.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
                    ?: ctx.contentResolver.openOutputStream(uri, "w")
                    ?: error("no output stream")
                stream.use { out -> CheckBackups.write(graph.repository, ids, out, BuildConfig.VERSION_NAME, AuditBundles.now()) }
            }
        }
        _backup.value = outcome.fold(
            { BackupUi.Done(it.message) },
            { BackupUi.Failed("Could not write the backup file. Nothing on this phone was changed.") },
        )
        _toast.value = (_backup.value as? BackupUi.Done)?.message ?: (_backup.value as BackupUi.Failed).message
    }

    /** Checks a backup the person picked, then adds the checks that are not on this phone yet. */
    fun restoreFrom(uri: android.net.Uri) = viewModelScope.launch {
        if (_backup.value is BackupUi.Working) return@launch
        _backup.value = BackupUi.Working("Checking the backup…")
        val read: Result<com.dpdpxray.core.export.BackupContents> = withContext(Dispatchers.IO) { readBackup(uri) }
        val contents = read.getOrElse {
            _backup.value = BackupUi.Failed(it.message ?: "Could not read this file. Nothing was restored.")
            _toast.value = (_backup.value as BackupUi.Failed).message
            return@launch
        }
        graph.repository.refresh()
        val plan = AuditBackups.plan(contents, audits.value.map { it.id }.toSet())
        _backup.value = BackupUi.Working("Restoring ${plan.toRestore.size} checks…")
        val outcome = withContext(Dispatchers.IO) { CheckBackups.restore(graph.repository, plan, ::restoredReport) }
        _backup.value = BackupUi.Done(outcome.message)
        _toast.value = outcome.message
    }

    /** Size limit, then the bounded read, then core's schema, hash and audit checks. Never throws. */
    private fun readBackup(uri: android.net.Uri): Result<com.dpdpxray.core.export.BackupContents> = try {
        val size = runCatching {
            ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        }.getOrNull()
        AuditBackups.sizeProblem(size)?.let { throw BackupFormatException(it) }
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { CheckBackups.readBounded(it, AuditBackups.MAX_BYTES) }
            ?: throw BackupFormatException(AuditBackups.sizeProblem(AuditBackups.MAX_BYTES + 1)!!)
        Result.success(AuditBackups.read(bytes.toString(Charsets.UTF_8)))
    } catch (e: BackupFormatException) {
        Result.failure(e)
    } catch (e: OutOfMemoryError) {
        Result.failure(BackupFormatException("This backup is too large to check on this phone. Nothing was restored."))
    } catch (e: Exception) {
        Result.failure(BackupFormatException("Could not read this file. Nothing was restored."))
    }

    /** The saved report for a restored audit, built the same way as when an audit is saved; empty if that fails. */
    private fun restoredReport(meta: AuditMeta, sessions: List<AuditSession>, result: com.dpdpxray.core.model.AuditResult): String =
        runCatching {
            val built = MarkdownReport.build(result, sessions, graph.fixCatalog.fixesFor(result, graph.catalog()), graph.citations())
            EvidenceReport.annotate(if (meta.sample) SampleReport.annotate(built, sessions) else built, sessions, meta.timingProfile, sample = meta.sample)
        }.getOrDefault("")

    suspend fun previousComparison(bundle: AuditBundle): AuditComparisonResult = beforeAfter(bundle).comparison

    /** The "Before / after" card: the comparison with the previous audit, and the version update between them if any. */
    data class BeforeAfter(val comparison: AuditComparisonResult, val update: com.dpdpxray.app.data.VersionUpdate?, val previousTimeMs: Long?)

    suspend fun beforeAfter(bundle: AuditBundle): BeforeAfter {
        val previousId = AuditComparison.previousId(bundle.meta, audits.value)
        val previous = previousId?.let { graph.repository.load(it) }
        return BeforeAfter(AuditComparison.compare(bundle, previous), AuditComparison.update(bundle, previous), previous?.meta?.createdAtMs)
    }

    /** Opens Android's share sheet with [text]; never sends anything by itself. */
    fun shareText(subject: String, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching { ctx.startActivity(Intent.createChooser(send, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { _toast.value = "No sharing app is available." }
    }

    private val citationCache = java.util.concurrent.ConcurrentHashMap<String, List<Passage>>()

    /** Legal citations may run the on-device embedding model, so never call this on the main thread. */
    suspend fun citationsFor(f: Finding): List<Passage> = citationCache[f.checkId.name + f.explanation.hashCode()]
        ?: withContext(Dispatchers.Default) { graph.citations().cite(f, 3) }.also { citationCache[f.checkId.name + f.explanation.hashCode()] = it }

    /** The bundled legal lenses (profiles, check mapping and passages). */
    val legal: LegalLens get() = LegalLens.default

    /** The legal lens in use: the Settings choice, else the device region, else the global baseline. */
    fun lensChoice(setting: String = settings.value.legalProfile): LensChoice =
        LegalLensSetting.resolve(setting, java.util.Locale.getDefault().country)

    fun lensProfile(): LegalProfile = lensChoice().profile

    /** Legal text for a finding under [profile]: India keeps its DPDP citations; other lenses show their mapped passages. */
    suspend fun lensPassagesFor(f: Finding, profile: LegalProfile): List<LensPassage> =
        if (profile == LegalProfile.INDIA) citationsFor(f).map { it.asLensPassage() } else legal.passages(profile, f.checkId.code)

    /** Network events of a session, classified and placed before/after the consent choice, for the timeline. */
    fun timeline(session: AuditSession): List<NetEvent> {
        return EvidenceUi(graph.catalog(), graph.corpus).timeline(session)
    }

    fun classify(event: NetEvent): NetEvent = EvidenceUi(graph.catalog(), graph.corpus).classify(event)

    fun findingAliases(finding: Finding, bundle: AuditBundle): List<String> =
        EvidenceUi(graph.catalog(), graph.corpus).aliasesFor(finding, bundle.sessions)

    fun rightsNotice(bundle: AuditBundle, check: CheckId): RightsNotice? =
        EvidenceUi(graph.catalog(), graph.corpus).notice(check, bundle.result, bundle.sessions)

    /** Opens Android's chooser only after the user reviews the draft; never sends directly. */
    fun shareNotice(notice: RightsNotice) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "TraceVeil observations for ${notice.appLabel}")
            putExtra(Intent.EXTRA_TEXT, notice.text)
        }
        runCatching { ctx.startActivity(Intent.createChooser(send, "Share reviewed notice").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { _toast.value = "No sharing app is available. You can copy the draft instead." }
    }

    /** Always ends with an explanation: the AI's (labelled AI-inferred) or a rules-based fallback that says why. */
    fun explain(finding: Finding, hindi: Boolean) = viewModelScope.launch {
        val key = "${finding.checkId}-${if (hindi) "hi" else "en"}"
        _explaining.value = key
        val passages = citationsFor(finding)
        val explanation = if (!graph.llm.isReady && !graph.llm.load(BackendKind.GPU)) {
            val reason = when (val s = graph.llm.status.value) {
                LlmStatus.NoModel -> "no on-device model is installed (see AI lab)"
                is LlmStatus.Failed -> "the on-device model could not load (${s.message.take(80)})"
                else -> "the on-device model is not available (see AI lab)"
            }
            Explanations.fallback(finding, hindi, reason)
        } else {
            val model = (graph.llm.status.value as? LlmStatus.Ready)?.let { "${it.model} on ${it.backend.label}" } ?: "the on-device model"
            Explanations.fromModel(graph.explainer.explain(finding, passages, hindi), finding, passages, hindi, model)
        }
        _explanations.update { it + (key to explanation) }
        _explaining.value = null
    }

    fun markFirstParty(host: String) {
        val domain = host.split('.').takeLast(2).joinToString(".")
        graph.settings.update { it.copy(firstPartyDomains = it.firstPartyDomains + domain) }
        _toast.value = "$domain marked as your own domain"
    }

    fun loadModel(kind: BackendKind) = viewModelScope.launch {
        graph.llm.load(kind)
        refreshPreflight()
    }

    fun unloadModel() = viewModelScope.launch { graph.llm.unload(); refreshPreflight() }

    fun runBench(kind: BackendKind) = viewModelScope.launch {
        _benchRunning.value = kind
        val r = graph.llm.benchmark(kind)
        _bench.update { list -> list.filterNot { it.backend == kind } + r }
        _benchRunning.value = null
        refreshPreflight()
    }

    fun thermalHeadroom() = graph.llm.thermalHeadroom()

    fun cameraCheck(bitmap: Bitmap) = viewModelScope.launch {
        _cameraResult.value = CameraCheckState.Working
        if (!graph.llm.isReady && !graph.llm.load(BackendKind.GPU)) {
            _cameraResult.value = CameraCheckState.Error("The on-device model is not loaded. Push a Gemma model and open AI lab.")
            return@launch
        }
        if (!graph.llm.canReadImages) {
            _cameraResult.value = CameraCheckState.Error("The loaded model reads text only, so the photo was not sent to it. Load the GPU model (Gemma 4) in AI lab for Camera Check.")
            return@launch
        }
        val file = File(ctx.cacheDir, "camera_check.jpg")
        withContext(Dispatchers.IO) { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) } }
        val audit = graph.llm.auditConsentScreen(file, "(photo of a screen; no accessibility tree)")
        _cameraResult.value = audit?.let { CameraCheckState.Done(it, file.absolutePath) }
            ?: CameraCheckState.Error("Could not read a consent screen in that photo. Hold the phone closer and keep the whole dialog in frame.")
    }

    fun resetCamera() { _cameraResult.value = CameraCheckState.Idle }

    fun exportPdf(bundle: AuditBundle, share: Boolean) = viewModelScope.launch {
        val md = withContext(Dispatchers.IO) { markdown(bundle) }
        val pdf = withContext(Dispatchers.IO) { PdfExporter.render(md, File(bundle.dir, "TraceVeil-${bundle.meta.targetLabel.filter { it.isLetterOrDigit() }}.pdf")) }
        if (share) ExportManager.share(ctx, pdf, "application/pdf")
        else {
            val ok = withContext(Dispatchers.IO) { ExportManager.saveToDownloads(ctx, pdf, pdf.name, "application/pdf") }
            _toast.value = if (ok) "Saved to Downloads/TraceVeil" else "Could not save the PDF"
        }
    }

    fun saveMarkdown(bundle: AuditBundle) = viewModelScope.launch {
        val f = withContext(Dispatchers.IO) {
            File(bundle.dir, "TraceVeil-${bundle.meta.id}-export.md").apply { writeText(markdown(bundle)) }
        }
        val ok = withContext(Dispatchers.IO) { ExportManager.saveToDownloads(ctx, f, "TraceVeil-${bundle.meta.id}.md", "text/markdown") }
        _toast.value = if (ok) "Markdown saved to Downloads/TraceVeil" else "Could not save the report"
    }

    /** Offline single-file HTML (inline CSS/JS, no network requests) to open in any browser, e.g. on the laptop. */
    fun exportHtml(bundle: AuditBundle, share: Boolean) = viewModelScope.launch {
        val file = runCatching {
            val html = withContext(Dispatchers.Default) {
                HtmlExporter.build(
                    bundle.result, bundle.sessions, bundle.meta.timingProfile, bundle.meta.sample, fixesFor(bundle), graph.citations(), graph.catalog(),
                    profile = lensProfile(),
                )
            }
            withContext(Dispatchers.IO) { HtmlExporter.write(html, File(bundle.dir, "TraceVeil-${bundle.meta.targetLabel.filter { it.isLetterOrDigit() }}.html")) }
        }.getOrElse { _toast.value = "Could not build the HTML report"; return@launch }
        if (share) ExportManager.share(ctx, file, HtmlExporter.MIME)
        else {
            val ok = withContext(Dispatchers.IO) { ExportManager.saveToDownloads(ctx, file, file.name, HtmlExporter.MIME) }
            _toast.value = if (ok) "Interactive report saved to Downloads/TraceVeil" else "Could not save the HTML report"
        }
    }

    /**
     * Audit data as a traceveil.audit.v1 JSON bundle for TraceVeil CI; it leaves the phone only when saved or shared. The
     * file's exact bytes are also signed with this install's hardware key (<file>.sig.json next to it) when the phone
     * can; otherwise the export goes ahead unsigned and says so.
     */
    fun exportAuditJson(bundle: AuditBundle, share: Boolean) = viewModelScope.launch {
        val groups = withContext(Dispatchers.Default) { sdkGroups(bundle) }
        val label = withContext(Dispatchers.Default) { playLabelExport(bundle, groups) }
        val file = runCatching {
            withContext(Dispatchers.IO) {
                AuditJsonExport.write(bundle, BuildConfig.VERSION_NAME, lensProfile().name, SdkAttributionExport(groups = groups), playLabel = label)
            }
        }
            .getOrElse { _toast.value = "Could not build the audit data export"; return@launch }
        val signing = withContext(Dispatchers.IO) { EvidenceFiles.signNextTo(file, evidenceKey, BuildConfig.VERSION_NAME) }
        _evidenceLine.value = bundle.meta.id to signing.line
        val sig = (signing as? EvidenceSigning.Signed)?.file
        if (share) ExportManager.share(ctx, listOfNotNull(file, sig), AuditJsonExport.MIME)
        else {
            val saved = withContext(Dispatchers.IO) { ExportManager.saveToDownloads(ctx, file, file.name, AuditJsonExport.MIME) }
            val sigSaved = sig?.let { withContext(Dispatchers.IO) { ExportManager.saveToDownloads(ctx, it, it.name, EvidenceSignatures.MIME) } }
            _toast.value = when {
                !saved -> "Could not save the audit data"
                sigSaved == false -> "Audit data (JSON) saved to Downloads/TraceVeil, but its signature file could not be saved"
                else -> "Audit data (JSON) saved to Downloads/TraceVeil · ${signing.line}"
            }
        }
    }

    /**
     * The Markdown (and PDF) report under the selected legal lens, built from the saved result like the HTML export. The
     * report saved at audit time is the fallback if that fails. Call off the main thread: citations may use the
     * on-device embedding model.
     */
    fun markdown(bundle: AuditBundle, profile: LegalProfile = lensProfile()): String {
        val md = runCatching {
            val built = MarkdownReport.build(bundle.result, bundle.sessions, fixesFor(bundle), graph.citations(), profile)
            if (bundle.meta.sample) SampleReport.annotate(built, bundle.sessions) else built
        }.getOrElse { graph.repository.markdownFile(bundle.meta.id).takeIf { it.exists() }?.readText().orEmpty() }
        // "Play label vs what we saw", when a label is attached: its own section, separate from the findings and score.
        val withLabel = bundle.playLabel?.let { rec ->
            runCatching { PlayLabelReport.insertInto(md, PlayLabelReport.markdown(rec, playLabelCheck(rec, sdkGroups(bundle)))) }.getOrNull()
        } ?: md
        return EvidenceReport.annotate(withLabel, bundle.sessions, bundle.meta.timingProfile, sample = bundle.meta.sample)
    }

    fun copy(label: String, text: String) {
        ExportManager.copy(ctx, label, text)
        _toast.value = "Copied to the clipboard"
    }

    fun updateSettings(transform: (com.dpdpxray.app.data.XraySettings) -> com.dpdpxray.app.data.XraySettings) = graph.settings.update(transform)
}

/** Largest text file accepted as a Play label (a label is a few hundred characters). */
private const val MAX_LABEL_FILE_BYTES = 256L * 1024

/** "Back up my checks" / "Restore from a backup" progress, shown on the card that started it. */
sealed interface BackupUi {
    data object Idle : BackupUi
    data class Working(val message: String) : BackupUi
    data class Done(val message: String) : BackupUi
    data class Failed(val message: String) : BackupUi
}

sealed interface CameraCheckState {
    data object Idle : CameraCheckState
    data object Working : CameraCheckState
    data class Done(val audit: ScreenAudit, val imagePath: String) : CameraCheckState
    data class Error(val message: String) : CameraCheckState
}
