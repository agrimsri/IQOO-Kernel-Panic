package com.dpdpxray.app.data

import com.dpdpxray.core.model.AuditResult
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.ConsentHistory
import com.dpdpxray.core.rules.Inconclusive

/**
 * The person's answer about their consent history, for a saved check whose consent history is unknown (the app kept
 * its data and showed no consent screen). The result is then a range ([com.dpdpxray.core.model.HistoryBounds]); an
 * answer picks one end. Only a tap on the result sets it, nothing infers it, and it can be changed or cleared. Pure:
 * the rules engine is passed in, so the app and its tests re-score the same way.
 */
object ConsentAnswers {
    enum class State {
        /** No range: the history is known (fresh start or a consent screen), or this is a sample. */
        NONE,

        /** Both answers give the same band: no question is needed. */
        SETTLED,

        /** The answer can change the band: ask the one question. */
        ASK,

        /** Asked; the person is not sure. The range stays. */
        NOT_SURE,

        /** The person answered; the result is the end their answer picked. */
        ANSWERED,
    }

    fun state(b: AuditBundle): State {
        val bounds = b.result.historyBounds
        if (b.meta.sample || bounds == null) return State.NONE
        return when {
            bounds.answer?.declared == true -> State.ANSWERED
            bounds.answer == ConsentHistory.NOT_SURE -> State.NOT_SURE
            bounds.bandSettled -> State.SETTLED
            else -> State.ASK
        }
    }

    /**
     * For a check saved before results carried a consent-history range: the same sessions scored again, but only when
     * that adds the range and changes nothing else (same checks, same score and band). Otherwise null, and the saved
     * result stays as it was.
     */
    fun rangeFor(b: AuditBundle, evaluate: (List<AuditSession>, ConsentHistory) -> AuditResult): AuditResult? {
        if (b.meta.sample || b.result.historyBounds != null) return null
        if (Inconclusive.reason(b.result.findings) != Inconclusive.Reason.CONSENT_HISTORY_UNKNOWN) return null
        val again = evaluate(b.sessions, ConsentHistory.UNKNOWN)
        val same = again.score == b.result.score && again.band == b.result.band &&
            again.findings.map { it.checkId }.toSet() == b.result.findings.map { it.checkId }.toSet()
        return again.takeIf { same && it.historyBounds != null }
    }

    /**
     * Re-scores [b]'s sessions with [answer] (null clears it) and returns the metadata and result to save in place of
     * the old ones. The sessions themselves are never changed.
     */
    fun apply(
        b: AuditBundle,
        answer: ConsentHistory?,
        evaluate: (List<AuditSession>, ConsentHistory) -> AuditResult,
    ): Pair<AuditMeta, AuditResult> {
        val result = evaluate(b.sessions, answer ?: ConsentHistory.UNKNOWN)
        val meta = b.meta.copy(
            score = result.score, band = result.band, findingCount = result.findings.size,
            inconclusiveReason = Inconclusive.reason(result.findings)?.name, historyBounds = result.historyBounds,
        )
        return meta to result
    }
}
