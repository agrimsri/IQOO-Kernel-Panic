package com.dpdpxray.app.audit

import com.dpdpxray.app.ai.LlmEngine
import com.dpdpxray.app.consent.ConsentAccessibilityService
import com.dpdpxray.core.consent.BlockerAgent

/**
 * Asks the on-device model for the next step when a screen stands between an app's launch and its consent screen and no
 * rule knows what to do with it. One runner serves one plan: it remembers what it has already done on the way, so the model
 * is told, and gives up (the person is asked instead) when a step changes nothing.
 *
 * Every step the model proposes goes through [BlockerAgent.decide] first, which refuses anything unsafe.
 */
internal class BlockerAgentRunner(private val llm: LlmEngine, private val maxSteps: Int = MAX_STEPS) {
    private val history = mutableListOf<String>()
    private var lastScreen: String? = null
    private var steps = 0

    /** Why the model could not go on (shown to the person with the hand-over), or null. */
    var lastReason: String? = null
        private set

    /** True while the model can still be asked: it is loaded and the step budget is not used up. */
    val available: Boolean get() = llm.isReady && steps < maxSteps

    /** The description of the step taken, for the audit notes, or null when nothing was done. Main thread only. */
    suspend fun step(service: ConsentAccessibilityService): String? {
        if (!available) return null
        val (tree, _) = service.readScreen() ?: return null
        val digest = BlockerAgent.digest(tree)
        if (digest.isEmpty) return null
        // The screen is exactly as it was after the last step: that step did nothing, so stop and ask the person.
        if (digest.text == lastScreen && history.isNotEmpty()) {
            lastReason = "The screen did not change after the last step."
            return null
        }
        lastScreen = digest.text
        steps++
        // If the safety check refuses what the model chose, tell it why and let it choose once more.
        val feedback = mutableListOf<String>()
        var step: BlockerAgent.Step = BlockerAgent.Step.HandOff("The on-device model gave no answer.", vetoed = false)
        for (attempt in 0..1) {
            val reply = llm.decideBlockerStep(BlockerAgent.userMessage(digest, history + feedback)) ?: break
            step = BlockerAgent.decide(reply, tree, digest)
            if (step !is BlockerAgent.Step.HandOff || !step.vetoed) break
            feedback += "Not allowed: ${step.reason} Choose a different step, or hand_off."
        }
        lastReason = step.reason.ifBlank { null }
        val done = service.executeAgentStep(step, tree) ?: return null
        history += done
        lastReason = null
        return "$done, chosen by the on-device model"
    }

    companion object {
        /** Enough for a tour, a short form and a picker; a screen needing more than this is better left to the person. */
        const val MAX_STEPS = 8
    }
}
