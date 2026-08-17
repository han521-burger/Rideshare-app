package com.example.ds04_monash_rideshare_application.feature.rides

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import com.example.ds04_monash_rideshare_application.Database.Passenger.SeatRequest
import com.example.ds04_monash_rideshare_application.Database.RideShare.RideOffer
import com.example.ds04_monash_rideshare_application.core.state.Resource
import com.example.ds04_monash_rideshare_application.core.ui.RideshareScaffold
import java.text.SimpleDateFormat
import java.util.Locale

// The one screen that makes every other ride you're involved in reachable - rides you're
// driving (any status) and seats you've requested as a passenger (any status), each tappable
// through to Ride Details / Confirmed Ride depending on where it's at. Without this, a ride that
// was already CONFIRMED before you opened the app (e.g. seed data, or a request accepted while
// you were elsewhere) had no menu path to reach it at all.
@Composable
fun MyRidesScreen(
    driverRides: Resource<List<RideOffer>>,
    myRequests: Resource<List<SeatRequest>>,
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

        val driving = driverRides.data.orEmpty()
        val requested = myRequests.data.orEmpty()

        if (driving.isEmpty() && requested.isEmpty()) {
            Text(
                "You're not driving or requesting any rides yet.",
                modifier = Modifier.padding(contentPadding).padding(16.dp)
            )
            return@RideshareScaffold
        }

        LazyColumn(
            modifier = Modifier.padding(contentPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (driving.isNotEmpty()) {
                item {
                    Text(
                        "Rides you're driving",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                items(driving, key = { "drive-${it.rideId}" }) { ride ->
                    Card(onClick = { onOpenDriverRide(ride) }, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(ride.departureTime),
                                fontWeight = FontWeight.SemiBold
                            )
                            Text("${ride.status} - ${ride.seatsAvailable} seat(s) available", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            if (requested.isNotEmpty()) {
                item {
                    Text(
                        "Your seat requests",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                items(requested, key = { "req-${it.requestId}" }) { request ->
                    Card(onClick = { onOpenRequest(request) }, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Status: ${request.status}", fontWeight = FontWeight.SemiBold)
                            Text("${request.seatsRequested} seat(s) requested", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
