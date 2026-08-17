package com.example.ds04_monash_rideshare_application.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.core.navigation.AppDestination
import com.example.ds04_monash_rideshare_application.core.state.AppMode
import com.example.ds04_monash_rideshare_application.ui.theme.DriverAmber
import com.example.ds04_monash_rideshare_application.ui.theme.PassengerTeal

fun modeAccent(mode: AppMode): Color = if (mode == AppMode.PASSENGER) PassengerTeal else DriverAmber

// The shell for all four bottom-nav tabs: a top bar carrying the Passenger/Driver toggle (teal
// while passenger, amber while driver - the whole tab content picks up the same accent so the
// switch is visible everywhere, not just in the toggle itself) and the nav bar with badges for
// counts that need attention (pending requests).
@Composable
fun BottomNavScaffold(
    activeMode: AppMode,
    onModeChange: (AppMode) -> Unit,
    currentTab: AppDestination,
    onTabSelected: (AppDestination) -> Unit,
    requestsBadgeCount: Int,
    greetingName: String,
    modifier: Modifier = Modifier,
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit
) {
    val accent = modeAccent(activeMode)
    val colorScheme = MaterialTheme.colorScheme.copy(primary = accent, secondary = accent)

    MaterialTheme(colorScheme = colorScheme, typography = MaterialTheme.typography) {
        Scaffold(
            modifier = modifier,
            topBar = {
                Surface(shadowElevation = 2.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Hi, $greetingName",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        ModeToggle(activeMode = activeMode, onModeChange = onModeChange)
                    }
                }
            },
            bottomBar = {
                NavigationBar {
                    AppDestination.tabDestinations.forEach { destination ->
                        val glyph = when (destination) {
                            AppDestination.Home -> "Home"
                            AppDestination.Requests -> "Reqs"
                            AppDestination.Activity -> "Stats"
                            else -> "You"
                        }
                        val badgeCount = if (destination == AppDestination.Requests) requestsBadgeCount else 0
                        NavigationBarItem(
                            selected = currentTab == destination,
                            onClick = { onTabSelected(destination) },
                            icon = {
                                if (badgeCount > 0) {
                                    BadgedBox(badge = { Badge { Text(badgeCount.toString()) } }) {
                                        NavGlyph(glyph)
                                    }
                                } else {
                                    NavGlyph(glyph)
                                }
                            },
                            label = { Text(destination.title) }
                        )
                    }
                }
            },
            content = content
        )
    }
}

// No icon font is wired up (avoids taking a dependency on material-icons-extended just for four
// tabs) - a short label reads fine at nav-bar size and needs nothing extra in build.gradle.
@Composable
private fun NavGlyph(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
}

@Composable
private fun ModeToggle(activeMode: AppMode, onModeChange: (AppMode) -> Unit) {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp))
            .padding(3.dp)
    ) {
        ModeToggleOption("Passenger", selected = activeMode == AppMode.PASSENGER, accent = PassengerTeal) {
            onModeChange(AppMode.PASSENGER)
        }
        ModeToggleOption("Driver", selected = activeMode == AppMode.DRIVER, accent = DriverAmber) {
            onModeChange(AppMode.DRIVER)
        }
    }
}

@Composable
private fun ModeToggleOption(label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(if (selected) accent else Color.Transparent, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}
