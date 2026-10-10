package com.dpdpxray.core.consentlens

/** The role of one consent-screen string. Order = the model's output order (see `consentlens_text.py`). */
enum class ConsentRole {
    ACCEPT, REJECT, MANAGE, PURPOSE_NOTICE, PRIVACY_LINK, WITHDRAW_INFO, OTHER;

    val isChoice: Boolean get() = this == ACCEPT || this == REJECT || this == MANAGE
    val isNotice: Boolean get() = this == PURPOSE_NOTICE || this == PRIVACY_LINK || this == WITHDRAW_INFO
}

/** One model output. Always INFERRED evidence; [confidence] is the softmax probability of [role]. */
data class RolePrediction(
    val text: String,
    val role: ConsentRole,
    val confidence: Double,
    val probabilities: List<Double>,
)

/** Anything that labels consent-screen strings (the on-device engine, or a fake in tests). */
interface ConsentRoleClassifier {
    val modelId: String

    /** Which hardware actually ran the last classification (e.g. "NPU (QNN HTP V81)", "CPU (XNNPACK)"), if known. */
    val backendLabel: String?

    /** One prediction per input text, in order. May throw; callers treat a failure as "no ConsentLens evidence". */
    fun classify(texts: List<String>): List<RolePrediction>
}

/** Turns the model's INT8 softmax output into probabilities and a role. */
object ConsentLensDecoder {
    val ROLES: List<ConsentRole> = ConsentRole.entries

    fun dequantize(q: ByteArray, scale: Float, zeroPoint: Int): DoubleArray =
        DoubleArray(q.size) { scale.toDouble() * (q[it].toInt() - zeroPoint) }

    fun decode(text: String, probabilities: DoubleArray): RolePrediction {
        require(probabilities.size == ROLES.size) { "expected ${ROLES.size} outputs, got ${probabilities.size}" }
        var best = 0
        for (i in probabilities.indices) if (probabilities[i] > probabilities[best]) best = i
        return RolePrediction(text, ROLES[best], probabilities[best], probabilities.toList())
    }
}
