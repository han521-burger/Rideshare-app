package com.example.ds04_monash_rideshare_application.feature.requests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideRequestSummary
import com.example.ds04_monash_rideshare_application.core.state.AppMode
import com.example.ds04_monash_rideshare_application.ui.describeLocation
import com.example.ds04_monash_rideshare_application.ui.requestStatusLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Both directions live on one tab, since a single account can be riding one trip and driving
// another at once (the mode toggle changes emphasis/order, not what data exists). "My requests"
// is what I've asked to join; "Waiting on you" is what other passengers have asked of my rides.
@Composable
fun RequestsScreen(
    activeMode: AppMode,
    myRequests: List<RideRequestSummary>,
    incomingRequests: List<RideRequestSummary>,
    onOpenRide: (rideId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val sections = if (activeMode == AppMode.DRIVER) {
        listOf("Waiting on you" to incomingRequests, "My requests" to myRequests)
    } else {
        listOf("My requests" to myRequests, "Waiting on you" to incomingRequests)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        sections.forEach { (heading, requests) ->
            item { Text(heading, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            if (requests.isEmpty()) {
                item { Text("Nothing here right now.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(requests, key = { "$heading-${it.requestId}" }) { request ->
                    RequestRow(request, onClick = { onOpenRide(request.rideId) })
                }
            }
        }
    }
}

@Composable
private fun RequestRow(request: RideRequestSummary, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(modifier = Modifier.padding(16.dp)) {
            val ride = request.ride
            if (ride != null) {
                Text(
                    "${describeLocation(ride.originLat, ride.originLng)} → ${describeLocation(ride.destinationLat, ride.destinationLng)}",
                    fontWeight = FontWeight.SemiBold
                )
                Text(SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(ride.departureTime)))
            }
            request.passenger?.let { passenger -> Text("From ${passenger.displayName}") }
            Text("${request.seatsRequested} seat(s) · ${requestStatusLabel(request.status)}", color = MaterialTheme.colorScheme.primary)
            request.note?.takeIf { it.isNotBlank() }?.let { Text("\"$it\"") }
        }
    }
}
