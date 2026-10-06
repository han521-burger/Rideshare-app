package com.example.ds04_monash_rideshare_application.feature.requests

import androidx.compose.foundation.BorderStroke
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
import com.example.ds04_monash_rideshare_application.core.ui.CountStepper
import com.example.ds04_monash_rideshare_application.core.ui.DetailCard
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
    modifier: Modifier = Modifier
) {
    var pickingRide by remember { mutableStateOf(false) }
    var newRideSeats by remember(request?.seatsRequested) { mutableStateOf(request?.seatsRequested ?: 1) }

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
                            onClick = { if (isStandalone) pickingRide = true else onAcceptAttached() },
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

            if (pickingRide) {
                if (myOpenRidesForAccept.isNotEmpty()) {
                    Text("Attach to one of your posted rides", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp))
                    myOpenRidesForAccept.forEach { ride ->
                        DetailCard(modifier = Modifier.fillMaxWidth(), onClick = { onAcceptStandalone(ride.rideId) }) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                RouteTimeline(describeLocation(ride.originLat, ride.originLng), describeLocation(ride.destinationLat, ride.destinationLng))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    FormStatusPill(formatTime(ride.departureTime))
                                    FormStatusPill("${ride.seatsAvailable} seat(s) free")
                                }
                            }
                        }
                    }
                }

                // Always available, not just as a fallback - accepting doesn't require having
                // already posted a matching ride first.
                FormSectionCard(
                    if (myOpenRidesForAccept.isEmpty()) "Create a ride for this" else "Or create a new ride",
                    supporting = "Total seats on the new ride - at least ${request.seatsRequested} to cover this request."
                ) {
                    CountStepper(newRideSeats, request.seatsRequested, 8, { newRideSeats = it },
                        unit = if (newRideSeats == 1) "seat" else "seats", subject = "seats on the new ride")
                    FieldMessage("Uses this request's route and time.", isError = false)
                    Button(
                        onClick = { onCreateRideAndAccept(newRideSeats) },
                        enabled = !isActionInFlight,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    ) { Text("Create ride & accept") }
                }
            }
        }
    }
}
