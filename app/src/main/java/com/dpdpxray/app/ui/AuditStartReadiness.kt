package com.dpdpxray.app.ui

import com.dpdpxray.app.control.Preflight

internal object AuditStartReadiness {
    fun problem(preflight: Preflight): String? = when {
        !preflight.vpnReady -> "Allow the VPN permission before starting an audit."
        !preflight.accessibilityOn -> "Enable the TraceVeil consent watcher in Accessibility settings, then return here."
        else -> null
    }
}
