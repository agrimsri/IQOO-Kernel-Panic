package com.dpdpxray.core.ml

import com.dpdpxray.core.model.EvidenceType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.log2
import kotlin.math.round

/** A character n-gram of the scored site and the weight of its hash bucket (approximate: buckets can collide). */
data class NgramContribution(val ngram: String, val weight: Double)

/**
 * Output of [HostnameModel] ("TrackerLens"). An INFERRED score that the hostname's site looks like the
 * tracking/analytics sites on public blocklists. A DNS lookup is an attempt to contact a host, not proof
 * that data was sent, and this score never creates a finding on its own.
 */
data class HostnameScore(
    val hostname: String,
    /** The part actually scored: the hostname's registrable-domain approximation. */
    val site: String,
    val probability: Double,
    val logit: Double,
    val threshold: Double,
    val elevated: Boolean,
    /** Up to three n-grams (length 3+) of the site that raise the score most, strongest first. */
    val topNgrams: List<NgramContribution>,
    val modelVersion: String,
    val evidence: EvidenceType = EvidenceType.INFERRED,
) {
    fun summary(): String {
        val pct = round(probability * 100).toInt()
        val head = if (elevated) {
            "Possible tracking site: $site resembles tracking/analytics sites on public blocklists (model score $pct%, inferred)."
        } else {
            "$site does not resemble the listed tracking sites (model score $pct%, inferred). A low score does not clear the host."
        }
        val why = topNgrams.joinToString(", ") { "\"${it.ngram.trim('^', '$')}\"" }
        val reasons = if (elevated && why.isNotEmpty()) " Name parts that raised it: $why." else ""
        return "$head$reasons A lookup is an attempt to contact a host, not proof that data was sent."
    }
}

/** Hashed character n-gram logistic regression trained in `ml/train_tracker_hostname.py`. Pure Kotlin. */
class HostnameModel(
    val version: String,
    val intercept: Double,
    val threshold: Double,
    val hashBits: Int,
    val secondLevel: Set<String>,
    private val dense: Map<String, Double>,
    private val hashed: DoubleArray,
) {
    val hashedSize: Int get() = hashed.size
    private val mask = (1 shl hashBits) - 1

    fun score(hostname: String): HostnameScore {
        val host = normalise(hostname)
        val site = site(host, secondLevel)
        val grams = ngrams(site)
        val buckets = grams.map { bucket(it) }.toSet()
        val values = denseFeatures(site)
        var logit = intercept
        for (b in buckets) logit += hashed[b]
        for ((name, v) in values) logit += (dense[name] ?: 0.0) * v
        val probability = PermissionRiskModel.sigmoid(logit)
        val top = grams.filter { it.length >= 3 }
            .map { NgramContribution(it, hashed[bucket(it)]) }
            .distinctBy { bucket(it.ngram) }
            .filter { it.weight > 0 }
            .sortedByDescending { it.weight }
            .take(3)
        return HostnameScore(host, site, probability, logit, threshold, probability >= threshold, top, version)
    }

    private fun bucket(gram: String): Int = fnv1a32(gram) and mask

    @Serializable
    private data class FeatureSpace(val hashBits: Int, val secondLevel: List<String>)

    @Serializable
    private data class File(
        val schema: String,
        val version: String,
        val intercept: Double,
        val threshold: Double,
        val featureSpace: FeatureSpace,
        val dense: Map<String, Double>,
        val hashedWeightsFloat32LeBase64: String,
    )

    companion object {
        const val SCHEMA = "traceveil.ml.hashed-logistic-regression.v1"
        private const val RESOURCE = "/ml/tracker_hostname.json"
        private val json = Json { ignoreUnknownKeys = true }

        fun normalise(host: String): String = host.trim().lowercase().trimEnd('.')

        /** Last two labels, or last three under a two-letter country code with a known second level (co.uk, com.au). */
        fun site(host: String, secondLevel: Set<String>): String {
            val labels = host.split('.')
            val n = if (labels.size >= 3 && labels.last().length == 2 && labels[labels.size - 2] in secondLevel) 3 else 2
            return labels.takeLast(n).joinToString(".")
        }

        /** Distinct character n-grams (2-5) of '^host$' in first-seen order. */
        fun ngrams(host: String): List<String> {
            val s = "^$host$"
            val seen = LinkedHashSet<String>()
            for (n in 2..5) for (i in 0..s.length - n) seen.add(s.substring(i, i + n))
            return seen.toList()
        }

        /** FNV-1a 32-bit over UTF-8 bytes (as a wrapped Int). */
        fun fnv1a32(text: String): Int {
            var h = 0x811C9DC5.toInt()
            for (b in text.encodeToByteArray()) {
                h = h xor (b.toInt() and 0xFF)
                h *= 0x01000193
            }
            return h
        }

        /** length/64, labels/8, digit ratio, character entropy (bits)/5 — same order as the Python reference. */
        fun denseFeatures(host: String): List<Pair<String, Double>> {
            val n = host.length
            if (n == 0) return listOf("length" to 0.0, "labels" to 0.0, "digitRatio" to 0.0, "entropy" to 0.0)
            val counts = LinkedHashMap<Char, Int>()
            for (c in host) counts[c] = (counts[c] ?: 0) + 1
            var entropy = 0.0
            for (count in counts.values) {
                val p = count.toDouble() / n
                entropy -= p * log2(p)
            }
            return listOf(
                "length" to n / 64.0,
                "labels" to (host.count { it == '.' } + 1) / 8.0,
                "digitRatio" to host.count { it in '0'..'9' }.toDouble() / n,
                "entropy" to entropy / 5.0,
            )
        }

        fun fromJson(text: String): HostnameModel {
            val f = json.decodeFromString<File>(text)
            require(f.schema == SCHEMA) { "Unsupported model schema '${f.schema}'" }
            require(f.threshold in 0.0..1.0) { "Threshold must be a probability" }
            val bytes = Base64.getDecoder().decode(f.hashedWeightsFloat32LeBase64)
            val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            require(floats.remaining() == 1 shl f.featureSpace.hashBits) { "Expected ${1 shl f.featureSpace.hashBits} hashed weights" }
            val hashed = DoubleArray(floats.remaining()) { floats.get(it).toDouble() }
            return HostnameModel(f.version, f.intercept, f.threshold, f.featureSpace.hashBits, f.featureSpace.secondLevel.toSet(), f.dense, hashed)
        }

        fun loadDefault(): HostnameModel {
            val text = HostnameModel::class.java.getResourceAsStream(RESOURCE)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("${RESOURCE.removePrefix("/")} missing from resources")
            return fromJson(text)
        }
    }
}
