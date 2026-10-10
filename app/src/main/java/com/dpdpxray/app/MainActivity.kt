package com.dpdpxray.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.dpdpxray.app.apkguard.ApkGuardHubScreen
import com.dpdpxray.app.apkguard.ApkGuardIntents
import com.dpdpxray.app.apkguard.ApkGuardResultScreen
import com.dpdpxray.app.apkguard.ApkGuardRoutes
import com.dpdpxray.app.apkguard.ApkGuardSubject
import androidx.navigation.navArgument
import com.dpdpxray.app.audit.AuditProgress
import com.dpdpxray.app.data.Audience
import com.dpdpxray.app.automation.AutomationIntents
import com.dpdpxray.app.autoguard.AutoGuardRoutes
import com.dpdpxray.app.autoguard.CheckupScreen
import com.dpdpxray.app.quarantine.QuarantineRoutes
import com.dpdpxray.app.quarantine.QuarantineScreen
import com.dpdpxray.app.quarantine.navigateToQuarantine
import com.dpdpxray.app.trust.TrustRoutes
import com.dpdpxray.app.trust.trustDestination
import com.dpdpxray.app.ui.XrayViewModel
import com.dpdpxray.app.ui.AuditRoutes
import com.dpdpxray.app.ui.guide.GuideLang
import com.dpdpxray.app.ui.guide.GuideState
import com.dpdpxray.app.ui.guide.GuideViewModel
import com.dpdpxray.app.ui.guide.LocalGuide
import com.dpdpxray.app.ui.guide.OnboardingGate
import com.dpdpxray.app.ui.onboarding.OnboardingScreen
import com.dpdpxray.app.ui.modes.EverydayHomeScreen
import com.dpdpxray.app.ui.modes.ModeRoutes
import com.dpdpxray.app.voice.VoiceCopilotScreen
import com.dpdpxray.app.voice.wake.AssistantEntry
import com.dpdpxray.app.voice.wake.AssistantFeature
import com.dpdpxray.app.voice.wake.HeyTraceVeilHooks
import com.dpdpxray.app.voice.assistant.VoiceAssistantHost
import com.dpdpxray.app.ui.screens.AiLabScreen
import com.dpdpxray.app.ui.screens.AppPickerScreen
import com.dpdpxray.app.ui.screens.CameraCheckScreen
import com.dpdpxray.app.ui.screens.FindingDetailScreen
import com.dpdpxray.app.ui.screens.FixesScreen
import com.dpdpxray.app.ui.screens.HomeScreen
import com.dpdpxray.app.ui.screens.LiveAuditScreen
import com.dpdpxray.app.ui.screens.MoreScreen
import com.dpdpxray.app.ui.screens.ReportsScreen
import com.dpdpxray.app.ui.screens.ReportScreen
import com.dpdpxray.app.ui.screens.ResultsScreen
import com.dpdpxray.app.ui.screens.SettingsScreen
import com.dpdpxray.app.ui.shell.AppBottomBar
import com.dpdpxray.app.ui.shell.AppTab
import com.dpdpxray.app.ui.shell.AppTabs
import com.dpdpxray.app.ui.shell.openTab
import com.dpdpxray.app.ui.shell.ScreenMotion
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.FilmExtra
import com.dpdpxray.app.ui.theme.LocalReduceMotion
import com.dpdpxray.app.ui.theme.XrayTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: XrayViewModel by viewModels()
    /** APK Guard route requested by an opened/shared APK file or a new-install notification. */
    private val apkGuardRoute = MutableStateFlow<String?>(null)

    /** Developer automation: a check started by TraceVeil Runner shows its live screen (automation/AutomationActivity). */
    private val openLiveCheck = MutableStateFlow(false)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ApkGuardIntents.route(this, intent)?.let { apkGuardRoute.value = it }
        if (AutomationIntents.opensLive(intent)) openLiveCheck.value = true
    }
    /** Launcher shortcut, "Hey Google" App Action or wake-word notification request, consumed once. */
    private val assistantFeatures = Channel<AssistantFeature>(Channel.CONFLATED)
    // Survives rotation; its text-to-speech engine binds only when spoken guidance first speaks. It also runs the
    // narrator that reads a running check's steps aloud (only when spoken guidance is on).
    private val guideVm: GuideViewModel by viewModels()
    private val guideVoice get() = guideVm.voice

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        graph.warmUpSemanticSearch()
        // Auto-guard: alert at once for apps installed while TraceVeil was closed (once per process).
        com.dpdpxray.app.autoguard.AutoGuard.onAppOpened(this)
        if (savedInstanceState == null) apkGuardRoute.value = ApkGuardIntents.route(this, intent)
        if (savedInstanceState == null && AutomationIntents.opensLive(intent)) openLiveCheck.value = true
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) { vm.refreshPreflight() }
        }
        if (savedInstanceState == null) AssistantEntry.featureFrom(intent)?.let { assistantFeatures.trySend(it) }
        addOnNewIntentListener { AssistantEntry.featureFrom(it)?.let { f -> assistantFeatures.trySend(f) } }
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
                val nav = rememberNavController()
                val entry by nav.currentBackStackEntryAsState()
                val route = entry?.destination?.route
                // The bar belongs to the five top-level screens only, and steps aside while the keyboard is open.
                val tab = AppTabs.tabOf(route)
                val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                val selectTab: (AppTab) -> Unit = { target ->
                    // Same gate as Home's "Check an app": without the VPN and watcher, Home shows what is missing.
                    if (target == AppTab.CHECK && !vm.prepareAudit()) nav.openTab(AppTab.HOME) else nav.openTab(target)
                }
                val reduceMotion = LocalReduceMotion.current
                Scaffold(
                    // A soft vertical gradient behind every screen; screens themselves stay transparent.
                    modifier = Modifier.background(FilmExtra.backdrop),
                    containerColor = Color.Transparent,
                    contentColor = Film.bone,
                    contentWindowInsets = WindowInsets.safeDrawing,
                    bottomBar = { if (tab != null && !keyboardOpen) AppBottomBar(tab, selectTab) },
                ) { padding ->
                // Content keeps a comfortable reading width on tablets and in landscape.
                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.fillMaxHeight().widthIn(max = 680.dp).fillMaxWidth()) {
                    val progress by vm.progress.collectAsState()
                    val start = remember { OnboardingGate.startRoute(vm.settings.value.onboardingDone, vm.progress.value is AuditProgress.Running) }
                    // Guidance never talks over the voice copilot.
                    LaunchedEffect(route) { guideVoice.suppressed = route == AuditRoutes.VOICE }
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
                    NavHost(
                        nav, startDestination = start,
                        enterTransition = { ScreenMotion.enter(reduceMotion, ScreenMotion.isTabSwitch(initialState.destination.route, targetState.destination.route)) },
                        exitTransition = { ScreenMotion.exit(reduceMotion, ScreenMotion.isTabSwitch(initialState.destination.route, targetState.destination.route)) },
                        popEnterTransition = { ScreenMotion.popEnter(reduceMotion, ScreenMotion.isTabSwitch(initialState.destination.route, targetState.destination.route)) },
                        popExitTransition = { ScreenMotion.popExit(reduceMotion, ScreenMotion.isTabSwitch(initialState.destination.route, targetState.destination.route)) },
                    ) {
                        composable(OnboardingGate.ONBOARDING) {
                            OnboardingScreen(
                                onDone = leaveOnboarding,
                                onSample = { leaveOnboarding(); vm.openSample { nav.navigate("results/$it") } },
                                onDemo = { leaveOnboarding(); if (vm.prepareAudit()) nav.navigate("pick?q=LeakyShop") },
                                onApk = apkCheck()?.let { open -> { leaveOnboarding(); open() } },
                                // Mode choice: nothing pre-picked on first run; a replay shows the current mode.
                                audience = settings.audience.takeIf { settings.onboardingDone },
                                onAudience = { a -> vm.updateSettings { it.copy(audience = a) } },
                            )
                        }
                        composable("home") {
                            // Everyday mode gets its own front door; both doors open the same screens and engine.
                            if (settings.audience == Audience.USER) {
                                val routeIfPresent: (String) -> (() -> Unit)? = { r -> if (nav.graph.findNode(r) != null) { { nav.navigate(r) } } else null }
                                EverydayHomeScreen(
                                    vm,
                                    onCheckApp = { nav.navigate("pick") },
                                    onApkFile = { nav.navigate(ApkGuardRoutes.file(it)) },
                                    onAlerts = { nav.openTab(AppTab.APK) },
                                    onOpen = { nav.navigate("results/$it") },
                                    onAllReports = { nav.openTab(AppTab.REPORTS) },
                                    onLive = { nav.navigate("live") },
                                    onSettings = { nav.navigate("settings") },
                                    onCheckup = routeIfPresent(ModeRoutes.CHECKUP),
                                    onTrust = routeIfPresent(ModeRoutes.TRUST),
                                )
                            } else HomeScreen(
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
                                onAllReports = { nav.openTab(AppTab.REPORTS) },
                                onAutomation = { nav.navigate("settings") },
                            )
                        }
                        composable(AppTabs.REPORTS) {
                            ReportsScreen(vm, onOpen = { nav.navigate("results/$it") })
                        }
                        composable(AppTabs.MORE) {
                            MoreScreen(
                                vm,
                                onSettings = { nav.navigate("settings") },
                                onLab = { nav.navigate("lab") },
                                onCamera = { nav.navigate("camera") },
                                onVoice = { nav.navigate(AuditRoutes.voice(it)) },
                                onHowItWorks = { nav.navigate(OnboardingGate.ONBOARDING) },
                                onOpen = { nav.navigate("results/$it") },
                                onTrust = { nav.navigate(TrustRoutes.ROUTE) },
                            )
                        }
                        trustDestination(nav)
                        composable("pick?q={q}", arguments = listOf(navArgument("q") { type = NavType.StringType; defaultValue = "" })) { e ->
                            AppPickerScreen(
                                vm, onBack = { nav.popBackStack() }, onStarted = { nav.navigate("live") { popUpTo("home") } },
                                initialQuery = e.arguments?.getString("q").orEmpty(),
                                onQuarantine = { nav.navigateToQuarantine(it) },
                            )
                        }
                        composable(QuarantineRoutes.ROUTE) { e ->
                            QuarantineScreen(vm, e.arguments?.getString(QuarantineRoutes.ARG).orEmpty(), onBack = { nav.popBackStack() })
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
                        composable("settings") {
                            SettingsScreen(vm, onBack = { nav.popBackStack() }, onCheckup = { nav.navigate(AutoGuardRoutes.CHECKUP) })
                        }
                        composable(ApkGuardRoutes.HUB) {
                            ApkGuardHubScreen(
                                onBack = null,
                                onOpenApp = { nav.navigate(ApkGuardRoutes.app(it)) },
                                onOpenFile = { nav.navigate(ApkGuardRoutes.file(it)) },
                                onCheckup = { nav.navigate(AutoGuardRoutes.CHECKUP) },
                            )
                        }
                        // Auto-guard "My phone checkup"; also opened by its weekly notification.
                        composable(AutoGuardRoutes.CHECKUP) {
                            CheckupScreen(onBack = { nav.popBackStack() }, onOpenApp = { nav.navigate(ApkGuardRoutes.app(it)) })
                        }
                        composable(ApkGuardRoutes.APP) { e ->
                            ApkGuardResultScreen(
                                ApkGuardSubject.Installed(e.arguments?.getString("pkg").orEmpty()),
                                onBack = { nav.popBackStack() },
                                onAudit = { nav.navigate(ApkGuardRoutes.auditPicker(it)) },
                                onOpenInstalled = { nav.navigate(ApkGuardRoutes.app(it)) },
                                onQuarantine = { nav.navigateToQuarantine(it) },
                            )
                        }
                        composable(ApkGuardRoutes.FILE) { e ->
                            ApkGuardResultScreen(
                                ApkGuardSubject.File(e.arguments?.getString("key").orEmpty()),
                                onBack = { nav.popBackStack() },
                                onAudit = { nav.navigate(ApkGuardRoutes.auditPicker(it)) },
                                onOpenInstalled = { nav.navigate(ApkGuardRoutes.app(it)) },
                            )
                        }
                    }
                    val incoming by apkGuardRoute.collectAsState()
                    LaunchedEffect(incoming) {
                        incoming?.let { nav.navigate(it); apkGuardRoute.value = null }
                    }
                    LaunchedEffect(nav) {
                        for (feature in assistantFeatures) {
                            AssistantEntry.open(nav, feature, this@MainActivity)?.let { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show() }
                        }
                    }
                    HeyTraceVeilHooks(nav)
                    val liveCheck by openLiveCheck.collectAsState()
                    LaunchedEffect(liveCheck) {
                        // Only a check that is actually running is shown; otherwise the request is ignored.
                        if (liveCheck) {
                            openLiveCheck.value = false
                            if (vm.progress.value is AuditProgress.Running) nav.navigate("live") { launchSingleTop = true }
                        }
                    }
                    VoiceAssistantHost(nav, vm)
                }
                }
                }
                }
            }
        }
    }
}
