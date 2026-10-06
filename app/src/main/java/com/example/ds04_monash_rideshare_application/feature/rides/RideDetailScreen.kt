package com.example.ds04_monash_rideshare_application.feature.rides

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.Repository.Remote.RequestStatus
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideRequestSummary
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideStatus
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideSummary
import com.example.ds04_monash_rideshare_application.core.state.Resource
import com.example.ds04_monash_rideshare_application.core.ui.DetailCard
import com.example.ds04_monash_rideshare_application.core.ui.FormBanner
import com.example.ds04_monash_rideshare_application.core.ui.FormBannerKind
import com.example.ds04_monash_rideshare_application.core.ui.FormInfoRow
import com.example.ds04_monash_rideshare_application.core.ui.FormSectionCard
import com.example.ds04_monash_rideshare_application.core.ui.FormStatusPill
import com.example.ds04_monash_rideshare_application.core.ui.ReviewDivider
import com.example.ds04_monash_rideshare_application.core.ui.ReviewSection
import com.example.ds04_monash_rideshare_application.core.ui.RideshareScaffold
import com.example.ds04_monash_rideshare_application.core.ui.RouteMiniMap
import com.example.ds04_monash_rideshare_application.core.ui.RouteTimeline
import com.example.ds04_monash_rideshare_application.ui.describeLocation
import com.example.ds04_monash_rideshare_application.ui.requestStatusLabel
import com.example.ds04_monash_rideshare_application.ui.rideStatusLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatDeparture(millis: Long) = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(millis))

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

            rideDetails.errorMessage != null -> Column(Modifier.padding(contentPadding).padding(16.dp)) {
                FormBanner(rideDetails.errorMessage, FormBannerKind.Error, title = "Couldn’t load this ride")
            }

            else -> {
                val ride = rideDetails.data ?: return@RideshareScaffold
                val isDriver = ride.driverId == currentUserId

                LazyColumn(
                    modifier = Modifier.padding(contentPadding).fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
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
                        item { FormBanner(error, FormBannerKind.Error) }
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
                        item {
                            Text("Passengers", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold)
                        }
                        if (incomingRequestsForRide.isEmpty()) {
                            item { FormInfoRow("No requests yet for this ride.") }
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
                        if (ride.status in setOf(RideStatus.OPEN, RideStatus.CONFIRMED)) {
                            item {
                                DangerButton("Cancel this ride", enabled = !isActionInFlight, onClick = onCancelRide)
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
                                Button(onClick = onRequestSeat, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
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
                                    TripActionButton(Icons.AutoMirrored.Rounded.Chat, "Chat", Modifier.weight(1f), onClick = onOpenChat)
                                    TripActionButton(Icons.Rounded.Share, "Share", Modifier.weight(1f)) { shareRide(context, ride) }
                                    TripActionButton(Icons.Rounded.Warning, "SOS", Modifier.weight(1f), danger = true) { showSosConfirm = true }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RideSummaryCard(ride: RideSummary) {
    FormSectionCard("Trip", trailing = { FormStatusPill(rideStatusLabel(ride.status), success = ride.status == RideStatus.CONFIRMED) }) {
        RouteTimeline(
            describeLocation(ride.originLat, ride.originLng),
            describeLocation(ride.destinationLat, ride.destinationLng),
            via = ride.stops.sortedBy { it.sequence }.map { it.label ?: describeLocation(it.lat, it.lng) }
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FormStatusPill(formatDeparture(ride.departureTime))
            FormStatusPill("%.1f km · about %d min".format(ride.distanceMeters / 1000.0, ride.estimatedDurationSeconds / 60))
            FormStatusPill("${ride.seatsAvailable} of ${ride.seatsTotal} seats free")
            if (ride.recurringGroupId != null) FormStatusPill("Repeats weekly")
        }
        ride.notes?.takeIf { it.isNotBlank() }?.let {
            ReviewDivider()
            ReviewSection("DRIVER'S NOTE") { Text(it) }
        }
    }
}

@Composable
private fun DriverInfoCard(ride: RideSummary) {
    val colors = MaterialTheme.colorScheme
    val name = ride.driver?.displayName ?: ride.driverId
    FormSectionCard("Your driver") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(colors.primaryContainer, CircleShape).clearAndSetSemantics {},
                contentAlignment = Alignment.Center) {
                Text(name.trim().take(1).uppercase(), color = colors.onPrimaryContainer, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.Bold)
                val driver = ride.driver
                val averageRating = driver?.averageRating
                if (driver != null && averageRating != null) {
                    Text("★ %.1f · ${driver.ratingCount} rating(s)".format(averageRating),
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
        }
        ride.vehicle?.let { vehicle ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.DirectionsCar, null, Modifier.size(20.dp), tint = colors.primary)
                Text(com.example.ds04_monash_rideshare_application.feature.profile.vehicleSummary(vehicle),
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (ride.driverEnRoute) FormStatusPill("Driver is on the way", success = true)
    }
}

// Same look as the ride flows' toggle rows: one switch for the whole row.
@Composable
private fun DriverEnRouteCard(enRoute: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(shape)
            .background(if (enRoute) colors.primaryContainer else colors.surface)
            .border(if (enRoute) 1.5.dp else 1.dp, if (enRoute) colors.primary else colors.outlineVariant, shape)
            .toggleable(value = enRoute, enabled = enabled, role = Role.Switch, onValueChange = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(Icons.Rounded.DirectionsCar, null, Modifier.size(24.dp), tint = colors.primary)
        Column(Modifier.weight(1f)) {
            Text("I'm coming", fontWeight = FontWeight.Bold, color = if (enRoute) colors.onPrimaryContainer else colors.onSurface)
            Text(
                if (enabled || enRoute) "Lets your passengers know you're on the way (not live GPS)."
                else "Available once a passenger is confirmed.",
                style = MaterialTheme.typography.bodySmall,
                color = if (enRoute) colors.onPrimaryContainer else colors.onSurfaceVariant
            )
        }
        Switch(checked = enRoute, onCheckedChange = null, enabled = enabled)
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
    val colors = MaterialTheme.colorScheme
    DetailCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(request.passenger?.displayName ?: request.passengerId, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                FormStatusPill(requestStatusLabel(request.status), success = request.status == RequestStatus.ACCEPTED)
            }
            Text("${request.seatsRequested} seat(s)", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            request.note?.takeIf { it.isNotBlank() }?.let { Text("“$it”", style = MaterialTheme.typography.bodyMedium) }

            when {
                request.status == RequestStatus.PENDING -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = onReject, enabled = !isActionInFlight,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface),
                            border = BorderStroke(1.dp, colors.outline)
                        ) { Text("Decline") }
                        Button(
                            onClick = onAccept, enabled = !isActionInFlight,
                            modifier = Modifier.weight(1.4f).heightIn(min = 48.dp)
                        ) { Text("Accept") }
                    }
                }
                request.status == RequestStatus.ACCEPTED && rideCompleted -> {
                    RateButton("Rate this passenger", onRate)
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
    FormSectionCard("Your request", trailing = {
        FormStatusPill(requestStatusLabel(request.status), success = request.status == RequestStatus.ACCEPTED)
    }) {
        Text("${request.seatsRequested} seat(s)", fontWeight = FontWeight.Bold)
        request.note?.takeIf { it.isNotBlank() }?.let { ReviewSection("YOUR NOTE") { Text(it) } }

        when (request.status) {
            RequestStatus.PENDING -> {
                FormInfoRow("Waiting for the driver to review your request.")
                DangerButton("Cancel request", enabled = !isActionInFlight, onClick = onCancel)
            }

            RequestStatus.ACCEPTED -> {
                FormBanner("You're in! Message your driver or share your trip below.", FormBannerKind.Success)
                DangerButton("Cancel my seat", enabled = !isActionInFlight, onClick = onCancel)
            }

            RequestStatus.COMPLETED -> RateButton("Rate your driver", onRate)

            else -> Unit
        }
    }
}

@Composable
private fun TripActionButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, danger: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tint = if (danger) colors.error else colors.primary
    OutlinedButton(
        onClick = onClick, modifier = modifier.heightIn(min = 52.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (danger) colors.error else colors.onSurface),
        border = BorderStroke(1.dp, if (danger) colors.error else colors.outline),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = tint)
        Text(label, Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun DangerButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.error),
        border = BorderStroke(1.dp, colors.error)
    ) { Text(label) }
}

@Composable
private fun RateButton(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Icon(Icons.Rounded.Star, null, Modifier.size(18.dp))
        Text(label, Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun SosDialog(emergencyContactPhone: String?, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        icon = { Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error) },
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
            TextButton(
                onClick = { onConfirm(emergencyContactPhone ?: "000") },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text("Open dialer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun RatingDialog(displayName: String, onDismiss: () -> Unit, onSubmit: (Int, String?) -> Unit) {
    var stars by remember { mutableStateOf(5) }
    var comment by remember { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = { Text("Rate $displayName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    (1..5).forEach { value ->
                        IconButton(onClick = { stars = value }, modifier = Modifier.size(48.dp)) {
                            Icon(
                                if (value <= stars) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                contentDescription = "$value star${if (value == 1) "" else "s"}",
                                modifier = Modifier.size(32.dp),
                                tint = if (value <= stars) colors.primary else colors.onSurfaceVariant
                            )
                        }
                    }
                }
                Text("$stars out of 5", Modifier.semantics {}, style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant)
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text("Comment (optional)") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
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
        "departing ${formatDeparture(ride.departureTime)}."
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Share trip"))
}
