package com.example.ds04_monash_rideshare_application.feature.rides

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideSummary
import com.example.ds04_monash_rideshare_application.core.state.RequestSubmitError
import com.example.ds04_monash_rideshare_application.core.state.Resource
import com.example.ds04_monash_rideshare_application.core.ui.ChoiceChipGroup
import com.example.ds04_monash_rideshare_application.core.ui.CountStepper
import com.example.ds04_monash_rideshare_application.core.ui.FieldMessage
import com.example.ds04_monash_rideshare_application.core.ui.FormInfoRow
import com.example.ds04_monash_rideshare_application.core.ui.FormSectionCard
import com.example.ds04_monash_rideshare_application.core.ui.FormStatusPill
import com.example.ds04_monash_rideshare_application.core.ui.OptionalNoteField
import com.example.ds04_monash_rideshare_application.core.ui.ReviewDivider
import com.example.ds04_monash_rideshare_application.core.ui.ReviewSection
import com.example.ds04_monash_rideshare_application.core.ui.RideFlowScaffold
import com.example.ds04_monash_rideshare_application.core.ui.RouteTimeline
import com.example.ds04_monash_rideshare_application.core.ui.SeatBlocks
import com.example.ds04_monash_rideshare_application.ui.describeLocation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val STEP_LABELS = listOf("Ride", "Repeat", "Seats", "Review")

// Requesting a seat on a ride you're already browsing (tapped into from Home/Requests's open
// rides list) - the ride and its time are already fixed, so this is shorter than the standalone
// Request-a-Ride flow (RequestRideFlowScreen): choose recurring dates, seats and a note, then
// send the request to the driver for approval. Same shell and pieces as Request/Offer a ride.
@Composable
fun RequestSeatFlowScreen(
    rideDetails: Resource<RideSummary>,
    isSubmitting: Boolean,
    onSubmit: (seats: Int, note: String?, applyToRecurringGroup: Boolean, onDone: (RequestSubmitError?) -> Unit) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    // Opens the request the backend says this one duplicates (shown as a "View" action on the banner).
    onViewRequest: (String) -> Unit = {}
) {
    var step by rememberSaveable { mutableStateOf(0) }
    var applyToRecurringGroup by rememberSaveable { mutableStateOf(false) }
    var seats by rememberSaveable { mutableStateOf(1) }
    var note by rememberSaveable { mutableStateOf("") }
    var submitError by remember { mutableStateOf<RequestSubmitError?>(null) }

    val ride = rideDetails.data

    RideFlowScaffold(
        title = "Request a seat",
        stepLabels = STEP_LABELS,
        currentStep = step,
        canGoNext = ride != null,
        hint = if (ride == null) "Loading this ride…" else null,
        isSubmitting = isSubmitting,
        finalLabel = "Send request",
        busyLabel = "Sending request…",
        onBackStep = { step-- },
        onNextStep = {
            if (step < STEP_LABELS.lastIndex) {
                step++
            } else if (ride != null) {
                onSubmit(seats, note.trim().takeIf { it.isNotBlank() }, applyToRecurringGroup) { error ->
                    submitError = error
                }
            }
        },
        onExit = onExit,
        modifier = modifier
    ) {
        if (rideDetails.isLoading || ride == null) {
            CircularProgressIndicator()
        } else {
            when (step) {
                0 -> RideStep(ride)
                1 -> RepeatStep(ride, applyToRecurringGroup) { applyToRecurringGroup = it }
                2 -> SeatsStep(ride, seats, note, onSeatsChange = { seats = it }, onNoteChange = { note = it })
                else -> ReviewStep(ride, seats, note, applyToRecurringGroup)
            }
        }
        submitError?.let { RequestSubmitErrorBanner(it, failureTitle = "Couldn’t send your request", onViewRequest = onViewRequest) }
    }
}

private fun rideOrigin(ride: RideSummary) = describeLocation(ride.originLat, ride.originLng)
private fun rideDestination(ride: RideSummary) = describeLocation(ride.destinationLat, ride.destinationLng)
private fun rideTime(ride: RideSummary) = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(ride.departureTime))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RideStep(ride: RideSummary) {
    FormSectionCard("The ride") {
        RouteTimeline(rideOrigin(ride), rideDestination(ride))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FormStatusPill(rideTime(ride))
            FormStatusPill("${ride.seatsAvailable} of ${ride.seatsTotal} seats free")
        }
        Text("Driver: ${ride.driver?.displayName ?: ride.driverId}", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RepeatStep(ride: RideSummary, applyToRecurringGroup: Boolean, onChange: (Boolean) -> Unit) {
    if (ride.recurringGroupId == null) {
        FormSectionCard("How often?") {
            FormInfoRow("This ride doesn't repeat - you're only requesting this one trip.")
        }
        return
    }
    FormSectionCard("How often?", supporting = "This driver repeats this trip on other days too.") {
        ChoiceChipGroup(
            options = listOf(false, true), selected = applyToRecurringGroup,
            label = { if (it) "Every date in this series" else "Just this trip" },
            onSelect = onChange, groupDescription = "How often"
        )
    }
}

@Composable
private fun SeatsStep(ride: RideSummary, seats: Int, note: String, onSeatsChange: (Int) -> Unit, onNoteChange: (String) -> Unit) {
    FormSectionCard("Seats needed", supporting = "${ride.seatsAvailable} free on this ride.") {
        CountStepper(seats, 1, ride.seatsAvailable.coerceAtLeast(1), onSeatsChange,
            unit = if (seats == 1) "seat" else "seats", subject = "seats")
        SeatBlocks(seats)
    }
    FormSectionCard("Note to the driver") {
        OptionalNoteField(note, onNoteChange, label = "Note (optional)", subject = "Note to the driver")
        FieldMessage("e.g. where you'll wait, or what you're wearing.", isError = false)
    }
}

@Composable
private fun ReviewStep(ride: RideSummary, seats: Int, note: String, applyToRecurringGroup: Boolean) {
    FormSectionCard("Review your request") {
        ReviewSection("ROUTE") { RouteTimeline(rideOrigin(ride), rideDestination(ride)) }
        ReviewDivider()
        ReviewSection("WHEN") {
            Text(rideTime(ride), fontWeight = FontWeight.Bold)
            if (applyToRecurringGroup) Text("And every other date in this series")
        }
        ReviewDivider()
        ReviewSection("SEATS") { Text("$seats ${if (seats == 1) "seat" else "seats"}", fontWeight = FontWeight.Bold) }
        if (note.isNotBlank()) {
            ReviewDivider()
            ReviewSection("NOTE") { Text(note.trim()) }
        }
    }
    FormInfoRow("The driver will review this request. Your seat is confirmed only after they accept it.")
}
