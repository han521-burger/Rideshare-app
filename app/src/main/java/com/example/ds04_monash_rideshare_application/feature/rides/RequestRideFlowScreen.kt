package com.example.ds04_monash_rideshare_application.feature.rides

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideSummary
import com.example.ds04_monash_rideshare_application.core.state.Resource
import com.example.ds04_monash_rideshare_application.core.ui.StepWizardScaffold
import com.example.ds04_monash_rideshare_application.ui.describeLocation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val STEP_LABELS = listOf("Ride", "Repeat", "Note", "Confirm")

// Request-a-Ride flow: the ride is already picked (from Home/Requests -> Ride Detail -> "Request
// a seat"), so this wizard only covers what the passenger still needs to decide: whether to apply
// to the whole recurring series, how many seats, an optional note, then confirm.
@Composable
fun RequestRideFlowScreen(
    rideDetails: Resource<RideSummary>,
    isSubmitting: Boolean,
    onSubmit: (seats: Int, note: String?, applyToRecurringGroup: Boolean, onDone: (String?) -> Unit) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    var step by rememberSaveable { mutableStateOf(0) }
    var applyToRecurringGroup by rememberSaveable { mutableStateOf(false) }
    var seats by rememberSaveable { mutableStateOf(1) }
    var note by rememberSaveable { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val ride = rideDetails.data

    StepWizardScaffold(
        title = "Request a ride",
        stepLabels = STEP_LABELS,
        currentStep = step,
        canGoNext = ride != null,
        isSubmitting = isSubmitting,
        onBackStep = { step-- },
        onNextStep = {
            if (step < STEP_LABELS.lastIndex) {
                step++
            } else if (ride != null) {
                onSubmit(seats, note.trim().takeIf { it.isNotBlank() }, applyToRecurringGroup) { error ->
                    errorMessage = error
                }
            }
        },
        onExit = onExit,
        modifier = modifier
    ) { contentPadding ->
        Column(modifier = Modifier.padding(contentPadding).padding(16.dp).fillMaxSize()) {
            if (rideDetails.isLoading || ride == null) {
                CircularProgressIndicator()
            } else {
                when (step) {
                    0 -> RideStep(ride)
                    1 -> RepeatStep(ride, applyToRecurringGroup) { applyToRecurringGroup = it }
                    2 -> NoteStep(ride, seats, note, onSeatsChange = { seats = it }, onNoteChange = { note = it })
                    else -> ConfirmStep(ride, seats, note, applyToRecurringGroup)
                }
            }
            errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
        }
    }
}

@Composable
private fun RideStep(ride: RideSummary) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "${describeLocation(ride.originLat, ride.originLng)} → ${describeLocation(ride.destinationLat, ride.destinationLng)}",
                fontWeight = FontWeight.SemiBold
            )
            Text(SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(ride.departureTime)))
            Text("Driver: ${ride.driver?.displayName ?: ride.driverId}")
            Text("${ride.seatsAvailable} of ${ride.seatsTotal} seats free")
        }
    }
}

@Composable
private fun RepeatStep(ride: RideSummary, applyToRecurringGroup: Boolean, onChange: (Boolean) -> Unit) {
    if (ride.recurringGroupId == null) {
        Text("This ride doesn't repeat - you're only requesting this one trip.")
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Request every date in this series", fontWeight = FontWeight.SemiBold)
            Text(
                "This driver repeats this trip. Turn this on to request a seat on every upcoming date instead of just this one.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        Switch(checked = applyToRecurringGroup, onCheckedChange = onChange)
    }
}

@Composable
private fun NoteStep(ride: RideSummary, seats: Int, note: String, onSeatsChange: (Int) -> Unit, onNoteChange: (String) -> Unit) {
    Column {
        Text("Seats needed", fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            OutlinedButton(onClick = { if (seats > 1) onSeatsChange(seats - 1) }) { Text("-") }
            Text(seats.toString(), modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { if (seats < ride.seatsAvailable) onSeatsChange(seats + 1) }) { Text("+") }
        }
        OutlinedTextField(
            value = note,
            onValueChange = onNoteChange,
            label = { Text("Note to driver (optional)") },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        )
    }
}

@Composable
private fun ConfirmStep(ride: RideSummary, seats: Int, note: String, applyToRecurringGroup: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Review your request", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("${describeLocation(ride.originLat, ride.originLng)} → ${describeLocation(ride.destinationLat, ride.destinationLng)}")
            Text(SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(ride.departureTime)))
            Text("$seats seat(s)")
            if (applyToRecurringGroup) Text("Applies to every date in this series")
            note.takeIf { it.isNotBlank() }?.let { Text("Note: $it") }
        }
    }
}
