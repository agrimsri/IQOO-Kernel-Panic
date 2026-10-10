package com.dpdpxray.app.ui.system

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.Radii

/**
 * A segmented control: a quiet track with the chosen segment lifted onto the surface colour. Used where one screen has
 * two or three closely related views (Live check / APK Guard, report filters). Reads as a radio group to TalkBack.
 */
@Composable
fun SegmentedControl(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val track = RoundedCornerShape(12.dp)
    Row(modifier.fillMaxWidth().clip(track).background(Film.fill).padding(3.dp).selectableGroup()) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (on) Film.raised else Color.Transparent)
                    .selectable(selected = on, role = Role.RadioButton) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = if (on) Film.bone else Film.boneDim, maxLines = 1)
            }
        }
    }
}

/** A search field: filled, no outline, with a clear button. */
@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        placeholder = { Text(placeholder, color = Film.boneDim) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = Film.boneDim) },
        trailingIcon = {
            if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear search", tint = Film.boneDim) }
        },
        shape = RoundedCornerShape(Radii.small),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, cursorColor = Film.scan,
            focusedContainerColor = Film.fill, unfocusedContainerColor = Film.fill,
            focusedTextColor = Film.bone, unfocusedTextColor = Film.bone,
        ),
        textStyle = MaterialTheme.typography.bodyLarge,
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
