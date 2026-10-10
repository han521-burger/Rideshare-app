package com.example.ds04_monash_rideshare_application.feature.requests

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.Repository.Remote.RequestStatus
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideRequestSummary
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideSummary
import com.example.ds04_monash_rideshare_application.core.state.SCHEDULE_WARNING_TITLE
import com.example.ds04_monash_rideshare_application.core.ui.CountStepper
import com.example.ds04_monash_rideshare_application.core.ui.FieldMessage
import com.example.ds04_monash_rideshare_application.core.ui.FormBanner
import com.example.ds04_monash_rideshare_application.core.ui.FormBannerKind
import com.example.ds04_monash_rideshare_application.core.ui.FormInfoRow
import com.example.ds04_monash_rideshare_application.core.ui.FormSectionCard
import com.example.ds04_monash_rideshare_application.core.ui.FormStatusPill
import com.example.ds04_monash_rideshare_application.core.ui.ReviewDivider
import com.example.ds04_monash_rideshare_application.core.ui.ReviewSection
import com.example.ds04_monash_rideshare_application.core.ui.RideshareScaffold
import com.example.ds04_monash_rideshare_application.core.ui.RouteTimeline
import com.example.ds04_monash_rideshare_application.ui.describeLocation
import com.example.ds04_monash_rideshare_application.ui.requestStatusLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatTime(millis: Long) = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(millis))

// A pending request's own screen, separate from Ride Detail - a standalone request (rideId ==
// null) has no ride yet to show a map/driver for, so it needs somewhere else to live. Also used
// for an already-attached request when there's nothing ride-specific worth a full Ride Detail
// trip yet (still PENDING/REJECTED).
@OptIn(ExperimentalLayoutApi::class)
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
    modifier: Modifier = Modifier,
    // Passenger seats the driver's own car can take (capacity minus the driver); null = no vehicle.
    maxPassengerSeats: Int? = null,
    // The signed-in user's own schedule warning for this request (an earlier ride finished late), or null. Passenger only.
    scheduleWarning: String? = null
) {
    var choosingHowToAccept by remember { mutableStateOf(false) }

    RideshareScaffold(title = "Request detail", canNavigateBack = true, onNavigateBack = onNavigateBack, modifier = modifier) { contentPadding ->
        if (request == null) {
            Column(Modifier.padding(contentPadding).padding(16.dp)) {
                FormInfoRow("This request couldn't be found - it may have been cancelled or expired.")
            }
            return@RideshareScaffold
        }

        val isPassenger = request.passengerId == currentUserId
        val isStandalone = request.rideId == null
        val colors = MaterialTheme.colorScheme

        Column(
            modifier = Modifier.padding(contentPadding).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FormSectionCard(
                if (isStandalone) "Ride request" else "Seat request",
                trailing = { FormStatusPill(requestStatusLabel(request.status), success = request.status == RequestStatus.ACCEPTED) }
            ) {
                val ride = request.ride
                val origin = if (isStandalone) request.originLat?.let { lat -> request.originLng?.let { describeLocation(lat, it) } }
                    else ride?.let { describeLocation(it.originLat, it.originLng) }
                val destination = if (isStandalone) request.destinationLat?.let { lat -> request.destinationLng?.let { describeLocation(lat, it) } }
                    else ride?.let { describeLocation(it.destinationLat, it.destinationLng) }
                if (origin != null && destination != null) RouteTimeline(origin, destination)

                ReviewDivider()
                ReviewSection("WHEN") {
                    Text(
                        when {
                            !isStandalone && ride != null -> formatTime(ride.departureTime)
                            request.isAsap -> "Leaving as soon as possible"
                            else -> request.desiredDepartureTime?.let(::formatTime) ?: "No time set"
                        },
                        fontWeight = FontWeight.Bold
                    )
                    if (isStandalone && request.repeatDaysOfWeek.isNotEmpty()) {
                        Text("Also wants this repeated (${request.repeatDaysOfWeek.size} day(s)/week)")
                    }
                }
                ReviewDivider()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FormStatusPill("${request.seatsRequested} ${if (request.seatsRequested == 1) "seat" else "seats"}")
                    request.passenger?.let { FormStatusPill("From ${it.displayName}") }
                    request.matchScoreTotal?.takeIf { isStandalone }?.let { score ->
                        FormStatusPill(if (score >= 50) "Matches one of your routes" else "Weak match with your routes")
                    }
                }
                request.note?.takeIf { it.isNotBlank() }?.let { note ->
                    ReviewSection("NOTE") { Text(note) }
                }
            }

            actionErrorMessage?.let { FormBanner(it, FormBannerKind.Error) }
            // A request the system ended because it no longer fits: say why, so it doesn't just look like it vanished.
            conflictSupport(request)?.let { FormBanner(it, FormBannerKind.Error, title = "Schedule conflict") }
            // An ACCEPTED request stays accepted when the ride before it finished late; the passenger is told, not cancelled.
            // The warning is only ever the viewer's own, and only a passenger looking at their own accepted request sees it.
            if (isPassenger && request.status == RequestStatus.ACCEPTED) {
                scheduleWarning?.let { FormBanner(it, FormBannerKind.Warning, title = SCHEDULE_WARNING_TITLE) }
            }

            if (request.status == RequestStatus.PENDING) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!isPassenger) {
                        // On an open ask (no ride yet) Decline only hides it from this driver; it stays
                        // open for others (backend PUT /requests/{id} REJECTED).
                        OutlinedButton(
                            onClick = onDecline, enabled = !isActionInFlight,
                            modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface),
                            border = BorderStroke(1.dp, colors.outline)
                        ) { Text("Decline") }
                        Button(
                            onClick = { if (isStandalone) choosingHowToAccept = true else onAcceptAttached() },
                            enabled = !isActionInFlight,
                            modifier = Modifier.weight(1.4f).heightIn(min = 52.dp)
                        ) { Text("Accept") }
                    } else {
                        OutlinedButton(
                            onClick = onCancel, enabled = !isActionInFlight,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.error),
                            border = BorderStroke(1.dp, colors.error)
                        ) { Text("Cancel my request") }
                    }
                }
            }

            if (choosingHowToAccept) {
                AcceptChoiceDialog(
                    request = request,
                    myOpenRides = myOpenRidesForAccept,
                    maxPassengerSeats = maxPassengerSeats,
                    isActionInFlight = isActionInFlight,
                    onAttach = { rideId -> choosingHowToAccept = false; onAcceptStandalone(rideId) },
                    onCreate = { seats -> choosingHowToAccept = false; onCreateRideAndAccept(seats) },
                    onDismiss = { choosingHowToAccept = false }
                )
            }
        }
    }
}

private const val NEW_RIDE = "new-ride"

// The one place an open ask gets accepted: pick one of your posted rides, or a new ride using the
// ask's route and time - its seats capped by your own car (the backend enforces the same limit).
@Composable
private fun AcceptChoiceDialog(
    request: RideRequestSummary,
    myOpenRides: List<RideSummary>,
    maxPassengerSeats: Int?,
    isActionInFlight: Boolean,
    onAttach: (rideId: String) -> Unit,
    onCreate: (seats: Int) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val needed = request.seatsRequested
    var choice by remember { mutableStateOf(myOpenRides.firstOrNull()?.rideId ?: NEW_RIDE) }
    var seats by remember { mutableStateOf(needed) }
    val carFits = maxPassengerSeats != null && needed <= maxPassengerSeats
    val canConfirm = !isActionInFlight && (choice != NEW_RIDE || carFits)
    val passengerName = request.passenger?.displayName ?: "this passenger"

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = { Text("Accept $passengerName") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (myOpenRides.isNotEmpty()) {
                    Text("Add them to one of your rides", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    myOpenRides.forEach { ride ->
                        ChoiceRow(
                            selected = choice == ride.rideId,
                            title = "${describeLocation(ride.originLat, ride.originLng)} → ${describeLocation(ride.destinationLat, ride.destinationLng)}",
                            subtitle = "${formatTime(ride.departureTime)} · ${ride.seatsAvailable} seat(s) free",
                            onSelect = { choice = ride.rideId }
                        )
                    }
                    Text("Or", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                ChoiceRow(
                    selected = choice == NEW_RIDE,
                    title = "New ride for this request",
                    subtitle = "Uses their route and time",
                    onSelect = { choice = NEW_RIDE }
                )
                if (choice == NEW_RIDE) {
                    when {
                        maxPassengerSeats == null -> FormInfoRow("Register your vehicle in Profile first - a new ride uses your car.")
                        !carFits -> FieldMessage(
                            "They need $needed seats but your car takes up to $maxPassengerSeats passengers.", isError = true
                        )
                        else -> {
                            Text("Seats to offer on the new ride", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            CountStepper(seats.coerceIn(needed, maxPassengerSeats), needed, maxPassengerSeats, { seats = it },
                                unit = if (seats == 1) "seat" else "seats", subject = "seats on the new ride")
                            FieldMessage("At least $needed for this request · your car takes up to $maxPassengerSeats.", isError = false)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = canConfirm, onClick = {
                if (choice == NEW_RIDE) onCreate(seats.coerceIn(needed, maxPassengerSeats ?: needed)) else onAttach(choice)
            }) { Text("Accept") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ChoiceRow(selected: Boolean, title: String, subtitle: String, onSelect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape)
            .background(if (selected) colors.primaryContainer else colors.surface)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = if (selected) colors.onPrimaryContainer else colors.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant)
        }
    }
}
