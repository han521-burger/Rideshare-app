package com.example.ds04_monash_rideshare_application.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.ui.NamedLocation

private const val MAX_VISIBLE_MATCHES = 8

// Type-to-filter picker over a fixed list of places, for lists too long for a row of chips
// (the 25 approved points). Typing "stat" narrows to the stations; tapping one picks it.
@Composable
fun PlaceSearchField(
    label: String,
    places: List<NamedLocation>,
    onPick: (NamedLocation) -> Unit,
    modifier: Modifier = Modifier,
    isPicked: (NamedLocation) -> Boolean = { false }
) {
    val focus = LocalFocusManager.current
    var query by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val matches = filterPlaces(places, query)

    Column(modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(label) },
            placeholder = { Text("Type a station, centre or campus") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
        )
        if (focused || query.isNotBlank()) {
            matches.take(MAX_VISIBLE_MATCHES).forEach { place ->
                val picked = isPicked(place)
                Text(
                    text = if (picked) "✓ ${place.name}" else place.name,
                    fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onPick(place)
                            query = ""
                            focus.clearFocus()
                        }
                        .padding(horizontal = 4.dp, vertical = 12.dp)
                )
                HorizontalDivider()
            }
            when {
                matches.isEmpty() -> Text(
                    "No approved place matches \"${query.trim()}\"",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
                matches.size > MAX_VISIBLE_MATCHES -> Text(
                    "${matches.size - MAX_VISIBLE_MATCHES} more - keep typing to narrow it down",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

internal fun filterPlaces(places: List<NamedLocation>, query: String): List<NamedLocation> {
    val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return places.filter { place -> words.all { place.name.contains(it, ignoreCase = true) } }
}
