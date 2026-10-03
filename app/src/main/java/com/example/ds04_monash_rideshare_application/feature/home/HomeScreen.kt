package com.example.ds04_monash_rideshare_application.feature.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideRequestSummary
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideSummary
import com.example.ds04_monash_rideshare_application.core.state.AppMode
import com.example.ds04_monash_rideshare_application.core.state.Resource
import com.example.ds04_monash_rideshare_application.ui.rideStatusLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    activeMode: AppMode,
    currentUserId: String?,
    confirmedRides: List<RideSummary>,
    myDriverRides: Resource<List<RideSummary>>,
    myRequests: Resource<List<RideRequestSummary>>,
    onRetry: () -> Unit,
    onOpenRide: (rideId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val state = homeContent(activeMode, currentUserId, confirmedRides, myRequests, myDriverRides)
    LazyColumn(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("home-list"),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "heading") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(HOME_TITLE, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() })
                Text(homeSubtitle(activeMode), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        when (state) {
            HomeContent.Loading -> item(key = "loading") { HomeSkeletons() }
            HomeContent.Error -> item(key = "error") { HomeStateCard(activeMode, onRetry) }
            HomeContent.Empty -> item(key = "empty") { HomeStateCard(activeMode) }
            is HomeContent.Rides -> items(state.rides, key = { it.rideId }) { ride ->
                HomeRideCard(ride, activeMode == AppMode.DRIVER) { onOpenRide(ride.rideId) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeRideCard(ride: RideSummary, driving: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("home-ride-${ride.rideId}"),
        shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, colors.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = colors.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(color = colors.secondaryContainer, contentColor = colors.onSecondaryContainer,
                    shape = RoundedCornerShape(12.dp)) {
                    Text(rideStatusLabel(ride.status), Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }
                Text(SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(ride.departureTime)),
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp))
            }
            HomeRoute(ride)
            HorizontalDivider(color = colors.outlineVariant)
            if (driving) {
                val seats = homeSeats(ride)
                LinearProgressIndicator(progress = { seats.fraction }, modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
                    color = colors.primary, trackColor = colors.surfaceVariant)
                Text(seats.label, style = MaterialTheme.typography.bodyMedium)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(ride.driver?.displayName ?: "Driver", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    ride.vehicle?.let { vehicle ->
                        Text("${vehicle.make} ${vehicle.model}${vehicle.registrationMasked?.let { " \u00B7 $it" } ?: ""}",
                            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                    if (ride.driverEnRoute) Text("Driver is on the way", style = MaterialTheme.typography.bodyMedium,
                        color = colors.primary, fontWeight = FontWeight.SemiBold)
                }
            }
            // No nested click target: the Card owns the single accessible navigation action.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("View ride details", Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelLarge,
                    color = colors.primary)
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(18.dp), tint = colors.primary)
            }
        }
    }
}

@Composable
private fun HomeRoute(ride: RideSummary) {
    val (origin, destination) = homeRoute(ride)
    val colors = MaterialTheme.colorScheme
    Column {
        listOf("Origin" to origin, "Destination" to destination).forEachIndexed { index, (label, place) ->
            Column(Modifier.fillMaxWidth().drawBehind {
                val x = 6.dp.toPx(); val y = 9.dp.toPx(); val radius = 5.dp.toPx()
                if (index == 0) {
                    drawLine(colors.outlineVariant, Offset(x, y), Offset(x, size.height + y), 2.dp.toPx())
                    drawCircle(colors.primary, radius, Offset(x, y))
                } else {
                    drawCircle(colors.surface, radius, Offset(x, y))
                    drawCircle(colors.primary, radius, Offset(x, y), style = Stroke(2.dp.toPx()))
                }
            }.padding(start = 28.dp, bottom = if (index == 0) 16.dp else 0.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                Text(place, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun HomeStateCard(mode: AppMode, retry: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val error = retry != null
    Surface(Modifier.fillMaxWidth().testTag(if (error) "home-error" else "home-empty"),
        shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, colors.outlineVariant),
        color = if (error) colors.errorContainer else colors.surface,
        contentColor = if (error) colors.onErrorContainer else colors.onSurface) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (error) Icons.Rounded.ErrorOutline else Icons.Rounded.DirectionsCar, null,
                Modifier.size(44.dp).background(if (error) colors.errorContainer else colors.surfaceVariant, CircleShape).padding(10.dp),
                tint = if (error) colors.error else colors.onSurfaceVariant)
            Text(if (error) "Couldn't load your rides" else HOME_EMPTY_TITLE,
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, modifier = Modifier.semantics { heading(); if (error) liveRegion = LiveRegionMode.Polite })
            Text(if (error) "Check your connection, then try again." else homeEmptyMessage(mode),
                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            if (retry != null) Button(onClick = retry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry") }
        }
    }
}

@Composable
private fun HomeSkeletons() {
    // Static neutral placeholders respect reduced motion without a perpetual shimmer.
    Column(Modifier.testTag("home-loading").clearAndSetSemantics {
        contentDescription = "Loading your rides"; liveRegion = LiveRegionMode.Polite
    }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(2) {
            Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    listOf(.35f, .8f, .65f, .9f).forEach { width ->
                        Box(Modifier.fillMaxWidth(width).height(20.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)))
                    }
                }
            }
        }
    }
}
