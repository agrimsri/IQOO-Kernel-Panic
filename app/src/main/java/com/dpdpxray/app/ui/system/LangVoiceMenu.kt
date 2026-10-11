package com.dpdpxray.app.ui.system

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.ui.guide.GlyphIcon
import com.dpdpxray.app.ui.guide.GuideLang
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.guide.LocalGuide
import com.dpdpxray.app.ui.theme.Film

/**
 * One place for guidance language and read-aloud (UI_UX_REFACTOR_GUIDE.md D5), replacing the per-screen pills and the
 * per-banner toggles. It drives the same [LocalGuide] state as before; nothing about how guidance is spoken changes.
 * Shows nothing when no guide state is provided.
 */
@Composable
fun LangVoiceButton(modifier: Modifier = Modifier) {
    val guide = LocalGuide.current ?: return
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Box(
            Modifier.size(48.dp).clickable(role = Role.Button) { open = true }
                .semantics { contentDescription = "Language and read aloud" },
            contentAlignment = Alignment.Center,
        ) {
            Text(if (guide.lang == GuideLang.HI) "हि" else "A", style = MaterialTheme.typography.titleMedium, color = Film.boneDim)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Film.raised) {
            DropdownMenuItem(
                text = { Text("English") },
                trailingIcon = { if (guide.lang == GuideLang.EN) GlyphIcon(Glyph.CHECK, Film.scan, size = 18.dp) },
                onClick = { guide.setLang(GuideLang.EN); open = false },
            )
            DropdownMenuItem(
                text = { Text("हिन्दी") },
                trailingIcon = { if (guide.lang == GuideLang.HI) GlyphIcon(Glyph.CHECK, Film.scan, size = 18.dp) },
                onClick = { guide.setLang(GuideLang.HI); open = false },
            )
            DropdownMenuItem(
                text = { Text(if (guide.lang == GuideLang.HI) "पढ़कर सुनाएँ" else "Read aloud") },
                trailingIcon = { if (guide.spoken) GlyphIcon(Glyph.CHECK, Film.scan, size = 18.dp) },
                onClick = { guide.setSpoken(!guide.spoken); open = false },
            )
        }
    }
}
