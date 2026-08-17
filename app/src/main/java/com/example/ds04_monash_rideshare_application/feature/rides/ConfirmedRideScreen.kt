package com.example.ds04_monash_rideshare_application.feature.rides

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.Database.RideShare.RideOffer
import com.example.ds04_monash_rideshare_application.core.state.Resource
import com.example.ds04_monash_rideshare_application.core.ui.RideshareScaffold
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun ConfirmedRideScreen(
    rideDetails: Resource<RideOffer>,
    onOpenMessages: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    RideshareScaffold(
        title = "Confirmed Ride",
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
                    modifier = Modifier.padding(contentPadding).fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        SimpleDateFormat("EEEE d MMMM, HH:mm", Locale.getDefault()).format(ride.departureTime),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text("Status: ${ride.status}", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "From %.4f, %.4f to %.4f, %.4f".format(
                            ride.originLat, ride.originLng, ride.destinationLat, ride.destinationLng
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(onClick = onOpenMessages, modifier = Modifier.fillMaxWidth()) {
                        Text("Open Messages")
                    }
                }
            }

            else -> Text(
                "Select a confirmed ride from Ride Requests or Find Ride first.",
                modifier = Modifier.padding(contentPadding).padding(16.dp)
            )
        }
    }
}
