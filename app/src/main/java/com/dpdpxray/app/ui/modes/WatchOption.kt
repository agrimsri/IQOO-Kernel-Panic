package com.dpdpxray.app.ui.modes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.audit.WatchCopy
import com.dpdpxray.app.ui.guide.GuideLang
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.core.model.BackgroundWatches

/** The background watch's length: one chip per offered length (5, 10, 30 minutes), shared by both check sheets. */
@Composable
internal fun WatchLengthChips(minutes: Int, onPick: (Int) -> Unit, lang: GuideLang, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(WatchCopy.lengthLabel.pick(lang), style = MaterialTheme.typography.labelLarge, color = Film.boneDim)
        BackgroundWatches.MINUTES.forEach { m ->
            FilterChip(
                selected = m == minutes, onClick = { onPick(m) }, label = { Text(WatchCopy.lengthChip(m).pick(lang)) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Film.scan, selectedLabelColor = Film.base),
            )
        }
    }
}
