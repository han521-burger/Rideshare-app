package com.example.ds04_monash_rideshare_application.feature.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.Repository.Remote.AnalyticsSummary
import com.example.ds04_monash_rideshare_application.Repository.Remote.DashboardSummary
import com.example.ds04_monash_rideshare_application.Repository.Remote.HotzoneEntry
import com.example.ds04_monash_rideshare_application.Repository.Remote.RequestFunnelCounts
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideSummary
import com.example.ds04_monash_rideshare_application.core.state.Resource
import com.example.ds04_monash_rideshare_application.ui.describeLocation
import com.example.ds04_monash_rideshare_application.ui.rideStatusLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

// null = All, true = trips you drove, false = trips you rode in as a passenger.
private enum class TripRoleFilter { ALL, DRIVING, RIDING }

@Composable
fun ActivityScreen(
    currentUserId: String?,
    dashboard: Resource<DashboardSummary>,
    analytics: Resource<AnalyticsSummary>,
    tripHistory: Resource<List<RideSummary>>,
    onOpenLeaderboard: () -> Unit,
    onOpenRide: (rideId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var roleFilter by rememberSaveable { mutableStateOf(TripRoleFilter.ALL) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            when {
                dashboard.isLoading -> CircularProgressIndicator()
                dashboard.errorMessage != null -> Text(dashboard.errorMessage, color = MaterialTheme.colorScheme.error)
                else -> dashboard.data?.let { DashboardCard(it, onOpenLeaderboard) }
            }
        }

        analytics.data?.let { data ->
            item { RequestFunnelCard(data) }
            if (data.hotzones.isNotEmpty()) {
                item { HotzoneCard(data.hotzones) }
            }
            if (data.tripsByDayOfWeek.any { it > 0 }) {
                item { DayOfWeekCard(data.tripsByDayOfWeek) }
            }
            if (data.mostUsedRoute != null || data.averageSeatFillRatePercent != null) {
                item { InsightCards(data) }
            }
        }

        item { Text("Trip history", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }

        item {
            // Trip history mixes rides you drove and rides you rode in - this just narrows the
            // view; the "Your impact" CO2/fuel figures above already only ever count trips you
            // drove, since only a driver's vehicle has an emissions factor to apply.
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(selected = roleFilter == TripRoleFilter.ALL, onClick = { roleFilter = TripRoleFilter.ALL }, label = { Text("All") })
                }
                item {
                    FilterChip(selected = roleFilter == TripRoleFilter.DRIVING, onClick = { roleFilter = TripRoleFilter.DRIVING }, label = { Text("Driving") })
                }
                item {
                    FilterChip(selected = roleFilter == TripRoleFilter.RIDING, onClick = { roleFilter = TripRoleFilter.RIDING }, label = { Text("Riding") })
                }
            }
        }

        when {
            tripHistory.isLoading -> item { CircularProgressIndicator() }
            tripHistory.errorMessage != null -> item { Text(tripHistory.errorMessage, color = MaterialTheme.colorScheme.error) }
            else -> {
                val trips = tripHistory.data.orEmpty().filter { trip ->
                    val wasDriving = trip.driverId == currentUserId
                    when (roleFilter) {
                        TripRoleFilter.ALL -> true
                        TripRoleFilter.DRIVING -> wasDriving
                        TripRoleFilter.RIDING -> !wasDriving
                    }
                }
                if (trips.isEmpty()) {
                    item { Text("No completed trips yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(trips, key = { it.rideId }) { trip ->
                        TripRow(trip, wasDriving = trip.driverId == currentUserId, onClick = { onOpenRide(trip.rideId) })
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardCard(dashboard: DashboardSummary, onOpenLeaderboard: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Your impact", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatColumn("${dashboard.totalRidesCompleted}", "Trips shared")
                StatColumn("%.1f kg".format(dashboard.totalCo2AvoidedKg), "Estimated CO₂ avoided")
                StatColumn("%.1f L".format(dashboard.totalFuelSavedLitres), "Fuel saved")
            }

            val streak = computeStreakMonths(dashboard.monthlyTripSummary)
            if (streak > 0) {
                Text(
                    "$streak-month sharing streak - nice work!",
                    modifier = Modifier.padding(top = 12.dp),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            androidx.compose.material3.TextButton(onClick = onOpenLeaderboard, modifier = Modifier.padding(top = 4.dp)) {
                Text("See the leaderboard")
            }
        }
    }
}

@Composable
private fun StatColumn(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// A single magnitude bar - length encodes the value, one fixed hue throughout (this app's accent
// colour), the label/value stay in text-ink rather than being painted the series colour.
@Composable
private fun MagnitudeBarRow(label: String, value: Int, maxValue: Int, modifier: Modifier = Modifier) {
    val fraction = if (maxValue <= 0) 0f else (value.toFloat() / maxValue).coerceIn(0f, 1f)
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(value.toString(), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .padding(top = 2.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
            )
        }
    }
}

// Matching funnel (docs/FEATURE_PROPOSALS.md item 5): conversion through PENDING -> outcome for
// your own sent requests and, separately, for requests sent to rides you drive.
@Composable
private fun RequestFunnelCard(data: AnalyticsSummary) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Request funnel", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (data.driverFunnel.total > 0) {
                Text(
                    "Requests to your rides",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
                FunnelBars(data.driverFunnel)
                data.averageDriverResponseMinutes?.let {
                    Text(
                        "You typically respond in ${formatMinutes(it)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            if (data.passengerFunnel.total > 0) {
                Text(
                    "Your own requests",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = if (data.driverFunnel.total > 0) 16.dp else 8.dp)
                )
                FunnelBars(data.passengerFunnel)
            }
            if (data.driverFunnel.total == 0 && data.passengerFunnel.total == 0) {
                Text(
                    "No requests yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun FunnelBars(funnel: RequestFunnelCounts) {
    val max = maxOf(funnel.pending, funnel.accepted, funnel.rejected, funnel.cancelled, funnel.completed, 1)
    Column(modifier = Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MagnitudeBarRow("Pending", funnel.pending, max)
        MagnitudeBarRow("Accepted", funnel.accepted, max)
        MagnitudeBarRow("Completed", funnel.completed, max)
        MagnitudeBarRow("Rejected", funnel.rejected, max)
        MagnitudeBarRow("Cancelled", funnel.cancelled, max)
    }
}

private fun formatMinutes(minutes: Double): String = when {
    minutes < 60 -> "%.0f min".format(minutes)
    minutes < 1440 -> "%.1f hr".format(minutes / 60.0)
    else -> "%.1f day(s)".format(minutes / 1440.0)
}

// Hotzone (docs/FEATURE_PROPOSALS.md item 5): busiest endpoints campus-wide this week, to help
// decide where to offer a ride rather than only confirming demand after the fact.
@Composable
private fun HotzoneCard(hotzones: List<HotzoneEntry>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Busiest pickup points this week", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val max = hotzones.maxOf { it.count }
            Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                hotzones.forEach { entry -> MagnitudeBarRow(entry.locationName, entry.count, max) }
            }
        }
    }
}

// Patterns over time (docs/FEATURE_PROPOSALS.md item 5): your own completed trips (driving +
// riding) by day of week.
@Composable
private fun DayOfWeekCard(tripsByDayOfWeek: List<Int>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Your trips by day of week", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val max = (tripsByDayOfWeek.maxOrNull() ?: 0).coerceAtLeast(1)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                tripsByDayOfWeek.forEachIndexed { index, count ->
                    val fraction = (count.toFloat() / max).coerceIn(0.04f, 1f)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (count > 0) count.toString() else " ", style = MaterialTheme.typography.labelSmall)
                        Box(
                            modifier = Modifier.height(60.dp).width(20.dp),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(fraction)
                                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                            )
                        }
                        Text(
                            DAY_LABELS[index],
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

// Personal insight cards (docs/FEATURE_PROPOSALS.md item 5): simple descriptive stats, not
// predictions - your most-used route, and (drivers only) your average seat-fill rate.
@Composable
private fun InsightCards(data: AnalyticsSummary) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Insights", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            data.mostUsedRoute?.let { route ->
                Text(
                    "You've travelled ${route.originLabel} → ${route.destinationLabel} ${route.count} times.",
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            data.averageSeatFillRatePercent?.let { percent ->
                Text(
                    "Your rides average %.0f%% of seats filled.".format(percent),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun TripRow(trip: RideSummary, wasDriving: Boolean, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${describeLocation(trip.originLat, trip.originLng)} → ${describeLocation(trip.destinationLat, trip.destinationLng)}",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (wasDriving) "Drove" else "Rode",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            Text(SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(trip.departureTime)))
            Text(rideStatusLabel(trip.status), color = MaterialTheme.colorScheme.primary)
        }
    }
}

// Consecutive most-recent months with at least one completed trip - a simple derived stat from
// data the dashboard already returns, not a separately tracked backend field.
private fun computeStreakMonths(monthly: List<com.example.ds04_monash_rideshare_application.Repository.Remote.MonthlyTripSummary>): Int {
    var streak = 0
    for (month in monthly.asReversed()) {
        if (month.tripsCompleted > 0) streak++ else break
    }
    return streak
}
