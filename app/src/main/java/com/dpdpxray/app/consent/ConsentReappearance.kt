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
        isConsent && !wasVisible && screenSeen && choiceMade && recordedSoFar < MAX_PER_PLAN

    /** The re-shown screen's tree is kept so the signal can show what it asked; there is no screenshot. */
    fun event(tNanos: Long, tree: UiNode) = ConsentEvent(tNanos, ConsentKind.REPROMPT, tree = tree)
}
