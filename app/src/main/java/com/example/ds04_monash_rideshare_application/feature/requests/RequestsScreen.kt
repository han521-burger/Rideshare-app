package com.example.ds04_monash_rideshare_application.feature.requests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideRequestSummary
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideSummary
import com.example.ds04_monash_rideshare_application.core.state.AppMode
import com.example.ds04_monash_rideshare_application.core.state.Resource
import com.example.ds04_monash_rideshare_application.ui.describeLocation
import com.example.ds04_monash_rideshare_application.ui.requestStatusLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAB_LEFT = 0
private const val TAB_RIGHT = 1

// Two sub-tabs, same shape in both modes: left is "mine" (a passenger's own asks / a driver's
// own still-empty offers), right is "others'" (rides to browse and join / requests to accept).
// The "+" only ever lives on the left tab, and only does the thing relevant to the active mode -
// posting a standalone request (passenger) or opening Offer-a-Ride (driver).
@Composable
fun RequestsScreen(
    activeMode: AppMode,
    myRequests: List<RideRequestSummary>,
    incomingRequests: List<RideRequestSummary>,
    openRides: Resource<List<RideSummary>>,
    myOffers: List<RideSummary>,
    onOpenRequest: (requestId: String) -> Unit,
    onOpenRide: (rideId: String) -> Unit,
    onFindRide: () -> Unit,
    onRequestRide: () -> Unit,
    onOfferRide: () -> Unit,
    modifier: Modifier = Modifier
) {
    var subTab by rememberSaveable { mutableStateOf(TAB_LEFT) }
    val isDriver = activeMode == AppMode.DRIVER
    val leftLabel = if (isDriver) "My Offers" else "Pending"
    val rightLabel = if (isDriver) "Requests" else "Rides I can join"

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = subTab) {
                Tab(selected = subTab == TAB_LEFT, onClick = { subTab = TAB_LEFT }, text = { Text(leftLabel) })
                Tab(selected = subTab == TAB_RIGHT, onClick = { subTab = TAB_RIGHT }, text = { Text(rightLabel) })
            }

            if (isDriver) {
                when (subTab) {
                    TAB_LEFT -> MyOffersList(myOffers, onOpenRide)
                    else -> PendingList(incomingRequests, "No one's waiting on you right now.", onOpenRequest)
                }
            } else {
                when (subTab) {
                    TAB_LEFT -> PendingList(myRequests, "No pending rides - tap + to request one!", onOpenRequest)
                    else -> BrowseRidesList(openRides, onOpenRide, onFindRide)
                }
            }
        }

        if (subTab == TAB_LEFT) {
            FloatingActionButton(
                onClick = { if (isDriver) onOfferRide() else onRequestRide() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
            ) { Text("+", style = MaterialTheme.typography.headlineSmall) }
        }
    }
}

@Composable
private fun PendingList(requests: List<RideRequestSummary>, emptyMessage: String, onOpenRequest: (requestId: String) -> Unit) {
    if (requests.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(emptyMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(requests, key = { it.requestId }) { request ->
            RequestRow(request, onClick = { onOpenRequest(request.requestId) })
        }
        item {} // trailing space so the FAB doesn't sit on top of the last card
    }
}

@Composable
private fun MyOffersList(offers: List<RideSummary>, onOpenRide: (rideId: String) -> Unit) {
    if (offers.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No open offers yet - tap + to post a ride.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(offers, key = { it.rideId }) { ride -> MyOfferRow(ride, onClick = { onOpenRide(ride.rideId) }) }
        item {} // trailing space so the FAB doesn't sit on top of the last card
    }
}

@Composable
private fun MyOfferRow(ride: RideSummary, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "${describeLocation(ride.originLat, ride.originLng)} → ${describeLocation(ride.destinationLat, ride.destinationLng)}",
                fontWeight = FontWeight.SemiBold
            )
            Text(SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(ride.departureTime)))
            Text("Waiting for passengers - ${ride.seatsTotal} seat(s) open", color = MaterialTheme.colorScheme.primary)
            LinearProgressIndicator(
                progress = { 0f },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun BrowseRidesList(
    openRides: Resource<List<RideSummary>>,
    onOpenRide: (rideId: String) -> Unit,
    onFindRide: () -> Unit
) {
    when {
        openRides.isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        openRides.errorMessage != null -> Text(openRides.errorMessage, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        else -> {
            val rides = openRides.data.orEmpty()
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Button(onClick = onFindRide, modifier = Modifier.fillMaxWidth()) {
                        Text("Search by route and time")
                    }
                }
                if (rides.isEmpty()) {
                    item { Text("No open rides posted right now - check back soon.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(rides, key = { it.rideId }) { ride -> OpenRideRow(ride, onClick = { onOpenRide(ride.rideId) }) }
                }
                item {} // trailing space so the FAB doesn't sit on top of the last card
            }
        }
    }
}

@Composable
private fun OpenRideRow(ride: RideSummary, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "${describeLocation(ride.originLat, ride.originLng)} → ${describeLocation(ride.destinationLat, ride.destinationLng)}",
                fontWeight = FontWeight.SemiBold
            )
            Text(SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(ride.departureTime)))
            Text("${ride.seatsAvailable} seat(s) free · %.1f km".format(ride.distanceMeters / 1000.0))
        }
    }
}

@Composable
private fun RequestRow(request: RideRequestSummary, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(modifier = Modifier.padding(16.dp)) {
            val ride = request.ride
            when {
                ride != null -> {
                    Text(
                        "${describeLocation(ride.originLat, ride.originLng)} → ${describeLocation(ride.destinationLat, ride.destinationLng)}",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(ride.departureTime)))
                }
                request.originLat != null && request.originLng != null &&
                    request.destinationLat != null && request.destinationLng != null -> {
                    Text(
                        "${describeLocation(request.originLat, request.originLng)} → ${describeLocation(request.destinationLat, request.destinationLng)}",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (request.isAsap) "ASAP" else request.desiredDepartureTime?.let {
                            SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(it))
                        } ?: "No time set"
                    )
                }
            }
            request.passenger?.let { passenger -> Text("From ${passenger.displayName}") }
            Text("${request.seatsRequested} seat(s) · ${requestStatusLabel(request.status)}", color = MaterialTheme.colorScheme.primary)
            request.matchScoreTotal?.let { score ->
                if (score >= 50) {
                    Text(
                        "Roughly matches one of your posted routes",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
            request.note?.takeIf { it.isNotBlank() }?.let { Text("\"$it\"") }
        }
    }
}
