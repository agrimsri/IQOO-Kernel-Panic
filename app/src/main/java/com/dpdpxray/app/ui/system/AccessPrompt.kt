package com.dpdpxray.app.ui.system

import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.audit.AccessCopy
import com.dpdpxray.app.audit.AccessNeed
import com.dpdpxray.app.audit.AccessNeeds
import com.dpdpxray.app.control.DeviceChecks
import com.dpdpxray.app.ui.XrayViewModel
import com.dpdpxray.app.ui.guide.guideLang
import com.dpdpxray.app.ui.theme.Film
import kotlinx.coroutines.delay

/** The Shizuku helper app's package, opened to start the service. */
private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

/**
 * Takes the person to the one place where [AccessNeed] is switched on: the VPN consent, Accessibility settings, VPN
 * settings, the Shizuku app or Shizuku's own permission prompt. Whatever the person does there, the preflight is read
 * again when they come back.
 */
@Composable
fun rememberAccessAction(vm: XrayViewModel): (AccessNeed) -> Unit {
    val ctx = LocalContext.current
    val vpn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.refreshPreflight() }
    return remember(ctx, vm) {
        { need ->
            when (need) {
                AccessNeed.OTHER_VPN -> DeviceChecks.openVpnSettings(ctx)
                AccessNeed.VPN -> VpnService.prepare(ctx)?.let { vpn.launch(it) } ?: vm.refreshPreflight()
                AccessNeed.WATCHER -> DeviceChecks.openAccessibilitySettings(ctx)
                AccessNeed.SHIZUKU_RUNNING -> DeviceChecks.launch(ctx, SHIZUKU_PACKAGE)
                AccessNeed.SHIZUKU_PERMISSION -> vm.requestShizuku()
            }
        }
    }
}

/** Looks again each second while [need] is missing, because granting it elsewhere sends no signal back. */
@Composable
fun WatchAccess(vm: XrayViewModel, need: AccessNeed, onMet: () -> Unit) {
    val pf by vm.preflight.collectAsState()
    LaunchedEffect(need) { while (true) { delay(1_000); vm.refreshPreflight() } }
    LaunchedEffect(pf, need) { if (AccessNeeds.met(need, pf)) onMet() }
}

/**
 * When a check cannot start for want of a permission, says why in plain words and offers the button that opens the right
 * page. As soon as the permission is on and the person is back, the dialog closes and the check they asked for carries on.
 */
@Composable
fun AccessPromptHost(vm: XrayViewModel) {
    val prompt by vm.access.collectAsState()
    val act = rememberAccessAction(vm)
    val p = prompt ?: return
    val lang = guideLang()
    WatchAccess(vm, p.need) { vm.dismissAccess(); p.resume?.invoke() }
    AlertDialog(
        onDismissRequest = { vm.dismissAccess() },
        containerColor = Film.raised,
        title = { Text(AccessCopy.title(p.need).pick(lang), color = Film.bone) },
        text = {
            Column {
                Text(AccessCopy.reason(p.need).pick(lang), style = MaterialTheme.typography.bodyMedium, color = Film.boneDim)
                if (p.resume != null) {
                    Text(AccessCopy.continuesBySelf.pick(lang), style = MaterialTheme.typography.bodyMedium, color = Film.boneDim, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = { TextAction(AccessCopy.button(p.need).pick(lang), { act(p.need) }) },
        dismissButton = { TextAction(AccessCopy.notNow.pick(lang), { vm.dismissAccess() }, color = Film.boneDim) },
    )
}
