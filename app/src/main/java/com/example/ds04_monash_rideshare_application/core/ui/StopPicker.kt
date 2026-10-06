package com.example.ds04_monash_rideshare_application.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.ui.NamedLocation

private const val MAX_VISIBLE_MATCHES = 8

/**
 * Offer Ride's "Optional stops": numbered rows (order = order added) with a remove button each, and a
 * type-to-filter field over [places]. Same behaviour as the legacy PlaceSearchField + chips: a match is
 * a toggle (picking a picked place removes it), at most 8 matches show, filtering is [filterPlaces].
 * PlaceSearchField itself stays untouched because Find Ride still uses it.
 */
@Composable
fun StopPicker(
    places: List<NamedLocation>,
    selected: List<NamedLocation>,
    onToggle: (NamedLocation) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    var query by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val matches = filterPlaces(places, query)

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        selected.forEachIndexed { index, place ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(colors.surface)
                    .border(1.dp, colors.outlineVariant, RoundedCornerShape(14.dp)).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(colors.primaryContainer), contentAlignment = Alignment.Center) {
                    Text("${index + 1}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = colors.onPrimaryContainer)
                }
                Text(place.name, Modifier.weight(1f).padding(vertical = 8.dp), fontWeight = FontWeight.Medium)
                IconButton(onClick = { onToggle(place) }, Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = "Remove stop ${place.name}", tint = colors.onSurfaceVariant)
                }
            }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            label = { Text("Add a stop") },
            placeholder = { Text("Type a station, centre or campus") },
            singleLine = true, shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
        )
        if (focused || query.isNotBlank()) {
            matches.take(MAX_VISIBLE_MATCHES).forEach { place ->
                val picked = place in selected
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
                        .background(if (picked) colors.primaryContainer else colors.surface)
                        .border(if (picked) 1.5.dp else 1.dp, if (picked) colors.primary else colors.outlineVariant, RoundedCornerShape(14.dp))
                        .toggleable(value = picked, role = Role.Checkbox, onValueChange = {
                            onToggle(place); query = ""; focus.clearFocus()
                        })
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (picked) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = colors.onPrimaryContainer)
                    Column(Modifier.weight(1f)) {
                        Text(place.name, fontWeight = if (picked) FontWeight.Bold else FontWeight.Medium,
                            color = if (picked) colors.onPrimaryContainer else colors.onSurface)
                        Text(if (picked) "Added — tap to remove" else "Approved point", style = MaterialTheme.typography.bodySmall,
                            color = if (picked) colors.onPrimaryContainer else colors.onSurfaceVariant)
                    }
                }
            }
            when {
                matches.isEmpty() -> FieldMessage("No approved place matches \"${query.trim()}\"", isError = false)
                matches.size > MAX_VISIBLE_MATCHES ->
                    FieldMessage("${matches.size - MAX_VISIBLE_MATCHES} more - keep typing to narrow it down", isError = false)
            }
        }
    }
}
