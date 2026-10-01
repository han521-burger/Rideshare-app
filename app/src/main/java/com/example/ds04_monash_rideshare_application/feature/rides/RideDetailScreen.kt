package com.example.ds04_monash_rideshare_application.feature.rides

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.Repository.Remote.RequestStatus
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideRequestSummary
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideStatus
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideSummary
import com.example.ds04_monash_rideshare_application.core.state.Resource
import com.example.ds04_monash_rideshare_application.core.ui.RideshareScaffold
import com.example.ds04_monash_rideshare_application.core.ui.RouteMiniMap
import com.example.ds04_monash_rideshare_application.ui.describeLocation
import com.example.ds04_monash_rideshare_application.ui.requestStatusLabel
import com.example.ds04_monash_rideshare_application.ui.rideStatusLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// One screen for both sides of a ride: passengers see driver/vehicle info, their own request
// status and a way to request/cancel/rate; drivers see the passenger list, accept/reject,
// "I'm coming" and per-passenger rating. Matches the spec's Ride Detail (passenger) and Route
// Detail (driver) as the same underlying data with different action sets, not two screens.
@Composable
fun RideDetailScreen(
    rideDetails: Resource<RideSummary>,
    currentUserId: String?,
    myRequestForRide: RideRequestSummary?,
    incomingRequestsForRide: List<RideRequestSummary>,
    isActionInFlight: Boolean,
    requestActionError: String?,
    emergencyContactPhone: String?,
    onRequestSeat: () -> Unit,
    onCancelMyRequest: (requestId: String) -> Unit,
    onAcceptRequest: (requestId: String) -> Unit,
    onRejectRequest: (requestId: String) -> Unit,
    onSetEnRoute: (Boolean) -> Unit,
    onCancelRide: () -> Unit,
    onCompleteRide: () -> Unit,
    onOpenChat: () -> Unit,
    onSubmitRating: (ratedUserId: String, stars: Int, comment: String?) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showSosConfirm by remember { mutableStateOf(false) }
    var ratingTarget by remember { mutableStateOf<Pair<String, String>?>(null) } // userId to displayName

    RideshareScaffold(
        title = "Ride details",
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

            else -> {
                val ride = rideDetails.data ?: return@RideshareScaffold
                val isDriver = ride.driverId == currentUserId

                LazyColumn(
                    modifier = Modifier.padding(contentPadding).fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        RouteMiniMap(
                            originLat = ride.originLat, originLng = ride.originLng,
                            destinationLat = ride.destinationLat, destinationLng = ride.destinationLng
                        )
                    }

                    item { RideSummaryCard(ride) }

                    requestActionError?.let { error ->
                        item { Text(error, color = MaterialTheme.colorScheme.error) }
                    }

                    if (ride.stops.isNotEmpty()) {
                        item {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("Stops along the way", fontWeight = FontWeight.SemiBold)
                                    ride.stops.sortedBy { it.sequence }.forEach { stop ->
                                        Text("• ${stop.label ?: describeLocation(stop.lat, stop.lng)}")
                                    }
                                }
                            }
                        }
                    }

                    if (isDriver) {
                        item {
                            CompleteRideAction(ride.rideId, ride.driverId, currentUserId, ride.status,
                                isActionInFlight, onCompleteRide)
                        }
                        item {
                            DriverEnRouteCard(
                                enRoute = ride.driverEnRoute,
                                enabled = ride.status == RideStatus.CONFIRMED && !isActionInFlight,
                                onToggle = onSetEnRoute
                            )
                        }
                        item { Text("Passengers", style = MaterialTheme.typography.titleMedium) }
                        if (incomingRequestsForRide.isEmpty()) {
                            item { Text("No requests yet for this ride.") }
                        }
                        items(incomingRequestsForRide, key = { it.requestId }) { request ->
                            PassengerRequestCard(
                                request = request,
                                rideCompleted = ride.status == RideStatus.COMPLETED,
                                isActionInFlight = isActionInFlight,
                                onAccept = { onAcceptRequest(request.requestId) },
                                onReject = { onRejectRequest(request.requestId) },
                                onRate = {
                                    val name = request.passenger?.displayName ?: "this passenger"
                                    ratingTarget = request.passengerId to name
                                }
                            )
                        }
                        if (ride.status != RideStatus.CANCELLED && ride.status != RideStatus.COMPLETED) {
                            item {
                                OutlinedButton(onClick = onCancelRide, enabled = !isActionInFlight, modifier = Modifier.fillMaxWidth()) {
                                    Text("Cancel this ride")
                                }
                            }
                        }
                    } else {
                        item { DriverInfoCard(ride) }

                        when {
                            // OPEN and partially-filled CONFIRMED rides can both receive a pending
                            // request. The driver, not this screen, makes the booking decision.
                            myRequestForRide == null &&
                                ride.seatsAvailable > 0 &&
                                (ride.status == RideStatus.OPEN || ride.status == RideStatus.CONFIRMED) -> item {
                                Button(onClick = onRequestSeat, modifier = Modifier.fillMaxWidth()) {
                                    Text("Request a seat")
                                }
                            }
                            myRequestForRide != null -> item {
                                MyRequestCard(
                                    request = myRequestForRide,
                                    isActionInFlight = isActionInFlight,
                                    onCancel = { onCancelMyRequest(myRequestForRide.requestId) },
                                    onRate = { ratingTarget = ride.driverId to (ride.driver?.displayName ?: "your driver") }
                                )
                            }
                        }

                        if (myRequestForRide?.status == RequestStatus.ACCEPTED) {
                            item {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    OutlinedButton(onClick = onOpenChat, modifier = Modifier.weight(1f)) { Text("Chat") }
                                    OutlinedButton(
                                        onClick = { shareRide(context, ride) },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("Share trip") }
                                    OutlinedButton(
                                        onClick = { showSosConfirm = true },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("SOS") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSosConfirm) {
        SosDialog(
            emergencyContactPhone = emergencyContactPhone,
            onDismiss = { showSosConfirm = false },
            onConfirm = { phone ->
                showSosConfirm = false
                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
            }
        )
    }

    ratingTarget?.let { (userId, displayName) ->
        RatingDialog(
            displayName = displayName,
            onDismiss = { ratingTarget = null },
            onSubmit = { stars, comment ->
                onSubmitRating(userId, stars, comment)
                ratingTarget = null
            }
        )
    }
}

@Composable
private fun RideSummaryCard(ride: RideSummary) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(rideStatusLabel(ride.status), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(
                "${describeLocation(ride.originLat, ride.originLng)} → ${describeLocation(ride.destinationLat, ride.destinationLng)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(ride.departureTime)))
            Text("%.1f km · about %d min".format(ride.distanceMeters / 1000.0, ride.estimatedDurationSeconds / 60))
            Text("${ride.seatsAvailable} of ${ride.seatsTotal} seats free")
            if (ride.recurringGroupId != null) {
                Text("Part of a repeating series", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ride.notes?.takeIf { it.isNotBlank() }?.let {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("Driver's note: $it")
            }
        }
    }
}

@Composable
private fun DriverInfoCard(ride: RideSummary) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Driver", style = MaterialTheme.typography.labelLarge)
            Text(ride.driver?.displayName ?: ride.driverId, fontWeight = FontWeight.SemiBold)
            val driver = ride.driver
            val averageRating = driver?.averageRating
            if (driver != null && averageRating != null) {
                Text("%.1f / 5 (${driver.ratingCount} rating(s))".format(averageRating), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ride.vehicle?.let { vehicle ->
                Text(com.example.ds04_monash_rideshare_application.feature.profile.vehicleSummary(vehicle))
            }
            if (ride.driverEnRoute) {
                Text("Driver is on the way", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun DriverEnRouteCard(enRoute: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("I'm coming", fontWeight = FontWeight.SemiBold)
                Text(
                    "Lets your passengers know you're on the way (not live GPS tracking).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = enRoute, onCheckedChange = onToggle, enabled = enabled)
        }
    }
}

// Requests on posted rides remain pending until the owning driver accepts or rejects them. The
// capacity check is performed by the backend when Accept is tapped, not when the passenger asks.
@Composable
private fun PassengerRequestCard(
    request: RideRequestSummary,
    rideCompleted: Boolean,
    isActionInFlight: Boolean,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onRate: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(request.passenger?.displayName ?: request.passengerId, fontWeight = FontWeight.SemiBold)
            Text("${request.seatsRequested} seat(s) · ${requestStatusLabel(request.status)}")
            request.note?.takeIf { it.isNotBlank() }?.let { Text("Note: $it") }

            when {
                request.status == RequestStatus.PENDING -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onReject,
                            enabled = !isActionInFlight,
                            modifier = Modifier.weight(1f)
                        ) { Text("Decline") }
                        Button(
                            onClick = onAccept,
                            enabled = !isActionInFlight,
                            modifier = Modifier.weight(1f)
                        ) { Text("Accept") }
                    }
                }
                request.status == RequestStatus.ACCEPTED && rideCompleted -> {
                    TextButton(onClick = onRate, modifier = Modifier.padding(top = 4.dp)) { Text("Rate this passenger") }
                }
            }
        }
    }
}

@Composable
private fun MyRequestCard(
    request: RideRequestSummary,
    isActionInFlight: Boolean,
    onCancel: () -> Unit,
    onRate: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Your request", fontWeight = FontWeight.SemiBold)
            Text("${request.seatsRequested} seat(s) · ${requestStatusLabel(request.status)}")
            request.note?.takeIf { it.isNotBlank() }?.let { Text("Your note: $it") }

            when (request.status) {
                RequestStatus.PENDING -> {
                    Text(
                        "Waiting for the driver to review your request.",
                        modifier = Modifier.padding(top = 4.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    OutlinedButton(
                        onClick = onCancel,
                        enabled = !isActionInFlight,
                        modifier = Modifier.padding(top = 8.dp)
                    ) { Text("Cancel request") }
                }

                RequestStatus.ACCEPTED -> {
                    Text(
                        "You're in! Message your driver or share your trip below.",
                        modifier = Modifier.padding(top = 4.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    OutlinedButton(
                        onClick = onCancel,
                        enabled = !isActionInFlight,
                        modifier = Modifier.padding(top = 8.dp)
                    ) { Text("Cancel my seat") }
                }

                RequestStatus.COMPLETED -> TextButton(onClick = onRate, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Rate your driver")
                }

                else -> Unit
            }
        }
    }
}

@Composable
private fun SosDialog(emergencyContactPhone: String?, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Call for help?") },
        text = {
            Text(
                if (emergencyContactPhone != null) {
                    "This opens your phone dialer with your emergency contact's number ($emergencyContactPhone) ready to call."
                } else {
                    "No emergency contact is saved yet (add one in Profile). This opens your phone dialer so you can call 000 or campus security directly."
                }
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(emergencyContactPhone ?: "000") }) { Text("Open dialer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun RatingDialog(displayName: String, onDismiss: () -> Unit, onSubmit: (Int, String?) -> Unit) {
    var stars by remember { mutableStateOf(5) }
    var comment by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rate $displayName") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..5).forEach { value ->
                        Text(
                            text = if (value <= stars) "*" else "-",
                            modifier = Modifier.padding(4.dp),
                            fontWeight = FontWeight.Bold,
                            color = if (value <= stars) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..5).forEach { value ->
                        TextButton(onClick = { stars = value }) { Text(value.toString()) }
                    }
                }
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text("Optional comment") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(stars, comment.trim().takeIf { it.isNotBlank() }) }) { Text("Submit") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun shareRide(context: android.content.Context, ride: RideSummary) {
    val text = "I'm sharing a Monash Rideshare trip: " +
        "${describeLocation(ride.originLat, ride.originLng)} to ${describeLocation(ride.destinationLat, ride.destinationLng)}, " +
        "departing ${SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(ride.departureTime))}."
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Share trip"))
}
