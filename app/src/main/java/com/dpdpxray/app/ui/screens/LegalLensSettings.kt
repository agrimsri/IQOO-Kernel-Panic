package com.dpdpxray.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.data.LegalLensSetting
import com.dpdpxray.app.ui.XrayViewModel
import com.dpdpxray.app.ui.components.Callout
import com.dpdpxray.app.ui.components.Hairline
import com.dpdpxray.app.ui.components.Note
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.Radii
import com.dpdpxray.core.law.LegalProfile

/**
 * Settings → Legal lens. "Automatic" follows the device region (else the global baseline); any other row pins a lens.
 * The lens changes which law results and reports quote, never the evidence or the consent-first score.
 */
@Composable
fun LegalLensSection(vm: XrayViewModel, setting: String) {
    val auto = remember { LegalLensSetting.resolve(LegalLensSetting.AUTOMATIC, java.util.Locale.getDefault().country) }
    val chosen = LegalProfile.parse(setting)
    Note(
        "Choose which law results and reports quote. The evidence and the consent-first score stay the same; each finding " +
            "shows whether this law requires what it tests, treats it as context, or does not cover it. Not legal advice.",
    )
    Column(Modifier.padding(top = 8.dp).selectableGroup()) {
        LensOption(
            title = "Automatic",
            detail = "${auto.describe(vm.legal).removePrefix("Automatic: ")}. Uses the phone's region setting, else the global baseline.",
            selected = chosen == null,
        ) { vm.updateSettings { it.copy(legalProfile = LegalLensSetting.AUTOMATIC) } }
        for (id in vm.legal.profiles) {
            val p = vm.legal.profile(id)
            Hairline()
            LensOption(title = p.name, detail = p.laws, selected = chosen == id) { vm.updateSettings { it.copy(legalProfile = id.name) } }
        }
    }
    if (chosen == null) auto.hint?.let { Callout(it, Modifier.padding(top = 8.dp)) }
}

@Composable
private fun LensOption(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(Radii.small))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Film.scan, unselectedColor = Film.boneDim))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Film.bone)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = Film.boneDim)
        }
    }
}
