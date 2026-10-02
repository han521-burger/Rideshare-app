package com.example.ds04_monash_rideshare_application.feature.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.core.state.AppMode

// Applied only to Activity and its shell. Other tabs retain their existing role-based palette.
@Composable
fun activityColorScheme(dark: Boolean = isSystemInDarkTheme()): ColorScheme = if (dark) darkColorScheme(
    primary = Color(0xFFFFB77F), onPrimary = Color(0xFF512400),
    primaryContainer = Color(0xFF63310E), onPrimaryContainer = Color(0xFFFFDCC3),
    secondary = Color(0xFFA7C4DE), onSecondary = Color(0xFF102E44),
    secondaryContainer = Color(0xFF263F54), onSecondaryContainer = Color(0xFFD9E9F6),
    background = Color(0xFF171B22), onBackground = Color(0xFFECEEF4),
    surface = Color(0xFF202630), onSurface = Color(0xFFECEEF4),
    surfaceVariant = Color(0xFF303741), onSurfaceVariant = Color(0xFFCCBFB4),
    outline = Color(0xFF9F9186), outlineVariant = Color(0xFF51473F),
    error = Color(0xFFFFB4AB)
) else lightColorScheme(
    primary = Color(0xFFA44708), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDEC3), onPrimaryContainer = Color(0xFF78330A),
    secondary = Color(0xFF4D6D89), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6EEF5), onSecondaryContainer = Color(0xFF304B65),
    background = Color(0xFFFCF5ED), onBackground = Color(0xFF202D45),
    surface = Color(0xFFFFFEFB), onSurface = Color(0xFF202D45),
    surfaceVariant = Color(0xFFF8E9D9), onSurfaceVariant = Color(0xFF715D4D),
    outline = Color(0xFF9D8270), outlineVariant = Color(0xFFE6D4C3),
    error = Color(0xFFAD3530)
)

@Composable
internal fun ActivityTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = activityColorScheme(), typography = MaterialTheme.typography, content = content)
}

@Composable
internal fun outcomeColors(): List<Color> = if (isSystemInDarkTheme()) listOf(
    Color(0xFFE5AC50), Color(0xFF98B5D0), Color(0xFF8AC5A2), Color(0xFFF19B95), Color(0xFFB4A397), Color(0xFFB7A6CE)
) else listOf(Color(0xFFB57924), Color(0xFF4D6D89), Color(0xFF40825E), Color(0xFFBA453A), Color(0xFFAD9987), Color(0xFF806C99))

@Composable
fun ActivityHeader(name: String, mode: AppMode, onModeChange: (AppMode) -> Unit) {
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Monash Rideshare", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Hi, $name", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape).clearAndSetSemantics {},
                contentAlignment = Alignment.Center) {
                Text(name.trim().take(1).uppercase(), color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
            }
        }
        ActivitySegments(listOf("Passenger", "Driver"), if (mode == AppMode.PASSENGER) 0 else 1,
            onSelect = { onModeChange(if (it == 0) AppMode.PASSENGER else AppMode.DRIVER) }, filled = true)
    }
}

@Composable
internal fun ActivitySegments(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, filled: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(colors.surfaceVariant)
        .padding(3.dp).selectableGroup()) {
        labels.forEachIndexed { index, label ->
            val active = selected == index
            Box(Modifier.weight(1f).clip(RoundedCornerShape(24.dp))
                .background(if (active) { if (filled) colors.primary else colors.surface } else Color.Transparent)
                .selectable(selected = active, role = Role.RadioButton, onClick = { onSelect(index) })
                .heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                Text(label, style = MaterialTheme.typography.labelLarge,
                    color = if (active && filled) colors.onPrimary else colors.onSurface,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
            }
        }
    }
}
