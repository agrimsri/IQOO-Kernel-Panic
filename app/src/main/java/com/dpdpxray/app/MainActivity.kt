package com.dpdpxray.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dpdpxray.app.audit.AuditProgress
import com.dpdpxray.app.ui.XrayViewModel
import com.dpdpxray.app.ui.AuditRoutes
import com.dpdpxray.app.ui.guide.GuideLang
import com.dpdpxray.app.ui.guide.GuideNarrator
import com.dpdpxray.app.ui.guide.GuideState
import com.dpdpxray.app.ui.guide.GuideVoice
import com.dpdpxray.app.ui.guide.LocalGuide
import com.dpdpxray.app.ui.guide.OnboardingGate
import com.dpdpxray.app.ui.onboarding.OnboardingScreen
import com.dpdpxray.app.voice.VoiceCopilotScreen
import com.dpdpxray.app.ui.screens.AiLabScreen
import com.dpdpxray.app.ui.screens.AppPickerScreen
import com.dpdpxray.app.ui.screens.CameraCheckScreen
import com.dpdpxray.app.ui.screens.FindingDetailScreen
import com.dpdpxray.app.ui.screens.FixesScreen
import com.dpdpxray.app.ui.screens.HomeScreen
import com.dpdpxray.app.ui.screens.LiveAuditScreen
import com.dpdpxray.app.ui.screens.ReportScreen
import com.dpdpxray.app.ui.screens.ResultsScreen
import com.dpdpxray.app.ui.screens.SettingsScreen
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.XrayTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: XrayViewModel by viewModels()
    // Cheap to create: the text-to-speech engine binds only when spoken guidance first speaks.
    private val guideVoice by lazy { GuideVoice(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) { vm.refreshPreflight() }
        }
        // Reads a running check's steps aloud (only when spoken guidance is on), also while the checked app is in front.
        lifecycleScope.launch { GuideNarrator.run(vm.progress, vm.settings, guideVoice) }
        setContent {
            val settings by vm.settings.collectAsState()
            val toast by vm.toast.collectAsState()
            LaunchedEffect(toast) {
                toast?.let { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show(); vm.toastShown() }
            }
            val guideNote by guideVoice.note.collectAsState()
            val guide = GuideState(
                spoken = settings.spokenGuidance, lang = GuideLang.of(settings.guideLanguage), speaker = guideVoice, note = guideNote,
                setSpoken = { on ->
                    if (on) guideVoice.forgetRecent() else guideVoice.stop()
                    vm.updateSettings { it.copy(spokenGuidance = on) }
                },
                setLang = { lang ->
                    guideVoice.forgetRecent()
                    vm.updateSettings { it.copy(guideLanguage = lang.code) }
                },
            )
            XrayTheme(stageMode = settings.stageMode) {
                CompositionLocalProvider(LocalGuide provides guide) {
                Box(Modifier.fillMaxSize().background(Film.base).safeDrawingPadding()) {
                    val nav = rememberNavController()
                    val progress by vm.progress.collectAsState()
                    val start = remember { OnboardingGate.startRoute(vm.settings.value.onboardingDone, vm.progress.value is AuditProgress.Running) }
                    val entry by nav.currentBackStackEntryAsState()
                    // Guidance never talks over the voice copilot.
                    LaunchedEffect(entry?.destination?.route) { guideVoice.suppressed = entry?.destination?.route == AuditRoutes.VOICE }
                    LaunchedEffect(Unit) {
                        // If an audit is still running when the UI comes back, show it.
                        if (progress is AuditProgress.Running) nav.navigate("live")
                    }
                    val apkCheck: () -> (() -> Unit)? = { if (nav.graph.findNode("apkguard") != null) { { nav.navigate("apkguard") } } else null }
                    val leaveOnboarding = {
                        vm.updateSettings { it.copy(onboardingDone = true) }
                        if (!nav.popBackStack(OnboardingGate.HOME, inclusive = false)) {
                            nav.navigate(OnboardingGate.HOME) { popUpTo(OnboardingGate.ONBOARDING) { inclusive = true } }
                        }
                    }
                    NavHost(nav, startDestination = start) {
                        composable(OnboardingGate.ONBOARDING) {
                            OnboardingScreen(
                                onDone = leaveOnboarding,
                                onSample = { leaveOnboarding(); vm.openSample { nav.navigate("results/$it") } },
                                onDemo = { leaveOnboarding(); if (vm.prepareAudit()) nav.navigate("pick?q=LeakyShop") },
                                onApk = apkCheck()?.let { open -> { leaveOnboarding(); open() } },
                            )
                        }
                        composable("home") {
                            HomeScreen(
                                vm,
                                onAudit = { nav.navigate("pick") },
                                onOpen = { nav.navigate("results/$it") },
                                onLab = { nav.navigate("lab") },
                                onCamera = { nav.navigate("camera") },
                                onSettings = { nav.navigate("settings") },
                                onVoice = { nav.navigate(AuditRoutes.voice(it)) },
                                onApk = apkCheck(),
                                onHowItWorks = { nav.navigate(OnboardingGate.ONBOARDING) },
                                onLive = { nav.navigate("live") },
                            )
                        }
                        composable("pick?q={q}", arguments = listOf(navArgument("q") { type = NavType.StringType; defaultValue = "" })) { e ->
                            AppPickerScreen(
                                vm, onBack = { nav.popBackStack() }, onStarted = { nav.navigate("live") { popUpTo("home") } },
                                initialQuery = e.arguments?.getString("q").orEmpty(),
                            )
                        }
                        composable("live") {
                            LiveAuditScreen(
                                vm,
                                onDone = { id -> nav.navigate("results/$id") { popUpTo("home") } },
                                onBack = { nav.popBackStack("home", inclusive = false) },
                            )
                        }
                        composable("results/{id}") { e ->
                            val id = e.arguments?.getString("id").orEmpty()
                            ResultsScreen(
                                vm, id, onBack = { nav.popBackStack() },
                                onFinding = { nav.navigate("finding/$id/${it.name}") },
                                onFixes = { nav.navigate("fixes/$id") },
                                onReport = { nav.navigate("report/$id") },
                                onAsk = { nav.navigate(AuditRoutes.voice(id)) },
                                onCheckAgain = { if (vm.prepareAudit()) nav.navigate("pick") { popUpTo("home") } },
                            )
                        }
                        composable("finding/{id}/{check}") { e ->
                            val id = e.arguments?.getString("id").orEmpty()
                            FindingDetailScreen(vm, id, e.arguments?.getString("check").orEmpty(), onBack = { nav.popBackStack() }, onFixes = { nav.navigate("fixes/$id") })
                        }
                        composable("fixes/{id}") { e -> FixesScreen(vm, e.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() }) }
                        composable("report/{id}") { e -> ReportScreen(vm, e.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() }) }
                        composable(AuditRoutes.VOICE) { e ->
                            VoiceCopilotScreen(e.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
                        }
                        composable("lab") { AiLabScreen(vm, onBack = { nav.popBackStack() }) }
                        composable("camera") { CameraCheckScreen(vm, onBack = { nav.popBackStack() }) }
                        composable("settings") { SettingsScreen(vm, onBack = { nav.popBackStack() }) }
                    }
                }
                }
            }
        }
    }

    override fun onDestroy() {
        // The speaker belongs to this activity instance; a recreated activity makes its own.
        guideVoice.release()
        super.onDestroy()
    }
}
