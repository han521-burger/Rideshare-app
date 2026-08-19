package com.example.ds04_monash_rideshare_application.core.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideRequestSummary
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
    driverRequest: RideRequestSummary?,
    driverRequestCount: Int,
    isRequestActionInFlight: Boolean,
    requestActionError: String?,
    onReviewDriverRequest: (String) -> Unit,
    onAcceptDriverRequest: (String) -> Unit,
    onDeclineDriverRequest: (String) -> Unit,
    greetingName: String,
    modifier: Modifier = Modifier,
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit
) {
    val accent = modeAccent(activeMode)
    val colorScheme = MaterialTheme.colorScheme.copy(primary = accent, secondary = accent)
    var displayedRequest by remember { mutableStateOf(driverRequest) }

    LaunchedEffect(driverRequest) {
        if (driverRequest != null) displayedRequest = driverRequest
    }

    MaterialTheme(colorScheme = colorScheme, typography = MaterialTheme.typography) {
        Box(modifier = modifier) {
            Scaffold(
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
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 6.dp
                    ) {
                        AppDestination.tabDestinations.forEach { destination ->
                            val selected = currentTab == destination
                            val badgeCount = if (destination == AppDestination.Requests) requestsBadgeCount else 0
                            NavigationBarItem(
                                selected = selected,
                                onClick = { onTabSelected(destination) },
                                icon = {
                                    if (badgeCount > 0) {
                                        BadgedBox(badge = { Badge { Text(badgeCount.toString()) } }) {
                                            DestinationIcon(destination)
                                        }
                                    } else {
                                        DestinationIcon(destination)
                                    }
                                },
                                label = {
                                    Text(
                                        text = destination.title,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = accent,
                                    selectedTextColor = accent,
                                    indicatorColor = accent.copy(alpha = 0.14f),
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                },
                content = content
            )

            AnimatedVisibility(
                visible = activeMode == AppMode.DRIVER && driverRequest != null,
                enter = fadeIn(tween(350)) + slideInVertically(tween(350)) { -it / 2 },
                exit = fadeOut(tween(220)) + slideOutVertically(tween(220)) { -it / 3 },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 72.dp, start = 12.dp, end = 12.dp)
                    .widthIn(max = 600.dp)
                    .zIndex(2f)
            ) {
                (driverRequest ?: displayedRequest)?.let { request ->
                    DriverRequestNotification(
                        request = request,
                        totalWaiting = driverRequestCount,
                        isActionInFlight = isRequestActionInFlight,
                        errorMessage = requestActionError,
                        onReview = { onReviewDriverRequest(request.requestId) },
                        onAccept = { onAcceptDriverRequest(request.requestId) },
                        onDecline = { onDeclineDriverRequest(request.requestId) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DestinationIcon(destination: AppDestination) {
    val icon: ImageVector = when (destination) {
        AppDestination.Home -> Icons.Rounded.Home
        AppDestination.Requests -> Icons.Rounded.DirectionsCar
        AppDestination.Activity -> Icons.Rounded.BarChart
        AppDestination.Profile -> Icons.Rounded.Person
        else -> Icons.Rounded.Home
    }

    Icon(
        imageVector = icon,
        contentDescription = destination.title
    )
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
