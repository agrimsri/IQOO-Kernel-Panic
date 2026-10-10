package com.dpdpxray.app.ui

import com.dpdpxray.app.control.Preflight

internal object AuditStartReadiness {
    fun problem(preflight: Preflight): String? = when {
        preflight.otherVpnActive -> OTHER_VPN
        !preflight.vpnReady -> "Allow the VPN permission before starting an audit."
        !preflight.accessibilityOn -> "Enable the TraceVeil consent watcher in Accessibility settings, then return here."
        else -> null
    }

    /** Shown instead of taking over: Android runs one VPN at a time and TraceVeil never switches the user's VPN off. */
    const val OTHER_VPN = "Another VPN is on. Android allows one VPN at a time, so turn yours off in Settings first; " +
        "TraceVeil never switches it off for you. Turn it back on after the check."
}
