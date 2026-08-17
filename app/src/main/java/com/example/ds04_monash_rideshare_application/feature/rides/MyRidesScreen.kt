package com.example.ds04_monash_rideshare_application.feature.rides

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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.Database.Passenger.SeatRequest
import com.example.ds04_monash_rideshare_application.Database.RideShare.RideOffer
import com.example.ds04_monash_rideshare_application.Repository.Remote.RequestWithRide
import com.example.ds04_monash_rideshare_application.core.state.Resource
import com.example.ds04_monash_rideshare_application.core.ui.RideshareScaffold
import com.example.ds04_monash_rideshare_application.ui.describeLocation
import com.example.ds04_monash_rideshare_application.ui.requestStatusLabel
import com.example.ds04_monash_rideshare_application.ui.rideStatusLabel
import java.text.SimpleDateFormat
import java.util.Locale

// One combined, chronological list - rides you're driving AND rides you've requested a seat on
// used to be two separate sections with raw backend status strings (OPEN/CONFIRMED/PENDING/
// ACCEPTED/...), which read as two unrelated features instead of "my rides". A small role badge
// (Driving/Passenger) plus one plain-language status line replaces that.
private data class MyRideEntry(
    val key: String,
    val roleLabel: String,
    val departureTime: Long,
    val statusLabel: String,
    val locationText: String,
    val seatsText: String,
    val onClick: () -> Unit
)

@Composable
fun MyRidesScreen(
    driverRides: Resource<List<RideOffer>>,
    myRequests: Resource<List<RequestWithRide>>,
    onOpenDriverRide: (RideOffer) -> Unit,
    onOpenRequest: (SeatRequest) -> Unit,
    onRefresh: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    RideshareScaffold(
        title = "My Rides",
        canNavigateBack = true,
        onNavigateBack = onNavigateBack,
        actionLabel = "Refresh",
        onAction = onRefresh,
        modifier = modifier
    ) { contentPadding ->
        if (driverRides.isLoading || myRequests.isLoading) {
            Column(
                modifier = Modifier.padding(contentPadding).fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) { CircularProgressIndicator() }
            return@RideshareScaffold
        }

        val drivingEntries = driverRides.data.orEmpty().map { ride ->
            MyRideEntry(
                key = "drive-${ride.rideId}",
                roleLabel = "Driving",
                departureTime = ride.departureTime,
                statusLabel = rideStatusLabel(ride.status),
                locationText = "${describeLocation(ride.originLat, ride.originLng)} -> ${describeLocation(ride.destinationLat, ride.destinationLng)}",
                seatsText = "${ride.seatsAvailable} seat(s) available",
                onClick = { onOpenDriverRide(ride) }
            )
        }
        // A request whose ride is missing shouldn't happen (backend always attaches it, see
        // GET /requests(/mine)), but skip rather than crash on a null dereference if it ever does.
        val requestedEntries = myRequests.data.orEmpty().mapNotNull { requestWithRide ->
            val ride = requestWithRide.ride ?: return@mapNotNull null
            MyRideEntry(
                key = "req-${requestWithRide.request.requestId}",
                roleLabel = "Passenger",
                departureTime = ride.departureTime,
                statusLabel = requestStatusLabel(requestWithRide.request.status),
                locationText = "${describeLocation(ride.originLat, ride.originLng)} -> ${describeLocation(ride.destinationLat, ride.destinationLng)}",
                seatsText = "${requestWithRide.request.seatsRequested} seat(s) requested",
                onClick = { onOpenRequest(requestWithRide.request) }
            )
        }
        val entries = (drivingEntries + requestedEntries).sortedByDescending { it.departureTime }

        if (entries.isEmpty()) {
            Text(
                "You're not driving or requesting any rides yet.",
                modifier = Modifier.padding(contentPadding).padding(16.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.padding(contentPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(entries, key = { it.key }) { entry ->
                    MyRideCard(entry)
                }
            }
        }
    }
}

@Composable
private fun MyRideCard(entry: MyRideEntry) {
    Card(onClick = entry.onClick, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(
                        entry.roleLabel,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
                Text(
                    SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(entry.departureTime),
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(entry.statusLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Text(entry.locationText, style = MaterialTheme.typography.bodySmall)
            Text(entry.seatsText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
