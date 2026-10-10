package com.dpdpxray.core.consentlens

/**
 * Decides whether ConsentLens may try the Qualcomm Hexagon NPU (QNN HTP delegate) or must use the CPU, and why.
 * Pure logic so the fallback rules are unit-tested; the Android engine supplies the facts.
 *
 * The NPU is attempted only on Snapdragon SoCs whose HTP version has matching QNN skel/stub libraries in the APK.
 * Every refusal names its reason, and the engine shows and logs it; a CPU fallback is never labelled NPU.
 */
object NpuBackendPolicy {
    /** Build.SOC_MODEL to Hexagon HTP architecture, for the SoCs whose libraries the APK packages. */
    val HTP_ARCH: Map<String, String> = linkedMapOf(
        "SM8850" to "V81", // Snapdragon 8 Elite Gen 5
        "SM8750" to "V79", // Snapdragon 8 Elite
        "SM8650" to "V75", // Snapdragon 8 Gen 3
        "SM8550" to "V73", // Snapdragon 8 Gen 2
        "SM8475" to "V69", // Snapdragon 8+ Gen 1
        "SM8450" to "V69", // Snapdragon 8 Gen 1
    )

    /** Libraries the QNN TFLite delegate needs for [arch] (file names as packaged in the APK's lib/arm64-v8a). */
    fun requiredLibraries(arch: String): List<String> = listOf(
        "libQnnTFLiteDelegate.so", "libqnn_delegate_jni.so", "libQnnHtp.so", "libQnnHtpPrepare.so",
        "libQnnHtp${arch}Stub.so", "libQnnHtp${arch}Skel.so",
    )

    data class Environment(
        val sdkInt: Int,
        /** Build.SOC_MODEL, or null below Android 12 where it does not exist. */
        val socModel: String?,
        val supportedAbis: List<String>,
        /** File names present in the app's native library directory. */
        val nativeLibraries: Set<String>,
        /** An earlier NPU start in this install never finished (e.g. a native crash); stays on CPU until retried. */
        val previousNpuStartUnfinished: Boolean = false,
        val npuDisabledByUser: Boolean = false,
    )

    data class Decision(val tryNpu: Boolean, val htpArch: String?, val reason: String)

    fun decide(env: Environment): Decision {
        if (env.npuDisabledByUser) return cpu("NPU switched off in AI lab")
        if (env.sdkInt < 31 || env.socModel.isNullOrBlank()) return cpu("Android 12+ is needed to read the SoC model (Build.SOC_MODEL)")
        if ("arm64-v8a" !in env.supportedAbis) return cpu("not an arm64-v8a device")
        val soc = env.socModel.trim().uppercase()
        val arch = HTP_ARCH[soc] ?: return cpu("SoC $soc is not on the Hexagon HTP allow-list (${HTP_ARCH.keys.joinToString()})")
        val missing = requiredLibraries(arch).filterNot { it in env.nativeLibraries }
        if (missing.isNotEmpty()) return cpu("QNN libraries for HTP $arch are not in this APK: ${missing.joinToString()}")
        if (env.previousNpuStartUnfinished) return cpu("a previous NPU start did not finish (possible native crash); retry from AI lab")
        return Decision(true, arch, "SoC $soc → Hexagon HTP $arch; QNN libraries present")
    }

    private fun cpu(reason: String) = Decision(false, null, reason)
}
