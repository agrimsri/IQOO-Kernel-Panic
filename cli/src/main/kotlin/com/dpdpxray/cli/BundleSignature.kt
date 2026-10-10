package com.dpdpxray.cli

import com.dpdpxray.core.automation.AutomationContract
import com.dpdpxray.core.evidence.CheckStatus
import com.dpdpxray.core.evidence.EvidenceCheck
import com.dpdpxray.core.evidence.EvidenceReport
import com.dpdpxray.core.evidence.EvidenceSignatures
import com.dpdpxray.core.evidence.EvidenceVerifier
import com.dpdpxray.core.evidence.TrustedRoot
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * The hardware signature next to one checked bundle (traceveil.signature.v1, see docs/SIGNED_EVIDENCE.md), as `check`
 * and `run` report it: verified, NOT verified (with the checks that failed) or absent. [report] is the full
 * verification, null when there is no signature file. `traceveil-ci verify` prints every check.
 */
class SignatureStatus(
    val state: State,
    /** The signature file as shown in reports; null when absent. */
    val path: String?,
    /** File names looked for next to the bundle (or the --sig file). */
    val looked: List<String>,
    val report: EvidenceReport?,
) {
    enum class State(val wire: String) { VERIFIED("verified"), FAILED("failed"), ABSENT("absent") }

    val verified: Boolean get() = state == State.VERIFIED

    val failedChecks: List<EvidenceCheck> get() = report?.checks.orEmpty().filter { it.status == CheckStatus.FAIL }

    /** "StrongBox" / "TEE" / "secure hardware", from the attestation (never from the app-written label). */
    private val level: String get() = report?.keyDescription?.attestationSecurityLevel?.label ?: "secure hardware"
    private val root: String get() = report?.root?.name ?: "a trusted root"

    /** One line: "verified (StrongBox, chain ends at <root>)", "NOT verified (<n> checks failed: ...)" or "absent (...)". */
    val summary: String
        get() = when (state) {
            State.VERIFIED -> "verified ($level, chain ends at $root)"
            State.FAILED -> "NOT verified (${failedText()})"
            State.ABSENT -> "absent (no ${looked.joinToString(" or ")} next to the bundle)"
        }

    /** The requireSignature rule's message. */
    val ruleMessage: String
        get() = when (state) {
            State.VERIFIED -> "verified: $level key, chain ends at $root"
            State.FAILED -> "the signature did not verify (${failedText()}); traceveil-ci verify shows every check"
            State.ABSENT -> "no signature file next to the bundle (looked for ${looked.joinToString(" and ")}); " +
                "TraceVeil writes one when the phone can sign in secure hardware"
        }

    private fun failedText(): String {
        val failed = failedChecks
        if (failed.isEmpty()) return "no check passed"
        return "${failed.size} check${if (failed.size == 1) "" else "s"} failed: ${failed.joinToString(", ") { it.title }}"
    }

    fun toJson(): JsonObject = buildJsonObject {
        val kd = report?.keyDescription
        put("status", state.wire)
        put("verified", verified)
        put("file", path)
        put("looked", JsonArray(looked.map(::JsonPrimitive)))
        put("securityLevel", kd?.attestationSecurityLevel?.wire)
        put("root", report?.root?.let { r -> buildJsonObject { put("name", r.name); put("publicKeySha256", r.publicKeySha256) } } ?: JsonNull)
        put("appPackages", JsonArray(kd?.applicationId?.packages.orEmpty().map { JsonPrimitive(it.name) }))
        put("failedChecks", JsonArray(failedChecks.map { c -> buildJsonObject { put("id", c.id); put("title", c.title); put("detail", c.detail) } }))
        put("text", summary)
    }

    companion object {
        /**
         * Finds and verifies the signature of [bundle]: [explicit] (from --sig) or, next to the bundle,
         * <name>.sig.json then <name without .json>.sig.json. Verification is offline against [roots] (Google's hardware
         * attestation roots in the CLI) and expects the key to have been made by [expectedPackage].
         */
        fun of(
            bundle: File,
            explicit: File?,
            cwd: File,
            roots: List<TrustedRoot>,
            expectedPackage: String = AutomationContract.APP_PACKAGE,
        ): SignatureStatus {
            val candidates = explicit?.let { listOf(it) }
                ?: EvidenceSignatures.candidatesFor(bundle.name).map { File(bundle.absoluteFile.parentFile, it) }
            val looked = candidates.map { it.name }
            val sig = candidates.firstOrNull { it.isFile } ?: return SignatureStatus(State.ABSENT, null, looked, null)
            val shown = LoadedBundle.displayPath(sig, cwd)
            // The exact bytes on disk: the signature covers them, not the decoded bundle.
            val text = runCatching { sig.readText(Charsets.UTF_8).removePrefix("﻿") }.getOrNull()
            val bytes = runCatching { bundle.readBytes() }.getOrNull()
            val report = if (text == null || bytes == null) {
                val what = if (text == null) "The signature file could not be read." else "The bundle could not be read again to check its bytes."
                EvidenceReport(listOf(EvidenceCheck("file", CheckStatus.FAIL, "Signature file", what)), null, null, null)
            } else {
                EvidenceVerifier.verify(bytes, text, roots = roots, expectedPackage = expectedPackage)
            }
            return SignatureStatus(if (report.verified) State.VERIFIED else State.FAILED, shown, looked, report)
        }
    }
}
