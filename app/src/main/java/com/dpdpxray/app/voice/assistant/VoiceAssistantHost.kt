package com.dpdpxray.app.voice.assistant

import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import com.dpdpxray.app.apkguard.ApkFileChecks
import com.dpdpxray.app.apkguard.ApkGuardRoutes
import com.dpdpxray.app.apkguard.IncomingApk
import com.dpdpxray.app.control.DeviceChecks
import com.dpdpxray.app.ui.XrayViewModel
import com.dpdpxray.app.ui.shell.AppTab
import com.dpdpxray.app.ui.shell.openTab

/**
 * Mounted once in MainActivity, next to the wake-word hooks. While TraceVeil is started it performs the assistant's
 * screen work exactly as the matching buttons would: the app picker's audit start and live screen, APK Guard's file
 * picker, the VPN request and the system settings screens. It never runs while TraceVeil is in the background.
 */
@Composable
fun VoiceAssistantHost(nav: NavController, vm: XrayViewModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val vpnRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.refreshPreflight() }
    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching { nav.navigate(ApkGuardRoutes.file(ApkFileChecks.start(context, uri))) }
    }

    LaunchedEffect(nav) {
        AssistantBus.publishRoutes(OPTIONAL_ROUTES.filter { runCatching { nav.graph.findNode(it) != null }.getOrDefault(false) }.toSet())
    }

    LaunchedEffect(lifecycle, nav) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            AssistantBus.hostActive = true
            try {
                AssistantBus.pending.collect { waiting ->
                    val req = waiting?.let { AssistantBus.take(it) } ?: return@collect
                    val outcome = runCatching {
                        when (val action = req.action) {
                            is AssistantAction.StartAudit ->
                                VoiceAuditStart.start(action.config, { vm.progress.value }, vm::startAudit, { vm.toast.value }).also {
                                    if (it == UiOutcome.Done) {
                                        nav.navigate("live") { popUpTo("home") }
                                        VoiceAssistant.get(context).followUp(action.config, req.hindi)
                                    }
                                }
                            is AssistantAction.Navigate -> navigate(nav, action.route)
                            AssistantAction.PickApkFile -> {
                                apkPicker.launch(arrayOf(IncomingApk.APK_MIME, "application/octet-stream"))
                                UiOutcome.Done
                            }
                            is AssistantAction.FixSetup -> {
                                // Home's "Getting ready" card shows the remaining steps when the user comes back.
                                nav.openTab(AppTab.HOME)
                                when (action.fix) {
                                    SetupFix.VPN -> VpnService.prepare(context)?.let { vpnRequest.launch(it) }
                                    SetupFix.WATCHER -> DeviceChecks.openAccessibilitySettings(context)
                                    SetupFix.PRIVATE_DNS -> DeviceChecks.openPrivateDnsSettings(context)
                                }
                                UiOutcome.Done
                            }
                        }
                    }.getOrElse { UiOutcome.Failed(it.message) }
                    AssistantBus.complete(req, outcome)
                }
            } finally {
                AssistantBus.hostActive = false
            }
        }
    }
}

/** Routes added by other features; a command for one that is missing says so instead of crashing. */
private val OPTIONAL_ROUTES = listOf(AssistantPlanner.ROUTE_CHECKUP, AssistantPlanner.ROUTE_TRUST, AssistantPlanner.ROUTE_APK_GUARD)

private fun navigate(nav: NavController, route: String): UiOutcome {
    val current = nav.currentBackStackEntry
    when (route) {
        AssistantPlanner.ROUTE_HOME -> nav.openTab(AppTab.HOME)
        AssistantPlanner.ROUTE_REPORTS -> nav.openTab(AppTab.REPORTS)
        AssistantPlanner.ROUTE_PICK -> nav.navigate("pick") { launchSingleTop = true }
        else -> {
            if (route.startsWith("results/")) {
                val id = route.removePrefix("results/")
                if (current?.destination?.route == "results/{id}" && current.arguments?.getString("id") == id) return UiOutcome.Done
            } else if (nav.graph.findNode(route) == null) {
                return UiOutcome.Unavailable
            }
            nav.navigate(route) { launchSingleTop = true }
        }
    }
    return UiOutcome.Done
}
