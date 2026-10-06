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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.core.state.AppMode

// Which role the app is in right now, provided once at the top of RideshareNavHost so every
// screen's theme can follow it without passing the mode through each call.
val LocalAppMode = compositionLocalOf { AppMode.PASSENGER }

// The branded scheme shared by the shell and the Home, Requests, Activity and Profile tabs (and the
// Vehicles sub-screen and ride flows). Driver keeps the warm orange; Passenger swaps the primary
// family for a deep blue so the two roles are easy to tell apart. Blue/orange stays distinguishable
// for colour-blind users, and every role colour keeps >= 4.5:1 contrast for text.
@Composable
fun activityColorScheme(dark: Boolean = isSystemInDarkTheme(), mode: AppMode = LocalAppMode.current): ColorScheme {
    val base = baseColorScheme(dark)
    if (mode == AppMode.DRIVER) return base
    return if (dark) base.copy(
        primary = Color(0xFFA7C4DE), onPrimary = Color(0xFF102E44),
        primaryContainer = Color(0xFF263F54), onPrimaryContainer = Color(0xFFD9E9F6)
    ) else base.copy(
        primary = Color(0xFF2F5D7C), onPrimary = Color.White,
        primaryContainer = Color(0xFFDCE8F2), onPrimaryContainer = Color(0xFF173A54)
    )
}

private fun baseColorScheme(dark: Boolean): ColorScheme = if (dark) darkColorScheme(
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
fun ActivityHeader(name: String, mode: AppMode, onModeChange: (AppMode) -> Unit, driverChecking: Boolean = false) {
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
            onSelect = { onModeChange(if (it == 0) AppMode.PASSENGER else AppMode.DRIVER) }, filled = true,
            busyIndex = if (driverChecking) 1 else null, busyLabel = "Checking vehicle…")
    }
}

// [busyIndex] marks one segment as working (e.g. Driver while its vehicle lookup runs). The selection
// does not move until the work succeeds; the busy segment shows a spinner and a text label.
@Composable
internal fun ActivitySegments(
    labels: List<String>, selected: Int, onSelect: (Int) -> Unit, filled: Boolean = false,
    busyIndex: Int? = null, busyLabel: String = ""
) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(colors.surfaceVariant)
        .padding(3.dp).selectableGroup()) {
        labels.forEachIndexed { index, label ->
            val active = selected == index
            val busy = busyIndex == index
            Box(Modifier.weight(1f).clip(RoundedCornerShape(24.dp))
                .background(if (active) { if (filled) colors.primary else colors.surface } else if (busy) colors.surface else Color.Transparent)
                .then(if (busy) Modifier.drawBehind {
                    drawRoundRect(colors.primary, cornerRadius = CornerRadius(24.dp.toPx()),
                        style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
                } else Modifier)
                .selectable(selected = active, role = Role.RadioButton, onClick = { onSelect(index) })
                // The segment keeps its own name ("Driver") for TalkBack; the visible busy text is decorative.
                .semantics { if (busy) { contentDescription = label; stateDescription = "Checking vehicle"; liveRegion = LiveRegionMode.Polite } }
                .heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                if (busy) {
                    Row(Modifier.clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.primary)
                        Text(busyLabel, style = MaterialTheme.typography.labelLarge, color = colors.onSurface, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Text(label, style = MaterialTheme.typography.labelLarge,
                        color = if (active && filled) colors.onPrimary else colors.onSurface,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }
    }
}
