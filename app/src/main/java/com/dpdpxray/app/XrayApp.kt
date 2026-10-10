package com.dpdpxray.app

import android.app.Application
import android.content.Context
import com.dpdpxray.app.ai.AiScreenAuditor
import com.dpdpxray.app.ai.EmbeddingRetriever
import com.dpdpxray.app.ai.FindingExplainer
import com.dpdpxray.app.ai.LlmEngine
import com.dpdpxray.app.audit.AuditOrchestrator
import com.dpdpxray.app.data.AuditRepository
import com.dpdpxray.app.data.SettingsStore
import com.dpdpxray.core.dpdp.Bm25Retriever
import com.dpdpxray.core.dpdp.CitationService
import com.dpdpxray.core.dpdp.DpdpCorpus
import com.dpdpxray.core.dpdp.PassageRetriever
import com.dpdpxray.core.fixes.FixCatalog
import com.dpdpxray.core.trackers.TrackerCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class XrayApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        // APK Guard new-install alerts; lives only while this process lives.
        com.dpdpxray.app.apkguard.ApkInstallWatcher.register(this)
    }
}

/** Manual dependency graph: small app, no DI framework needed. */
class AppGraph(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val baseCatalog = TrackerCatalog.loadDefault()
    val fixCatalog = FixCatalog.loadDefault()
    val corpus = DpdpCorpus.loadDefault()
    val settings = SettingsStore(app)
    val repository = AuditRepository(app)
    val llm = LlmEngine(app)
    val screenAuditor = AiScreenAuditor(llm)
    val explainer = FindingExplainer(llm)
    val orchestrator = AuditOrchestrator(app, this)

    @Volatile var retriever: PassageRetriever = Bm25Retriever(corpus.passages)
        private set
    @Volatile var semanticSearch = false
        private set

    private val semanticWarmUp = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        llm.refreshAvailability()
        scope.launch { repository.refresh() }
    }

    /**
     * Builds the optional on-device semantic search once (it embeds the whole corpus on the CPU). Started when the UI
     * opens or citations are first needed, not in [init], so a process started only for a background Auto-guard job
     * does not pay for it. Until it is ready, citations use BM25, as before.
     */
    fun warmUpSemanticSearch() {
        if (!semanticWarmUp.compareAndSet(false, true)) return
        scope.launch {
            EmbeddingRetriever.create(app, corpus)?.let {
                retriever = it
                semanticSearch = true
            }
        }
    }

    fun catalog(): TrackerCatalog = baseCatalog.withFirstParty(settings.settings.value.firstPartyDomains)
    fun citations(): CitationService {
        warmUpSemanticSearch()
        return CitationService(corpus, retriever)
    }
}

val Context.graph: AppGraph get() = (applicationContext as XrayApp).graph
