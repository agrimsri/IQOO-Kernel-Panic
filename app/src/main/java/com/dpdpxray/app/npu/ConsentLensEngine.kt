package com.dpdpxray.app.npu

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.dpdpxray.core.consentlens.ConsentLensDecoder
import com.dpdpxray.core.consentlens.ConsentLensText
import com.dpdpxray.core.consentlens.ConsentRoleClassifier
import com.dpdpxray.core.consentlens.NpuBackendPolicy
import com.dpdpxray.core.consentlens.RolePrediction
import com.qualcomm.qti.QnnDelegate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * ConsentLens-1 on this phone: a 146 KB (145,816-byte) full-INT8 TFLite model (assets/consentlens) run by LiteRT.
 *
 * Load order: the Qualcomm QNN delegate on the Hexagon HTP (NPU) when [NpuBackendPolicy] allows it for this SoC,
 * otherwise, or if the delegate fails, LiteRT on the CPU with XNNPACK. The reason for any fallback is kept and shown.
 * [Status.fullyDelegated] comes from the interpreter's execution plan: with XNNPACK disabled on the NPU interpreter, a
 * plan of exactly one node means the QNN delegate took the whole graph, so no op ran on the CPU.
 */
class ConsentLensEngine(private val context: Context) : ConsentRoleClassifier {
    enum class Backend { NPU, CPU }

    data class Status(
        val backend: Backend,
        val label: String,
        val socModel: String,
        val htpArch: String?,
        val policyReason: String,
        val fallbackReason: String?,
        val planNodes: Int?,
        val modelOps: Int,
        val fullyDelegated: Boolean?,
        val loadMs: Long,
        val qnnVersion: String?,
        val htpQuantCapability: Boolean?,
    )

    data class Timing(val backend: String, val runs: Int, val medianMs: Double, val p95Ms: Double, val meanMs: Double)

    data class BenchReport(val active: Timing?, val cpu: Timing, val npuCpuAgree: Int?, val matchesReference: Int, val samples: Int)

    data class Sample(val demo: Meta.Demo, val prediction: RolePrediction)

    @Serializable
    data class Meta(
        @SerialName("model_id") val modelId: String,
        val file: String,
        val sha256: String,
        val bytes: Int,
        val roles: List<String>,
        @SerialName("max_bytes") val maxBytes: Int,
        val output: Output,
        @SerialName("tflite_ops") val ops: List<String>,
        @SerialName("op_count") val opCount: Int,
        val params: Int,
        @SerialName("train_rows") val trainRows: Int,
        val languages: Int,
        @SerialName("default_threshold") val defaultThreshold: Double,
        @SerialName("held_out_language_macro_f1_int8") val heldOutMacroF1: Double,
        @SerialName("held_out_language_macro_f1_lexicon") val heldOutLexiconMacroF1: Double,
        val demo: List<Demo>,
    ) {
        @Serializable
        data class Output(val scale: Float, @SerialName("zero_point") val zeroPoint: Int)

        @Serializable
        data class Demo(
            val lang: String,
            val text: String,
            val expected: String,
            @SerialName("in_training_data") val inTrainingData: Boolean,
            @SerialName("reference_role") val referenceRole: String,
            @SerialName("reference_confidence") val referenceConfidence: Double,
        )
    }

    val meta: Meta by lazy {
        val text = context.assets.open("$ASSET_DIR/consentlens_meta.json").bufferedReader().use { it.readText() }
        Json { ignoreUnknownKeys = true }.decodeFromString(Meta.serializer(), text)
    }
    private val model: ByteBuffer by lazy {
        val bytes = context.assets.open("$ASSET_DIR/${meta.file}").use { it.readBytes() }
        ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
    }
    private val prefs by lazy { context.getSharedPreferences("consentlens", Context.MODE_PRIVATE) }
    private val input = ByteBuffer.allocateDirect(4 * ConsentLensText.MAX_BYTES).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(OUTPUTS).order(ByteOrder.nativeOrder())

    private var interpreter: Interpreter? = null
    private var delegate: QnnDelegate? = null

    @Volatile var status: Status? = null
        private set

    override val modelId: String = ConsentLensText.MODEL_ID
    override val backendLabel: String? get() = status?.label

    /**
     * Loads once (or again after [close]); [retryNpu] clears the crash guard left by an unfinished NPU start.
     * Audits call it non-[interactive]: they use the NPU only after it has started successfully once on this phone
     * for this model (AI lab Load), so a first native NPU start never happens in the middle of an audit.
     */
    @Synchronized
    fun load(retryNpu: Boolean = false, interactive: Boolean = false): Status {
        status?.let { cur ->
            val upgrade = interactive && cur.backend == Backend.CPU && cur.fallbackReason == NOT_VERIFIED
            if (!retryNpu && !upgrade) return cur
        }
        close()
        val t0 = SystemClock.elapsedRealtime()
        if (retryNpu) prefs.edit().putBoolean(KEY_NPU_PENDING, false).commit()
        val libDir = context.applicationInfo.nativeLibraryDir
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null
        val decision = NpuBackendPolicy.decide(
            NpuBackendPolicy.Environment(
                sdkInt = Build.VERSION.SDK_INT, socModel = soc, supportedAbis = Build.SUPPORTED_ABIS.toList(),
                nativeLibraries = File(libDir).list()?.toSet().orEmpty(),
                previousNpuStartUnfinished = prefs.getBoolean(KEY_NPU_PENDING, false),
            ),
        )
        var fallback: String? = null
        var capability: Boolean? = null
        var qnnVersion: String? = null
        val verified = prefs.getString(KEY_NPU_VERIFIED, null) == meta.sha256
        if (decision.tryNpu && !interactive && !verified) {
            fallback = NOT_VERIFIED
        } else if (decision.tryNpu) {
            // Crash guard: written synchronously before native code runs, cleared only when the NPU start completes.
            prefs.edit().putBoolean(KEY_NPU_PENDING, true).commit()
            var d: QnnDelegate? = null
            var i: Interpreter? = null
            try {
                capability = runCatching { QnnDelegate.checkCapability(QnnDelegate.Capability.HTP_RUNTIME_QUANTIZED) }.getOrNull()
                val options = QnnDelegate.Options().apply {
                    setBackendType(QnnDelegate.Options.BackendType.HTP_BACKEND)
                    setSkelLibraryDir(libDir)
                    setHtpPrecision(QnnDelegate.Options.HtpPrecision.HTP_PRECISION_QUANTIZED)
                    setHtpPerformanceMode(QnnDelegate.Options.HtpPerformanceMode.HTP_PERFORMANCE_BURST)
                    setLogLevel(QnnDelegate.Options.LogLevel.LOG_LEVEL_WARN)
                }
                val qnn = QnnDelegate(options)
                d = qnn
                qnnVersion = runCatching { QnnDelegate.getVersion().joinToString(".") }.getOrNull()
                val npu = Interpreter(model, Interpreter.Options().addDelegate(qnn).setUseXNNPACK(false).setNumThreads(1))
                i = npu
                val plan = planLength(npu)
                check(plan == null || plan < meta.opCount) { "the QNN delegate accepted none of the ${meta.opCount} ops" }
                infer(npu, ConsentLensText.encode("Accept all")) // first inference on the HTP before declaring success
                interpreter = npu
                delegate = qnn
                prefs.edit().putString(KEY_NPU_VERIFIED, meta.sha256).apply()
                status = Status(
                    Backend.NPU, "NPU (QNN HTP ${decision.htpArch})", soc.orEmpty(), decision.htpArch, decision.reason, null,
                    plan, meta.opCount, plan?.let { it == 1 }, SystemClock.elapsedRealtime() - t0, qnnVersion, capability,
                )
            } catch (t: Throwable) {
                fallback = "NPU start failed (${t.javaClass.simpleName}: ${t.message ?: "no message"}); using CPU"
                runCatching { i?.close() }
                runCatching { d?.close() }
                interpreter = null
                delegate = null
            } finally {
                prefs.edit().putBoolean(KEY_NPU_PENDING, false).commit()
            }
        } else {
            fallback = decision.reason
        }
        if (interpreter == null) {
            val i = Interpreter(model, Interpreter.Options().setNumThreads(CPU_THREADS).setUseXNNPACK(true))
            interpreter = i
            status = Status(
                Backend.CPU, "CPU (XNNPACK, $CPU_THREADS threads)", soc ?: Build.HARDWARE, decision.htpArch, decision.reason,
                fallback, planLength(i), meta.opCount, null, SystemClock.elapsedRealtime() - t0, qnnVersion, capability,
            )
        }
        val s = status!!
        Log.i(
            TAG,
            "$modelId load backend=${s.backend} label=\"${s.label}\" soc=${s.socModel} htp=${s.htpArch ?: "-"} qnn=${s.qnnVersion ?: "-"} " +
                "htpQuantCapability=${s.htpQuantCapability ?: "-"} planNodes=${s.planNodes ?: "?"}/${s.modelOps} " +
                "fullyDelegated=${s.fullyDelegated ?: "-"} loadMs=${s.loadMs} fallback=\"${s.fallbackReason ?: "none"}\" sha256=${meta.sha256.take(12)}",
        )
        return s
    }

    @Synchronized
    override fun classify(texts: List<String>): List<RolePrediction> {
        val i = interpreter ?: run { load(); interpreter!! }
        return texts.map { t -> decode(t, infer(i, ConsentLensText.encode(t))) }
    }

    /** Fixed demo phrases with their Python INT8 reference prediction, run on the active backend. */
    @Synchronized
    fun samples(): List<Sample> {
        load(interactive = true)
        val preds = classify(meta.demo.map { it.text })
        return meta.demo.zip(preds).map { (d, p) -> Sample(d, p) }
    }

    /**
     * Median and p95 of one inference (Interpreter.run, input already encoded) over [runs] runs on the active backend,
     * and the same on a separate CPU/XNNPACK interpreter, cycling through the demo phrases. Also counts how many demo
     * phrases get the same role on both, and how many match the Python INT8 reference.
     */
    @Synchronized
    fun benchmark(runs: Int = 1000): BenchReport {
        val s = load(interactive = true)
        val active = interpreter!!
        val inputs = meta.demo.map { ConsentLensText.encode(it.text) }
        val activeRoles = inputs.map { decode("", infer(active, it)).role.name }
        val activeTiming = if (s.backend == Backend.NPU) time(active, inputs, runs, "NPU") else null
        val cpu = Interpreter(model, Interpreter.Options().setNumThreads(CPU_THREADS).setUseXNNPACK(true))
        val cpuTiming: Timing
        val cpuRoles: List<String>
        try {
            cpuRoles = inputs.map { decode("", infer(cpu, it)).role.name }
            cpuTiming = time(cpu, inputs, runs, "CPU")
        } finally {
            cpu.close()
        }
        val agree = if (s.backend == Backend.NPU) activeRoles.zip(cpuRoles).count { (a, b) -> a == b } else null
        val reference = activeRoles.zip(meta.demo).count { (r, d) -> r == d.referenceRole }
        val report = BenchReport(activeTiming, cpuTiming, agree, reference, inputs.size)
        Log.i(
            TAG,
            "$modelId bench runs=$runs backend=${s.backend} fullyDelegated=${s.fullyDelegated ?: "-"} planNodes=${s.planNodes ?: "?"}/${s.modelOps} " +
                (activeTiming?.let { "npu.medianMs=${fmt(it.medianMs)} npu.p95Ms=${fmt(it.p95Ms)} " } ?: "npu=not-running ") +
                "cpu.medianMs=${fmt(cpuTiming.medianMs)} cpu.p95Ms=${fmt(cpuTiming.p95Ms)} " +
                "npuCpuAgree=${agree?.let { "$it/${inputs.size}" } ?: "-"} matchesPythonInt8=$reference/${inputs.size}",
        )
        return report
    }

    @Synchronized
    fun close() {
        interpreter?.close(); interpreter = null
        delegate?.close(); delegate = null
        status = null
    }

    private fun time(i: Interpreter, inputs: List<IntArray>, runs: Int, label: String): Timing {
        repeat(WARMUP) { infer(i, inputs[it % inputs.size]) }
        val ns = LongArray(runs)
        for (k in 0 until runs) {
            fill(inputs[k % inputs.size])
            output.rewind()
            val t = System.nanoTime()
            i.run(input, output)
            ns[k] = System.nanoTime() - t
        }
        ns.sort()
        fun ms(v: Long) = v / 1e6
        return Timing(label, runs, ms(ns[runs / 2]), ms(ns[((runs - 1) * 95) / 100]), ns.average() / 1e6)
    }

    private fun fill(ids: IntArray) {
        input.rewind()
        for (v in ids) input.putInt(v)
        input.rewind()
    }

    private fun infer(i: Interpreter, ids: IntArray): ByteArray {
        fill(ids)
        output.rewind()
        i.run(input, output)
        output.rewind()
        return ByteArray(OUTPUTS).also { output.get(it) }
    }

    private fun decode(text: String, q: ByteArray) =
        ConsentLensDecoder.decode(text, ConsentLensDecoder.dequantize(q, meta.output.scale, meta.output.zeroPoint))

    /** Execution-plan length after delegation (LiteRT keeps the getter package-private, hence reflection). */
    private fun planLength(i: Interpreter): Int? = runCatching {
        val m = Class.forName("org.tensorflow.lite.InterpreterImpl").getDeclaredMethod("getExecutionPlanLength")
        m.isAccessible = true
        m.invoke(i) as Int
    }.getOrNull()

    private fun fmt(v: Double) = String.format(Locale.ROOT, "%.3f", v)

    companion object {
        const val TAG = "TraceVeilNpu"
        const val ASSET_DIR = "consentlens"
        private const val OUTPUTS = 7
        private const val CPU_THREADS = 2
        private const val WARMUP = 20
        private const val KEY_NPU_PENDING = "npu_start_pending"
        private const val KEY_NPU_VERIFIED = "npu_verified_model_sha256"
        const val NOT_VERIFIED = "the NPU has not yet started on this phone for this model; open AI lab → ConsentLens → Load once"
    }
}
