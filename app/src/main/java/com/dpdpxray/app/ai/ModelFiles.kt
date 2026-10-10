package com.dpdpxray.app.ai

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Models are pushed to the app's external files dir (no root needed):
 *   adb push gemma-4-E2B-it.litertlm /sdcard/Android/data/com.dpdpxray.app/files/models/
 * Selection rules live in [ModelSelection] so they can be unit-tested without a phone.
 */
object ModelFiles {
    fun dir(context: Context): File = File(context.getExternalFilesDir(null), "models").apply { mkdirs() }

    private fun all(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && (f.name.endsWith(".litertlm") || f.name.endsWith(".tflite")) }
            ?.toList().orEmpty()

    fun hasAnyModel(context: Context): Boolean = all(context).isNotEmpty()

    /** The main multimodal LLM (prefers Gemma 4 E2B, then any Gemma 3n/Gemma .litertlm that is not an embedder or NPU bundle). */
    fun mainModel(context: Context): File? = ModelSelection.mainModel(all(context).map { it.name })?.let { File(dir(context), it) }

    /** The NPU bundle compiled for THIS phone's SoC, or why there is none. Never the GPU bundle, never another chip's bundle. */
    fun npuChoice(context: Context): ModelSelection.NpuChoice = ModelSelection.npuModel(all(context).map { it.name }, deviceSoc())

    fun npuModel(context: Context): File? =
        (npuChoice(context) as? ModelSelection.NpuChoice.Found)?.let { File(dir(context), it.name) }

    fun embeddingModel(context: Context): File? = all(context).firstOrNull { it.name.contains("embed", ignoreCase = true) }

    /** Build.SOC_MODEL (e.g. "SM8850") exists from Android 12; older phones report nothing, so NPU selection fails closed. */
    fun deviceSoc(): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null

    fun describe(context: Context): String =
        "Models folder: ${dir(context).absolutePath}\n" +
            (all(context).joinToString("\n") { "• ${it.name} (${it.length() / (1024 * 1024)} MB)" }.ifEmpty { "• (empty)" })
}
