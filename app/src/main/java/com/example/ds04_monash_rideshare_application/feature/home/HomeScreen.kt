package com.example.ds04_monash_rideshare_application.feature.home

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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideStatus
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideSummary
import com.example.ds04_monash_rideshare_application.core.state.AppMode
import com.example.ds04_monash_rideshare_application.core.state.Resource
import com.example.ds04_monash_rideshare_application.ui.describeLocation
import com.example.ds04_monash_rideshare_application.ui.rideStatusLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Browsing open rides to join now lives on the Requests tab (its "Rides I can join" sub-tab) -
// Home is just "what's already mine": confirmed rides for a passenger, active (has at least one
// passenger) posted routes for a driver. A driver's still-empty offers live on the Requests tab's
// "My Offers" sub-tab instead - the moment one gets its first passenger it moves here.
@Composable
fun HomeScreen(
    activeMode: AppMode,
    currentUserId: String?,
    confirmedRides: List<RideSummary>,
    myDriverRides: Resource<List<RideSummary>>,
    onOpenRide: (rideId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (activeMode == AppMode.PASSENGER) {
            val ridingConfirmed = confirmedRides.filter { it.driverId != currentUserId }
            item { Text("Your upcoming rides", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            if (ridingConfirmed.isEmpty()) {
                item {
                    Text(
                        "No confirmed rides yet - check the Requests tab to browse and join one.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(ridingConfirmed, key = { it.rideId }) { ride -> ConfirmedRideCard(ride, onClick = { onOpenRide(ride.rideId) }) }
            }
        } else {
            item { Text("Your posted rides", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            when {
                myDriverRides.isLoading -> item { CircularProgressIndicator() }
                myDriverRides.errorMessage != null -> item { Text(myDriverRides.errorMessage, color = MaterialTheme.colorScheme.error) }
                else -> {
                    val list = myDriverRides.data.orEmpty().filter { it.status == RideStatus.CONFIRMED }
                    if (list.isEmpty()) {
                        item {
                            Text(
                                "No active rides yet - check the Requests tab to offer one or accept someone's request.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        items(list, key = { it.rideId }) { ride -> DriverRideCard(ride, onClick = { onOpenRide(ride.rideId) }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmedRideCard(ride: RideSummary, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "${describeLocation(ride.originLat, ride.originLng)} → ${describeLocation(ride.destinationLat, ride.destinationLng)}",
                fontWeight = FontWeight.SemiBold
            )
            Text(SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(ride.departureTime)))
            Text(ride.driver?.displayName ?: "Driver", color = MaterialTheme.colorScheme.onSurfaceVariant)
            ride.vehicle?.let { vehicle ->
                Text(
                    "${vehicle.make} ${vehicle.model}${vehicle.registrationMasked?.let { " · $it" } ?: ""}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (ride.driverEnRoute) {
                Text(
                    "Driver is on the way — %.1f km · %d min".format(ride.distanceMeters / 1000.0, ride.estimatedDurationSeconds / 60),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun DriverRideCard(ride: RideSummary, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "${describeLocation(ride.originLat, ride.originLng)} → ${describeLocation(ride.destinationLat, ride.destinationLng)}",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(ride.departureTime)))
            Text(rideStatusLabel(ride.status), color = MaterialTheme.colorScheme.primary)

            val filled = (ride.seatsTotal - ride.seatsAvailable).coerceIn(0, ride.seatsTotal)
            LinearProgressIndicator(
                progress = { if (ride.seatsTotal > 0) filled.toFloat() / ride.seatsTotal else 0f },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
            Text("$filled / ${ride.seatsTotal} seats filled", style = MaterialTheme.typography.bodySmall)
        }
    }
}
