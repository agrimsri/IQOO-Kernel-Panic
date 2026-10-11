package com.dpdpxray.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.dpdpxray.core.model.ScreenAudit
import com.dpdpxray.core.screen.ConsentAuditPrompt
import com.dpdpxray.core.screen.VlmResponseParser
import com.dpdpxray.core.consent.BlockerAgent
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Capabilities
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.benchmark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    private var modelFile: File? = null
    private var modelName: String? = null
    private var backendKind: BackendKind? = null
    private var capabilities: ModelCapabilities? = null

    private val _status = MutableStateFlow<LlmStatus>(LlmStatus.NotLoaded)
    val status: StateFlow<LlmStatus> = _status.asStateFlow()

    fun refreshAvailability() {
        if (engine == null) _status.value = if (ModelFiles.mainModel(context) == null) LlmStatus.NoModel else LlmStatus.NotLoaded
    }

    val isReady: Boolean get() = engine != null

    /** What the loaded bundle accepts as input; null when nothing is loaded. Images are only sent when this says VISION. */
    val loadedCapabilities: ModelCapabilities? get() = capabilities

    val canReadImages: Boolean get() = capabilities?.canReadImages == true

    /**
     * Loads the model for [kind]. NPU only ever loads a bundle compiled for this phone's SoC and never falls back to the
     * GPU bundle: if no matching bundle exists the status says so and nothing is labelled NPU.
     */
    suspend fun load(kind: BackendKind = BackendKind.GPU): Boolean = inUse {
        withContext(Dispatchers.Default) {
            val file = when (kind) {
                BackendKind.NPU -> when (val choice = ModelFiles.npuChoice(context)) {
                    is ModelSelection.NpuChoice.Found -> File(ModelFiles.dir(context), choice.name)
                    is ModelSelection.NpuChoice.Refused -> {
                        unloadLocked()
                        _status.value = if (!ModelFiles.hasAnyModel(context)) LlmStatus.NoModel
                        else LlmStatus.Failed("${kind.label}: ${choice.reason}. The GPU model was not loaded in its place.")
                        Log.w(TAG, "NPU load refused: ${choice.reason}")
                        return@withContext false
                    }
                }
                else -> ModelFiles.mainModel(context)
            }
            if (file == null) {
                _status.value = LlmStatus.NoModel
                return@withContext false
            }
            unloadLocked()
            _status.value = LlmStatus.Loading(kind)
            var caps = ModelCapabilities.fromFileName(file.name)
            val start = SystemClock.elapsedRealtime()
            var result = runCatching { openChecked(file, kind, caps) }
            // A bundle without an encoder we asked for only fails once a conversation is created; reopen once without it.
            val missing = EncoderPlan.missingEncoder(result.exceptionOrNull()?.message)
            if (missing != null && missing in caps.inputs) {
                Log.w(TAG, "bundle has no ${missing.label} encoder; reopening without it")
                caps = caps.without(missing, "bundle has no ${missing.label} encoder")
                result = runCatching { openChecked(file, kind, caps) }
            }
            result.onSuccess {
                engine = it
                modelFile = file
                modelName = file.name
                backendKind = kind
                capabilities = caps
                _status.value = LlmStatus.Ready(file.name, kind, SystemClock.elapsedRealtime() - start)
            }.onFailure {
                _status.value = LlmStatus.Failed("${kind.label}: ${it.message ?: it.javaClass.simpleName}")
            }
            result.isSuccess
        }
    }

    /** Initialises the engine and creates one throwaway conversation, which is where LiteRT-LM reports missing encoders. */
    private fun openChecked(file: File, kind: BackendKind, caps: ModelCapabilities): Engine {
        val e = Engine(engineConfig(file, kind, caps))
        try {
            e.initialize()
            e.createConversation(conversationConfig(AnswerCheck.SYSTEM, AskOptions())).use { }
            return e
        } catch (t: Throwable) {
            runCatching { e.close() }
            throw t
        }
    }

    /** Only encoders for inputs the app sends and the bundle can read are requested (see [EncoderPlan]). */
    private fun engineConfig(file: File, kind: BackendKind, caps: ModelCapabilities): EngineConfig {
        val encoders = EncoderPlan.of(kind, caps)
        return EngineConfig(
            modelPath = file.absolutePath,
            backend = backendFor(kind),
            visionBackend = encoders.vision?.let(::encoderBackend),
            audioBackend = encoders.audio?.let(::encoderBackend),
            // NPU bundles are compiled for a fixed context (e.g. "ekv1280"); let the bundle decide instead of forcing 4096.
            maxNumTokens = if (kind == BackendKind.NPU) null else 4096,
            cacheDir = context.cacheDir.path,
        )
    }

    private fun encoderBackend(target: EncoderTarget): Backend = when (target) {
        EncoderTarget.GPU -> Backend.GPU()
        EncoderTarget.CPU -> Backend.CPU()
    }

    suspend fun unload() = mutex.withLock { idleJob?.cancel(); withContext(Dispatchers.Default) { unloadLocked() } }

    private val idleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var idleJob: Job? = null

    /** Runs [block] under the engine lock, then restarts the idle timer, so a model nobody uses does not stay in memory. */
    private suspend fun <T> inUse(block: suspend () -> T): T = try { mutex.withLock { block() } } finally { armIdleUnload() }

    /** Closes the model after [IDLE_UNLOAD_MS] without a call; the next explanation or audit loads it again. */
    private fun armIdleUnload() {
        idleJob?.cancel()
        if (engine == null) return
        idleJob = idleScope.launch {
            delay(IDLE_UNLOAD_MS)
            mutex.withLock {
                if (engine != null) {
                    Log.i(TAG, "closing the model after ${IDLE_UNLOAD_MS / 60_000} minutes unused")
                    unloadLocked()
                }
            }
        }
    }

    private fun unloadLocked() {
        runCatching { engine?.close() }
        engine = null
        modelFile = null
        capabilities = null
        backendKind = null
        _status.value = if (ModelFiles.mainModel(context) == null) LlmStatus.NoModel else LlmStatus.NotLoaded
    }

    /**
     * Reviews a consent screen (screenshot + accessibility text) with constrained JSON output. A text-only model is never
     * sent the screenshot (or a camera photo); it is not asked at all and the accessibility-tree rules decide.
     */
    suspend fun auditConsentScreen(screenshot: File?, treeText: String): ScreenAudit? = inUse {
        withContext(Dispatchers.Default) {
            val e = engine ?: return@withContext null
            val plan = CapabilityRouting.screenReview(capabilities, screenshot?.exists() == true)
            if (!plan.callModel) {
                record("screen review", LlmOutcome.Skipped(plan.reason ?: "the loaded model cannot review this input"))
                return@withContext null
            }
            if (thermallyStressed()) Thread.sleep(1500)
            val start = SystemClock.elapsedRealtime()
            val parts = buildList {
                if (plan.attachImage && screenshot != null) add(Content.ImageFile(screenshot.absolutePath))
                add(Content.Text(ConsentAuditPrompt.userMessage(treeText)))
            }
            val constrained = llmCatching {
                ask(e, ConsentAuditPrompt.SYSTEM, Contents.of(parts), AskOptions.screenReview(constrained = true), ConsentAuditPrompt.JSON_SCHEMA)
            }
            val outcome = if (constrained is LlmOutcome.Text) constrained.also { record("screen review", it) }
            else {
                Log.w(TAG, "screen review constrained attempt: ${constrained.describe()}")
                llmCatching { ask(e, ConsentAuditPrompt.SYSTEM, Contents.of(parts), AskOptions.screenReview(constrained = false)) }
                    .also { record("screen review (constrained: ${constrained.describe()})", it) }
            }
            val text = outcome.textOrNull ?: return@withContext null
            VlmResponseParser.parse(text)?.copy(modelId = modelName, latencyMs = SystemClock.elapsedRealtime() - start)
                ?: null.also { Log.w(TAG, "screen review: model reply did not parse as a screen audit (${text.length} chars)") }
        }
    }

    /**
     * One step for a screen between launch and an app's consent screen that no rule covers (see BlockerAgent). Text only: the
     * numbered list of what is on screen is enough, and it keeps the answer quick. The reply is only a proposal; BlockerAgent
     * decides whether it is safe to carry out.
     */
    suspend fun decideBlockerStep(userMessage: String, screenshot: File? = null): String? = mutex.withLock {
        withContext(Dispatchers.Default) {
            val e = engine ?: return@withContext null
            if (thermallyStressed()) Thread.sleep(1500)
            // The screenshot goes along only when the loaded model can read images; a text-only model still gets the list.
            val parts = buildList {
                if (screenshot?.exists() == true && capabilities?.canReadImages == true) add(Content.ImageFile(screenshot.absolutePath))
                add(Content.Text(userMessage))
            }
            val contents = Contents.of(parts)
            val constrained = llmCatching {
                ask(e, BlockerAgent.SYSTEM, contents, AskOptions(constrained = true, maxOutputTokens = 160), BlockerAgent.JSON_SCHEMA)
            }
            val outcome = if (constrained is LlmOutcome.Text) constrained
            else llmCatching { ask(e, BlockerAgent.SYSTEM, contents, AskOptions(maxOutputTokens = 160)) }
            record("blocker step", outcome)
            outcome.textOrNull
        }
    }

    /** Free-text generation (finding explanations, Hindi summaries). Null on any failure; see [generateOutcome] for why. */
    suspend fun generate(system: String, prompt: String): String? = generateOutcome(system, prompt, AskOptions.EXPLANATION).textOrNull

    /** Like [generate], but says what happened: text, an empty reply, or the exception class and message. */
    suspend fun generateOutcome(system: String, prompt: String, options: AskOptions = AskOptions()): LlmOutcome = inUse {
        withContext(Dispatchers.Default) {
            val e = engine ?: return@withContext LlmOutcome.NotLoaded
            llmCatching { ask(e, system, Contents.of(prompt), options) }.also { record("generate", it) }
        }
    }

    private val _lastProblem = MutableStateFlow<String?>(null)
    /** The most recent failed, empty, skipped or channel-sourced model call, for AI lab. Never contains prompt or audit text. */
    val lastProblem: StateFlow<String?> = _lastProblem.asStateFlow()

    private fun record(what: String, outcome: LlmOutcome) {
        if (outcome is LlmOutcome.Text && outcome.source == LlmOutcome.FROM_CONTENTS) return
        val line = "$what: ${outcome.describe()}"
        _lastProblem.value = line
        Log.w(TAG, line)
    }

    /**
     * AI lab "Answer check": fixed, data-free prompts run under the app's settings and under each older setting that could
     * explain empty answers, so the failing setting is observed on the phone rather than guessed.
     */
    suspend fun diagnose(): AnswerCheckReport = inUse {
        withContext(Dispatchers.Default) {
            val e = engine ?: return@withContext AnswerCheckReport(
                listOf(DiagnosticRow("Model", LlmOutcome.NotLoaded, 0, ok = false)), listOf("Load the model first."), "",
            )
            val info = bundleReport()
            val cases = AnswerCheck.cases(canReadImages)
            val results = cases.map { case ->
                val start = SystemClock.elapsedRealtime()
                val contents = if (case.withTestImage) Contents.of(listOf(Content.ImageFile(testCard().absolutePath), Content.Text(case.prompt)))
                else Contents.of(case.prompt)
                val outcome = llmCatching {
                    if (case.options.streaming) askStreaming(e, AnswerCheck.SYSTEM, contents, case.options)
                    else ask(e, AnswerCheck.SYSTEM, contents, case.options, case.jsonSchema)
                }
                val ms = SystemClock.elapsedRealtime() - start
                Log.i(TAG, "answer check ${case.id}: ${outcome.describe()} in $ms ms")
                case to (outcome to ms)
            }
            val rows = results.map { (case, r) ->
                DiagnosticRow(case.label, r.first, r.second, ok = AnswerCheck.isExpected(case, r.first), preview = AnswerCheck.preview(r.first))
            }
            AnswerCheckReport(rows, AnswerCheck.conclude(results.map { (case, r) -> case to r.first }), info)
        }
    }

    /**
     * Reads the bundle's own metadata through LiteRT-LM's Capabilities API (user-triggered only, never on the audit path)
     * and narrows the routing capabilities if the bundle declares fewer inputs than the file name suggested.
     */
    @OptIn(ExperimentalApi::class)
    private fun bundleReport(): String {
        val file = modelFile ?: return ""
        val assumed = capabilities
        val probe = runCatching {
            Capabilities(file.absolutePath).use { c ->
                val m = c.inputModalities()
                val s = c.defaultSamplerParams()
                val bundle = ModelCapabilities.fromBundle(m.text, m.vision, m.audio)
                capabilities = assumed?.narrowTo(bundle)
                "Bundle metadata: inputs ${bundle.inputs.sorted().joinToString { it.label }}; thinking supported: ${c.supportsThinking()}; " +
                    "packaged sampler type ${s.type}, top-k ${s.topK}, top-p ${s.topP}, temperature ${s.temperature}"
            }
        }.getOrElse { "Bundle metadata could not be read (${it.javaClass.simpleName}: ${it.message?.take(120)})" }
        val routing = capabilities?.let { "Routing uses: ${it.describe()}" } ?: ""
        return listOf("Backend: ${backendKind?.label ?: "?"} · ${file.name}", routing, probe).filter { it.isNotBlank() }.joinToString("\n")
    }

    /** A white card with one printed word, generated on the phone, so the image path can be checked without audit data. */
    private fun testCard(): File {
        val f = File(context.cacheDir, "answer_check_card.png")
        if (!f.exists()) {
            val bmp = Bitmap.createBitmap(640, 320, Bitmap.Config.ARGB_8888)
            Canvas(bmp).apply {
                drawColor(Color.WHITE)
                drawText(AnswerCheck.IMAGE_WORD, 320f, 200f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK; textSize = 120f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
                })
            }
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
        }
        return f
    }

    private fun conversationConfig(system: String, options: AskOptions) = ConversationConfig(
        systemInstruction = if (options.systemInstruction) Contents.of(system) else null,
        samplerConfig = samplerConfig(options.sampler),
        maxOutputToken = options.maxOutputTokens,
        thinkingConfig = thinkingConfig(options.thinking),
        enableResponseFormat = options.constrained,
    )

    private fun ask(e: Engine, system: String, contents: Contents, options: AskOptions, jsonSchema: String? = null): LlmOutcome =
        e.createConversation(conversationConfig(system, options)).use { conv ->
            // Thinking and the output bound are sent per message too: LiteRT-LM 0.17.1 accepts both places, so both agree.
            val reply = conv.sendMessage(
                contents,
                maxOutputToken = options.maxOutputTokens,
                thinkingConfig = thinkingConfig(options.thinking),
                responseFormat = if (options.constrained && jsonSchema != null) ResponseFormat.json(jsonSchema) else null,
            )
            reply.toOutcome()
        }

    private suspend fun askStreaming(e: Engine, system: String, contents: Contents, options: AskOptions): LlmOutcome =
        e.createConversation(conversationConfig(system, options)).use { conv ->
            val acc = ReplyAccumulator()
            conv.sendMessageAsync(contents, maxOutputToken = options.maxOutputTokens, thinkingConfig = thinkingConfig(options.thinking))
                .collect { acc.add(it.textParts(), it.channels) }
            acc.outcome()
        }

    /** Real tokens/sec on each backend using LiteRT-LM's built-in benchmark (the main engine is unloaded first). */
    @OptIn(ExperimentalApi::class)
    suspend fun benchmark(kind: BackendKind): BenchResult = inUse {
        withContext(Dispatchers.Default) {
            val file = if (kind == BackendKind.NPU) {
                when (val choice = ModelFiles.npuChoice(context)) {
                    is ModelSelection.NpuChoice.Found -> File(ModelFiles.dir(context), choice.name)
                    is ModelSelection.NpuChoice.Refused -> return@withContext BenchResult(kind, false, error = choice.reason)
                }
            } else {
                ModelFiles.mainModel(context) ?: return@withContext BenchResult(kind, false, error = "No model found")
            }
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
        const val IDLE_UNLOAD_MS = 10 * 60_000L

        fun samplerConfig(choice: SamplerChoice): SamplerConfig? {
            val k = choice.topK ?: return null
            return SamplerConfig(topK = k, topP = choice.topP ?: 1.0, temperature = choice.temperature ?: 1.0, seed = 7)
        }

        fun thinkingConfig(choice: ThinkingChoice): ThinkingConfig? = when (choice) {
            ThinkingChoice.OFF -> ThinkingConfig(enableThinking = false)
            ThinkingChoice.ON -> ThinkingConfig(enableThinking = true)
            ThinkingChoice.ENGINE_DEFAULT -> null
        }
    }
}

data class DiagnosticRow(
    val label: String,
    val outcome: LlmOutcome,
    val ms: Long,
    /** True only when the model answered the fixed prompt as asked. */
    val ok: Boolean = outcome is LlmOutcome.Text,
    /** Start of the answer to the fixed test prompt (never audit data, never thinking text). */
    val preview: String = "",
) {
    val summary: String get() = listOf(outcome.describe(), preview.takeIf { it.isNotBlank() }?.let { "\"$it\"" }, "$ms ms")
        .filterNotNull().joinToString(" · ")
}

data class AnswerCheckReport(val rows: List<DiagnosticRow>, val conclusions: List<String>, val modelInfo: String)
