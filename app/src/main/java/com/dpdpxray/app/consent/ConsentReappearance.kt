package com.dpdpxray.app.consent

import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.UiNode

/**
 * When the watcher records a consent screen as "shown again after the choice" ([ConsentKind.REPROMPT], the evidence
 * for the nagging signal). Only a consent screen that comes back (it was gone at the previous check) after the plan's
 * first screen and a recorded Accept/Reject. The first-screen path is unchanged: until a screen is recorded and a
 * choice made, this never fires. Pure, so it is tested on the JVM.
 */
object ConsentReappearance {
    /** A screen that bounces in and out is recorded a few times at most per plan. */
    const val MAX_PER_PLAN = 3

    fun shouldRecord(isConsent: Boolean, wasVisible: Boolean, screenSeen: Boolean, choiceMade: Boolean, recordedSoFar: Int): Boolean =
        countsAsReturn(isConsent, wasVisible, screenSeen, choiceMade) && recordedSoFar < MAX_PER_PLAN

    /** Every return of the consent screen after the choice, recorded or not, so a loop past the cap is still counted. */
    fun countsAsReturn(isConsent: Boolean, wasVisible: Boolean, screenSeen: Boolean, choiceMade: Boolean): Boolean =
        isConsent && !wasVisible && screenSeen && choiceMade

    /** The plan note when the screen came back more often than is kept as evidence (a reprompt loop); null otherwise. */
    fun capNote(returns: Int): String? = if (returns <= MAX_PER_PLAN) null
        else "The consent screen came back $returns times after the choice (a reprompt loop); TraceVeil kept the first $MAX_PER_PLAN as evidence and counted the rest."

    /** The re-shown screen's tree is kept so the signal can show what it asked; there is no screenshot. */
    fun event(tNanos: Long, tree: UiNode) = ConsentEvent(tNanos, ConsentKind.REPROMPT, tree = tree)
}
