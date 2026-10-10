package com.traceveil.riskdemo

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

private val BannerRed = Color(0xFFB71C1C)
private val Navy = Color(0xFF1F3A5F)
private val PageGrey = Color(0xFFF2F4F7)

/** Results of the latest network attempts, kept for the life of the process so a screen rotation does not lose them. */
private object SignalLog {
    val attempts = mutableStateListOf<DemoSignals.Attempt>()
    var running by mutableStateOf(false)
}

/**
 * A single screen that looks like a challan payment page, under a permanent red TEST banner.
 * Its buttons only open Android's own permission dialog and Settings pages so TraceVeil can observe them.
 */
class ChallanActivity : ComponentActivity() {
    /** Bumped on every resume so the on-screen permission states are re-read after visiting Settings. */
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) runNetworkSignals()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Navy)) {
                Column(Modifier.fillMaxSize().background(PageGrey).safeDrawingPadding()) {
                    TestBanner()
                    ChallanPage()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }

    private fun runNetworkSignals() {
        if (SignalLog.running) return
        SignalLog.running = true
        thread(name = "riskdemo-signals", isDaemon = true) {
            val result = DemoSignals.run()
            runOnUiThread {
                SignalLog.attempts.clear()
                SignalLog.attempts.addAll(result)
                SignalLog.running = false
            }
        }
    }

    private fun openSettings(primary: Intent, fallback: Intent? = null) {
        try {
            startActivity(primary)
        } catch (e: ActivityNotFoundException) {
            fallback?.let { runCatching { startActivity(it) } }
        }
    }

    private fun smsGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    private fun overlayAllowed() = Settings.canDrawOverlays(this)

    private fun accessibilityOn(): Boolean = getSystemService(AccessibilityManager::class.java)
        ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        ?.any { it.resolveInfo?.serviceInfo?.packageName == packageName } == true

    @Composable
    private fun TestBanner() {
        Column(Modifier.fillMaxWidth().background(BannerRed).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("⚠ " + stringResource(R.string.test_banner), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(stringResource(R.string.test_banner_detail), color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }

    @Composable
    private fun ChallanPage() {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.fillMaxWidth().background(Navy, RoundedCornerShape(12.dp)).padding(16.dp)) {
                Text("Traffic e-Challan payment", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("TEST page imitating a fake challan app. Not a government or bank service.", color = Color(0xFFD6E2F0), fontSize = 13.sp)
            }

            Card("Challan details (TEST data)") {
                Field("Challan number", "TEST-000000")
                Field("Vehicle number", "XX 00 TEST 0000")
                Field("Offence", "None. This is a TraceVeil test page.")
                Field("Amount due", "Nothing to pay")
                Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("Pay now (disabled in this TEST app)")
                }
            }

            PermissionCard()
            NetworkCard()

            Text(
                "Package $packageName · TraceVeil demo fixture. Uninstall it from Settings > Apps when the demo is over.",
                color = Color.Gray, fontSize = 12.sp,
            )
        }
    }

    @Composable
    private fun PermissionCard() {
        val tick = resumeTick // re-read the states below whenever the activity resumes
        val sms = remember(tick) { smsGranted() }
        val accessibility = remember(tick) { accessibilityOn() }
        val overlay = remember(tick) { overlayAllowed() }
        var smsAnswer by remember { mutableStateOf<String?>(null) }
        val smsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            smsAnswer = if (granted) "SMS permission granted. This app still never reads SMS." else "SMS permission not granted."
            resumeTick++
        }
        Card("Permission prompts for TraceVeil to observe") {
            Text("Each button only opens Android's own dialog or Settings page. Nothing here is switched on automatically, and granting access gives this app nothing it uses.", fontSize = 13.sp)

            Button(onClick = { smsLauncher.launch(Manifest.permission.READ_SMS) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("Request SMS permission")
            }
            StateLine(smsAnswer ?: if (sms) "SMS permission: granted (never used)" else "SMS permission: not granted")

            OutlinedButton(onClick = { openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, modifier = Modifier.fillMaxWidth()) {
                Text("Open accessibility settings")
            }
            StateLine(if (accessibility) "Demo accessibility service: on (it ignores every event)" else "Demo accessibility service: off")

            OutlinedButton(
                onClick = {
                    openSettings(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open display-over-apps settings") }
            StateLine(if (overlay) "Display over other apps: allowed (this app never draws over other apps)" else "Display over other apps: not allowed")
        }
    }

    @Composable
    private fun NetworkCard() {
        Card("Network attempts made on launch") {
            Text(
                "Lookups of reserved names that can never exist (.invalid, .example) and one connection attempt to " +
                    "${DemoSignals.TEST_NET_ADDRESS.hostAddress}, a reserved documentation address. No data is sent.",
                fontSize = 13.sp,
            )
            when {
                SignalLog.running -> StateLine("Running…")
                SignalLog.attempts.isEmpty() -> StateLine("Not run yet.")
                else -> SignalLog.attempts.forEach { a ->
                    val kind = if (a.kind == DemoSignals.Kind.DNS_LOOKUP) "DNS lookup" else "TCP connect"
                    Column(Modifier.padding(top = 6.dp)) {
                        Text("$kind · ${a.target}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(a.detail, fontSize = 12.sp, color = if (a.reached) BannerRed else Color.DarkGray)
                    }
                }
            }
            TextButton(onClick = { runNetworkSignals() }, enabled = !SignalLog.running) { Text("Repeat the network attempts") }
        }
    }

    @Composable
    private fun Card(title: String, content: @Composable () -> Unit) {
        Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Navy)
            content()
        }
    }

    @Composable
    private fun Field(label: String, value: String) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, color = Color.Gray, fontSize = 14.sp, modifier = Modifier.weight(0.45f))
            Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(0.55f))
        }
    }

    @Composable
    private fun StateLine(text: String) {
        Text(text, fontSize = 12.sp, color = Color.DarkGray)
    }
}
