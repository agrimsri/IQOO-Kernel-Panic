package com.dpdpxray.app.voice.wake

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.dpdpxray.app.graph
import com.dpdpxray.app.ui.AuditRoutes

/** Turns shortcut, App Actions and wake-word notification intents into navigation inside MainActivity. */
object AssistantEntry {
    fun featureFrom(intent: Intent?): AssistantFeature? {
        if (intent == null) return null
        // Reopening from recents replays the original intent; that is not a new request.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
        return AssistantFeatures.parse(intent.getStringExtra(AssistantFeatures.EXTRA_FEATURE))
    }

    /** Opens the feature's existing screen. Returns a message to show when it cannot, or null. */
    suspend fun open(nav: NavController, feature: AssistantFeature, context: Context): String? {
        val latest = if (feature == AssistantFeature.ASK_LAST_CHECK) {
            val repository = context.graph.repository
            repository.refresh()
            repository.audits.value.firstOrNull()?.id
        } else {
            null
        }
        // Another branch adds the "apkguard" screen; until it is in this build's graph, say so instead of crashing.
        val apkRouteAvailable = runCatching { nav.graph.findNode(AssistantFeatures.APK_GUARD_ROUTE) != null }.getOrDefault(false)
        return when (val route = AssistantFeatures.route(feature, latest, apkRouteAvailable)) {
            is AssistantRoute.Navigate ->
                runCatching { nav.navigate(route.route) { launchSingleTop = true } }.exceptionOrNull()?.let { "That screen could not be opened." }
            is AssistantRoute.Notice -> route.message
        }
    }
}

/**
 * Mounted once in MainActivity. Tells the wake word when the voice copilot is open, so it lets go of the microphone
 * (only one recogniser may use it) and resumes when the copilot closes, and whether TraceVeil is on screen, so it
 * changes screens directly only when Android allows it.
 */
@Composable
fun HeyTraceVeilHooks(nav: NavController) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(nav, lifecycle) {
        val destinations = NavController.OnDestinationChangedListener { _, destination, _ ->
            WakeRuntime.copilotOpen = destination.route == AuditRoutes.VOICE
        }
        val visibility = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> WakeRuntime.uiVisible = true
                Lifecycle.Event.ON_STOP -> WakeRuntime.uiVisible = false
                else -> Unit
            }
        }
        nav.addOnDestinationChangedListener(destinations)
        lifecycle.addObserver(visibility)
        onDispose {
            nav.removeOnDestinationChangedListener(destinations)
            lifecycle.removeObserver(visibility)
            WakeRuntime.uiVisible = false
            WakeRuntime.copilotOpen = false
        }
    }
}
