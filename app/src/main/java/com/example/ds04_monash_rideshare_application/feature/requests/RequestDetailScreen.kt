package com.example.ds04_monash_rideshare_application.feature.requests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.example.ds04_monash_rideshare_application.Repository.Remote.RequestStatus
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideRequestSummary
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideSummary
import com.example.ds04_monash_rideshare_application.core.ui.RideshareScaffold
import com.example.ds04_monash_rideshare_application.ui.describeLocation
import com.example.ds04_monash_rideshare_application.ui.requestStatusLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// A pending request's own screen, separate from Ride Detail - a standalone request (rideId ==
// null) has no ride yet to show a map/driver for, so it needs somewhere else to live. Also used
// for an already-attached request when there's nothing ride-specific worth a full Ride Detail
// trip yet (still PENDING/REJECTED).
@Composable
fun RequestDetailScreen(
    request: RideRequestSummary?,
    currentUserId: String?,
    myOpenRidesForAccept: List<RideSummary>,
    isActionInFlight: Boolean,
    actionErrorMessage: String?,
    onAcceptAttached: () -> Unit,
    onAcceptStandalone: (targetRideId: String) -> Unit,
    onCreateRideAndAccept: (seatsTotal: Int) -> Unit,
    onDecline: () -> Unit,
    onCancel: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pickingRide by remember { mutableStateOf(false) }
    var newRideSeats by remember(request?.seatsRequested) { mutableStateOf(request?.seatsRequested ?: 1) }

    RideshareScaffold(title = "Request detail", canNavigateBack = true, onNavigateBack = onNavigateBack, modifier = modifier) { contentPadding ->
        if (request == null) {
            Text("Request not found.", modifier = Modifier.padding(contentPadding).padding(16.dp))
            return@RideshareScaffold
        }

        val isPassenger = request.passengerId == currentUserId
        val isStandalone = request.rideId == null

        Column(modifier = Modifier.padding(contentPadding).padding(16.dp).fillMaxSize()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(requestStatusLabel(request.status), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)

                    if (isStandalone) {
                        val originLat = request.originLat
                        val originLng = request.originLng
                        val destLat = request.destinationLat
                        val destLng = request.destinationLng
                        if (originLat != null && originLng != null && destLat != null && destLng != null) {
                            Text(
                                "${describeLocation(originLat, originLng)} → ${describeLocation(destLat, destLng)}",
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            if (request.isAsap) "Leaving ASAP" else request.desiredDepartureTime?.let {
                                SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(it))
                            } ?: "No time set"
                        )
                        if (request.repeatDaysOfWeek.isNotEmpty()) {
                            Text("Also wants this repeated (${request.repeatDaysOfWeek.size} day(s)/week)")
                        }
                        request.matchScoreTotal?.let { score ->
                            Text(
                                if (score >= 50) "Roughly matches one of your posted routes" else "Weak match with your posted routes",
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else {
                        val ride = request.ride
                        if (ride != null) {
                            Text(
                                "${describeLocation(ride.originLat, ride.originLng)} → ${describeLocation(ride.destinationLat, ride.destinationLng)}",
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(ride.departureTime)))
                        }
                    }

                    Text("${request.seatsRequested} seat(s)")
                    request.passenger?.let { passenger -> Text("From ${passenger.displayName}") }
                    request.note?.takeIf { it.isNotBlank() }?.let { Text("Note: $it") }
                }
            }

            actionErrorMessage?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            if (request.status == RequestStatus.PENDING) {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!isPassenger) {
                        Button(
                            onClick = {
                                if (isStandalone) pickingRide = true else onAcceptAttached()
                            },
                            enabled = !isActionInFlight,
                            modifier = Modifier.weight(1f)
                        ) { Text("Accept") }
                    }
                    // On an open ask (no ride yet) Decline only hides it from this driver; it stays
                    // open for others (backend PUT /requests/{id} REJECTED).
                    if (!isPassenger) {
                        OutlinedButton(onClick = onDecline, enabled = !isActionInFlight, modifier = Modifier.weight(1f)) { Text("Decline") }
                    }
                    if (isPassenger) {
                        OutlinedButton(onClick = onCancel, enabled = !isActionInFlight, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    }
                }
            }

            if (pickingRide) {
                if (myOpenRidesForAccept.isNotEmpty()) {
                    Text("Attach to one of your posted rides", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 20.dp))
                    myOpenRidesForAccept.forEach { ride ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            onClick = { onAcceptStandalone(ride.rideId) }
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "${describeLocation(ride.originLat, ride.originLng)} → ${describeLocation(ride.destinationLat, ride.destinationLng)}",
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(ride.departureTime)))
                                Text("${ride.seatsAvailable} seat(s) free")
                            }
                        }
                    }
                }

                // Always available, not just as a fallback - accepting doesn't require having
                // already posted a matching ride first.
                Text(
                    if (myOpenRidesForAccept.isEmpty()) "Create a ride for this" else "Or create a new ride",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 20.dp)
                )
                Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Total seats on the new ride")
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            OutlinedButton(onClick = { if (newRideSeats > request.seatsRequested) newRideSeats-- }) { Text("-") }
                            Text(newRideSeats.toString(), modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
                            OutlinedButton(onClick = { if (newRideSeats < 8) newRideSeats++ }) { Text("+") }
                        }
                        Text(
                            "At least ${request.seatsRequested} to cover this request.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = { onCreateRideAndAccept(newRideSeats) },
                            enabled = !isActionInFlight,
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                        ) { Text("Create ride & accept") }
                    }
                }
            }
        }
    }
}
