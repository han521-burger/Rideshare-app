package com.example.ds04_monash_rideshare_application.feature.rides

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.Database.RideShare.RideOffer
import com.example.ds04_monash_rideshare_application.core.state.Resource
import com.example.ds04_monash_rideshare_application.core.ui.RideshareScaffold
import com.example.ds04_monash_rideshare_application.core.ui.RouteMiniMap
import com.example.ds04_monash_rideshare_application.ui.describeLocation
import com.example.ds04_monash_rideshare_application.ui.rideStatusLabel
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun RideDetailsScreen(
    rideDetails: Resource<RideOffer>,
    currentUserId: String?,
    canRequestSeat: Boolean,
    isRequesting: Boolean,
    onRequestSeat: (String, onDone: (Boolean, String?) -> Unit) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var feedback by remember { mutableStateOf<String?>(null) }

    RideshareScaffold(
        title = "Ride Details",
        canNavigateBack = true,
        onNavigateBack = onNavigateBack,
        modifier = modifier
    ) { contentPadding ->
        when {
            rideDetails.isLoading -> Column(
                modifier = Modifier.padding(contentPadding).fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) { CircularProgressIndicator() }

            rideDetails.errorMessage != null -> Text(
                rideDetails.errorMessage,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(contentPadding).padding(16.dp)
            )

            rideDetails.data != null -> {
                val ride = rideDetails.data
                Column(
                    modifier = Modifier
                        .padding(contentPadding)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        SimpleDateFormat("EEEE d MMMM, HH:mm", Locale.getDefault()).format(ride.departureTime),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text("Status: ${rideStatusLabel(ride.status)}", style = MaterialTheme.typography.bodyLarge)
                    Text("${ride.seatsAvailable} seat(s) available", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "From ${describeLocation(ride.originLat, ride.originLng)} to ${describeLocation(ride.destinationLat, ride.destinationLng)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    RouteMiniMap(
                        originLat = ride.originLat,
                        originLng = ride.originLng,
                        destinationLat = ride.destinationLat,
                        destinationLng = ride.destinationLng
                    )

                    if (ride.driverId == currentUserId) {
                        Text(
                            "This is your ride. Manage requests from Ride Requests.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (canRequestSeat && ride.status == RideOffer.STATUS_OPEN && ride.seatsAvailable > 0) {
                        Button(
                            enabled = !isRequesting,
                            onClick = {
                                onRequestSeat(ride.rideId) { success, message ->
                                    feedback = if (success) "Seat requested - waiting on the driver." else message
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (isRequesting) "Requesting..." else "Request a seat")
                        }
                    }

                    feedback?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
