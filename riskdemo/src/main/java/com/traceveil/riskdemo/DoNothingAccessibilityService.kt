package com.traceveil.riskdemo

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Exists only so the manifest shows an accessibility service, a signal fake challan APKs share.
 * It does nothing: its config subscribes to no events, and both callbacks are intentionally empty.
 * The app never switches it on; only a person can, in Android Settings.
 */
class DoNothingAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally empty: TraceVeil demo, does nothing.
    }

    override fun onInterrupt() {
        // Intentionally empty.
    }
}
