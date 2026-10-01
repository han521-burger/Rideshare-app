package com.example.ds04_monash_rideshare_application.feature.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.Repository.Remote.DashboardSummary
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideSummary
import com.example.ds04_monash_rideshare_application.core.state.Resource
import com.example.ds04_monash_rideshare_application.ui.describeLocation
import com.example.ds04_monash_rideshare_application.ui.rideStatusLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ActivityScreen(
    dashboard: Resource<DashboardSummary>,
    tripHistory: Resource<List<RideSummary>>,
    onOpenLeaderboard: () -> Unit,
    onOpenRide: (rideId: String) -> Unit,
    modifier: Modifier = Modifier
) {
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

        item { Text("Trip history", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }

        when {
            tripHistory.isLoading -> item { CircularProgressIndicator() }
            tripHistory.errorMessage != null -> item { Text(tripHistory.errorMessage, color = MaterialTheme.colorScheme.error) }
            else -> {
                val trips = tripHistory.data.orEmpty()
                if (trips.isEmpty()) {
                    item { Text("No completed trips yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(trips, key = { it.rideId }) { trip -> TripRow(trip, onClick = { onOpenRide(trip.rideId) }) }
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

@Composable
private fun TripRow(trip: RideSummary, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "${describeLocation(trip.originLat, trip.originLng)} → ${describeLocation(trip.destinationLat, trip.destinationLng)}",
                fontWeight = FontWeight.SemiBold
            )
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
