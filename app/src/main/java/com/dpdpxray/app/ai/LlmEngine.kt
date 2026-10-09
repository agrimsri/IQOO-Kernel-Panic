package com.dpdpxray.app.ai

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.dpdpxray.core.model.ScreenAudit
import com.dpdpxray.core.screen.ConsentAuditPrompt
import com.dpdpxray.core.screen.VlmResponseParser
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.benchmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

enum class BackendKind(val label: String) { GPU("GPU (Adreno)"), NPU("NPU (Hexagon)"), CPU("CPU") }

sealed interface LlmStatus {
    data object NoModel : LlmStatus
    data object NotLoaded : LlmStatus
    data class Loading(val backend: BackendKind) : LlmStatus
    data class Ready(val model: String, val backend: BackendKind, val loadMs: Long) : LlmStatus
    data class Failed(val message: String) : LlmStatus
}

data class BenchResult(
    val backend: BackendKind,
    val ok: Boolean,
    val initSeconds: Double = 0.0,
    val ttftSeconds: Double = 0.0,
    val prefillTps: Double = 0.0,
    val decodeTps: Double = 0.0,
    val error: String? = null,
)

/** The on-device brain: Gemma via LiteRT-LM. All calls are serialised and run off the main thread. */
class LlmEngine(private val context: Context) {
    private val mutex = Mutex()
    private var engine: Engine? = null
    private var modelName: String? = null
    private var backendKind: BackendKind? = null

    private val _status = MutableStateFlow<LlmStatus>(LlmStatus.NotLoaded)
    val status: StateFlow<LlmStatus> = _status.asStateFlow()

    fun refreshAvailability() {
        if (engine == null) _status.value = if (ModelFiles.mainModel(context) == null) LlmStatus.NoModel else LlmStatus.NotLoaded
    }

    val isReady: Boolean get() = engine != null

    suspend fun load(kind: BackendKind = BackendKind.GPU): Boolean = mutex.withLock {
        withContext(Dispatchers.Default) {
            val file = (if (kind == BackendKind.NPU) ModelFiles.npuModel(context) else null) ?: ModelFiles.mainModel(context)
            if (file == null) {
                _status.value = LlmStatus.NoModel
                return@withContext false
            }
            unloadLocked()
            _status.value = LlmStatus.Loading(kind)
            val start = SystemClock.elapsedRealtime()
            val result = runCatching {
                Engine(
                    EngineConfig(
                        modelPath = file.absolutePath,
                        backend = backendFor(kind),
                        visionBackend = if (kind == BackendKind.CPU) Backend.CPU() else Backend.GPU(),
                        audioBackend = Backend.CPU(),
                        maxNumTokens = 4096,
                        cacheDir = context.cacheDir.path,
                    ),
                ).also { it.initialize() }
            }
            result.onSuccess {
                engine = it
                modelName = file.name
                backendKind = kind
                _status.value = LlmStatus.Ready(file.name, kind, SystemClock.elapsedRealtime() - start)
            }.onFailure {
                _status.value = LlmStatus.Failed("${kind.label}: ${it.message ?: it.javaClass.simpleName}")
            }
            result.isSuccess
        }
    }

    suspend fun unload() = mutex.withLock { withContext(Dispatchers.Default) { unloadLocked() } }

    private fun unloadLocked() {
        runCatching { engine?.close() }
        engine = null
        _status.value = if (ModelFiles.mainModel(context) == null) LlmStatus.NoModel else LlmStatus.NotLoaded
    }

    /** Reviews a consent screen (screenshot + accessibility text) with constrained JSON output. */
    suspend fun auditConsentScreen(screenshot: File?, treeText: String): ScreenAudit? = mutex.withLock {
        withContext(Dispatchers.Default) {
            val e = engine ?: return@withContext null
            if (thermallyStressed()) Thread.sleep(1500)
            val start = SystemClock.elapsedRealtime()
            val parts = buildList {
                if (screenshot != null && screenshot.exists()) add(Content.ImageFile(screenshot.absolutePath))
                add(Content.Text(ConsentAuditPrompt.userMessage(treeText)))
            }
            val constrained = llmCatching { ask(e, ConsentAuditPrompt.SYSTEM, Contents.of(parts), AskOptions(constrained = true)) }
            val outcome = if (constrained is LlmOutcome.Text) constrained
            else llmCatching { ask(e, ConsentAuditPrompt.SYSTEM, Contents.of(parts), AskOptions()) }.also { record("screen review (constrained: ${constrained.describe()})", it) }
            val text = outcome.textOrNull ?: return@withContext null
            VlmResponseParser.parse(text)?.copy(modelId = modelName, latencyMs = SystemClock.elapsedRealtime() - start)
                ?: null.also { Log.w(TAG, "screen review: model reply did not parse as a screen audit (${text.length} chars)") }
        }
    }

    /** Free-text generation (finding explanations, Hindi summaries). Null on any failure; see [generateOutcome] for why. */
    suspend fun generate(system: String, prompt: String): String? = generateOutcome(system, prompt).textOrNull

    /** Like [generate], but says what happened: text, an empty reply, or the exception class and message. */
    suspend fun generateOutcome(system: String, prompt: String, options: AskOptions = AskOptions()): LlmOutcome = mutex.withLock {
        withContext(Dispatchers.Default) {
            val e = engine ?: return@withContext LlmOutcome.NotLoaded
            llmCatching { ask(e, system, Contents.of(prompt), options) }.also { record("generate", it) }
        }
    }

    private val _lastProblem = MutableStateFlow<String?>(null)
    /** The most recent failed or empty model call, for AI lab. Never contains prompt or audit text. */
    val lastProblem: StateFlow<String?> = _lastProblem.asStateFlow()

    private fun record(what: String, outcome: LlmOutcome) {
        if (outcome is LlmOutcome.Text) return
        val line = "$what: ${outcome.describe()}"
        _lastProblem.value = line
        Log.w(TAG, line)
    }

    /**
     * A fixed prompt run under each configuration that could explain empty answers, so the failing setting is observed
     * on the phone rather than guessed. Each row records the outcome and the time it took.
     */
    suspend fun diagnose(): List<DiagnosticRow> = mutex.withLock {
        withContext(Dispatchers.Default) {
            val e = engine ?: return@withContext listOf(DiagnosticRow("Model", LlmOutcome.NotLoaded, 0))
            DIAGNOSTICS.map { (label, options) ->
                val start = SystemClock.elapsedRealtime()
                val outcome = llmCatching { ask(e, DIAG_SYSTEM, Contents.of(DIAG_PROMPT), options) }
                DiagnosticRow(label, outcome, SystemClock.elapsedRealtime() - start).also { Log.i(TAG, "diagnose $label: ${outcome.describe()} in ${it.ms} ms") }
            }
        }
    }

    private fun ask(e: Engine, system: String, contents: Contents, options: AskOptions): LlmOutcome {
        val config = ConversationConfig(
            systemInstruction = if (options.systemInstruction) Contents.of(system) else null,
            samplerConfig = if (options.greedySampler) SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 7) else null,
            enableResponseFormat = options.constrained,
            thinkingConfig = if (options.thinkingOff) ThinkingConfig(false) else null,
        )
        return e.createConversation(config).use { conv ->
            val reply: Message = if (options.constrained) {
                conv.sendMessage(contents, responseFormat = ResponseFormat.json(ConsentAuditPrompt.JSON_SCHEMA))
            } else {
                conv.sendMessage(contents)
            }
            LlmOutcome.fromReply(reply.contents.contents.filterIsInstance<Content.Text>().map { it.text }, reply.channels.keys)
        }
    }

    /** Real tokens/sec on each backend using LiteRT-LM's built-in benchmark (the main engine is unloaded first). */
    @OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)
    suspend fun benchmark(kind: BackendKind): BenchResult = mutex.withLock {
        withContext(Dispatchers.Default) {
            val file = (if (kind == BackendKind.NPU) ModelFiles.npuModel(context) else ModelFiles.mainModel(context))
                ?: return@withContext BenchResult(kind, false, error = if (kind == BackendKind.NPU) "No NPU model bundle found" else "No model found")
            unloadLocked()
            runCatching { benchmark(modelPath = file.absolutePath, backend = backendFor(kind)) }
                .fold(
                    onSuccess = { BenchResult(kind, true, it.initTimeInSecond, it.timeToFirstTokenInSecond, it.lastPrefillTokensPerSecond, it.lastDecodeTokensPerSecond) },
                    onFailure = { BenchResult(kind, false, error = it.message ?: it.javaClass.simpleName) },
                )
        }
    }

    val currentBackend: BackendKind? get() = backendKind

    private fun backendFor(kind: BackendKind): Backend = when (kind) {
        BackendKind.GPU -> Backend.GPU()
        BackendKind.CPU -> Backend.CPU()
        BackendKind.NPU -> Backend.NPU(context.applicationInfo.nativeLibraryDir)
    }

    /** Thermal headroom ≥ 0.9 means the device is close to throttling. */
    fun thermalHeadroom(): Float = runCatching {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return Float.NaN
        context.getSystemService(PowerManager::class.java).getThermalHeadroom(10)
    }.getOrDefault(Float.NaN)

    private fun thermallyStressed() = thermalHeadroom().let { !it.isNaN() && it >= 0.9f }

    private companion object {
        const val TAG = "TraceVeilLlm"
        const val DIAG_SYSTEM = "You are a test harness."
        const val DIAG_PROMPT = "Reply with the single word OK."
        val DIAGNOSTICS = listOf(
            "Current settings" to AskOptions(),
            "No system instruction" to AskOptions(systemInstruction = false),
            "Engine default sampler" to AskOptions(greedySampler = false),
            "Thinking off" to AskOptions(thinkingOff = true),
        )
    }
}

/** Conversation settings for one call. The defaults are the settings the app has always used. */
data class AskOptions(
    val systemInstruction: Boolean = true,
    val greedySampler: Boolean = true,
    val constrained: Boolean = false,
    val thinkingOff: Boolean = false,
)

data class DiagnosticRow(val label: String, val outcome: LlmOutcome, val ms: Long)
