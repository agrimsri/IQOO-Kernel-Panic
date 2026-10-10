package com.dpdpxray.core.ml

import com.dpdpxray.core.model.EvidenceType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.exp
import kotlin.math.round

/** One declared permission's pull on the score: its log-odds weight in the exported model. */
data class PermissionContribution(val permission: String, val weight: Double)

/**
 * Output of [PermissionRiskModel]. It is an INFERRED model score: how much the declared-permission
 * set resembles the malware class of 2010-2019 research data. It is never a malware verdict, and a
 * low score never clears an app.
 */
data class PermissionRiskScore(
    val probability: Double,
    val logit: Double,
    val threshold: Double,
    /** True when [probability] is at or above the model's threshold. */
    val elevated: Boolean,
    /** The app's own permissions that raise the score, strongest first. */
    val raising: List<PermissionContribution>,
    /** The app's own permissions that lower the score, strongest first. */
    val lowering: List<PermissionContribution>,
    /** How many of the app's declared permissions the model knows. */
    val recognised: Int,
    val vocabularySize: Int,
    val modelVersion: String,
    val evidence: EvidenceType = EvidenceType.INFERRED,
) {
    /** Plain wording for the UI; names the score as inferred and states its limits. */
    fun summary(): String {
        val pct = round(probability * 100).toInt()
        val head = if (elevated) {
            "Permission profile resembles malware samples in research data (model score $pct%, inferred)."
        } else {
            "Permission profile does not resemble the malware samples in our research data (model score $pct%, inferred). A low score does not clear the app."
        }
        val why = raising.take(3).joinToString(", ") { it.permission.substringAfterLast('.') }
        val reasons = if (why.isNotEmpty()) " Raised most by: $why." else ""
        return "$head$reasons Based on $recognised of the app's declared permissions; permissions are not behaviour. This is not a malware verdict."
    }
}

/**
 * The APK permission-risk logistic regression trained in `ml/train_apk_permission_risk.py`.
 * Pure Kotlin: logit = intercept + sum of weights of the declared permissions it knows.
 */
class PermissionRiskModel(
    val version: String,
    val intercept: Double,
    val threshold: Double,
    val weights: Map<String, Double>,
    /** Dataset names and licences, for the attribution line next to any score. */
    val sources: List<String>,
) {
    val vocabularySize: Int get() = weights.size

    fun score(permissions: Set<String>): PermissionRiskScore {
        val known = permissions.map(::canonical).toSet().mapNotNull { p -> weights[p]?.let { PermissionContribution(p, it) } }
        val logit = intercept + known.sumOf { it.weight }
        val probability = sigmoid(logit)
        return PermissionRiskScore(
            probability = probability,
            logit = logit,
            threshold = threshold,
            elevated = probability >= threshold,
            raising = known.filter { it.weight > 0 }.sortedByDescending { it.weight },
            lowering = known.filter { it.weight < 0 }.sortedBy { it.weight },
            recognised = known.size,
            vocabularySize = vocabularySize,
            modelVersion = version,
        )
    }

    @Serializable
    private data class Source(val name: String, val licence: String = "", val doi: String = "")

    @Serializable
    private data class File(
        val schema: String,
        val version: String,
        val intercept: Double,
        val threshold: Double,
        val weights: Map<String, Double>,
        val trainingData: List<Source> = emptyList(),
    )

    companion object {
        const val SCHEMA = "traceveil.ml.logistic-regression.v1"
        private const val RESOURCE = "/ml/apk_permission_risk.json"
        private val json = Json { ignoreUnknownKeys = true }
        private val DOUBLE_PREFIX = Regex("^(android\\.permission\\.)+")

        /** Canonical manifest name: trims and collapses a repeated `android.permission.` prefix. */
        fun canonical(name: String): String = name.trim().replace(DOUBLE_PREFIX, "android.permission.")

        /** Numerically stable logistic function (same branches as the Python reference). */
        fun sigmoid(z: Double): Double = if (z >= 0) 1.0 / (1.0 + exp(-z)) else exp(z) / (1.0 + exp(z))

        fun fromJson(text: String): PermissionRiskModel {
            val f = json.decodeFromString<File>(text)
            require(f.schema == SCHEMA) { "Unsupported model schema '${f.schema}'" }
            require(f.threshold in 0.0..1.0) { "Threshold must be a probability" }
            return PermissionRiskModel(f.version, f.intercept, f.threshold, f.weights, f.trainingData.map { "${it.name} (${it.licence})" })
        }

        fun loadDefault(): PermissionRiskModel {
            val text = PermissionRiskModel::class.java.getResourceAsStream(RESOURCE)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("${RESOURCE.removePrefix("/")} missing from resources")
            return fromJson(text)
        }
    }
}
