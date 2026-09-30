package com.example.ds04_monash_rideshare_application.feature.rides

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.Repository.Remote.StandaloneRequestDraft
import com.example.ds04_monash_rideshare_application.core.ui.DatePickerField
import com.example.ds04_monash_rideshare_application.core.ui.StepWizardScaffold
import com.example.ds04_monash_rideshare_application.core.ui.TimePickerField
import com.example.ds04_monash_rideshare_application.core.ui.combineDateAndTime
import com.example.ds04_monash_rideshare_application.ui.NAMED_LOCATIONS
import com.example.ds04_monash_rideshare_application.ui.NamedLocation
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

private val ASAP_STEP_LABELS = listOf("Search", "When", "Note", "Confirm")
private val SCHEDULED_STEP_LABELS = listOf("Search", "When", "Repeat", "Note", "Confirm")
private val WEEK_DAYS = listOf(
    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY
)

// Request-a-Ride flow: a standalone ask with no driver picked yet - route + time, posted as
// PENDING ("waiting for a driver") until some driver accepts it from their Requests tab. Distinct
// from RequestSeatFlowScreen, which requests a seat on a ride you've already picked by browsing.
@Composable
fun RequestRideFlowScreen(
    isSubmitting: Boolean,
    onSubmit: (StandaloneRequestDraft, onDone: (String?) -> Unit) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    var step by rememberSaveable { mutableStateOf(0) }
    var origin by remember { mutableStateOf<NamedLocation?>(null) }
    var destination by remember { mutableStateOf<NamedLocation?>(null) }
    var isAsap by remember { mutableStateOf(true) }
    var date by remember { mutableStateOf<LocalDate?>(null) }
    var time by remember { mutableStateOf<LocalTime?>(null) }
    var repeatDays by remember { mutableStateOf<Set<DayOfWeek>>(emptySet()) }
    var repeatWeeks by remember { mutableStateOf(4) }
    var seats by remember { mutableStateOf(1) }
    var note by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // ASAP has no scheduling concept, so the Repeat step (and any days already picked there)
    // never applies to it - drop straight from When to Note.
    val stepLabels = if (isAsap) ASAP_STEP_LABELS else SCHEDULED_STEP_LABELS

    val canGoNext = when (step) {
        0 -> origin != null && destination != null
        1 -> isAsap || (date != null && time != null)
        else -> true
    }

    StepWizardScaffold(
        title = "Request a ride",
        stepLabels = stepLabels,
        currentStep = step,
        canGoNext = canGoNext,
        isSubmitting = isSubmitting,
        onBackStep = { step-- },
        onNextStep = {
            if (step < stepLabels.lastIndex) {
                step++
                return@StepWizardScaffold
            }
            val from = origin ?: return@StepWizardScaffold
            val to = destination ?: return@StepWizardScaffold
            val draft = StandaloneRequestDraft(
                originLat = from.point.latitude, originLng = from.point.longitude,
                destinationLat = to.point.latitude, destinationLng = to.point.longitude,
                isAsap = isAsap,
                desiredDepartureTime = if (isAsap) null else combineDateAndTime(date, time),
                seatsRequested = seats,
                note = note.trim().takeIf { it.isNotBlank() },
                repeatDaysOfWeek = repeatDays.map { it.value }.sorted(),
                repeatWeeks = repeatWeeks.takeIf { repeatDays.isNotEmpty() }
            )
            onSubmit(draft) { error -> errorMessage = error }
        },
        onExit = onExit,
        modifier = modifier
    ) { contentPadding: PaddingValues ->
        Column(
            modifier = Modifier
                .padding(contentPadding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            when (stepLabels.getOrNull(step)) {
                "Search" -> SearchStep(origin, destination, onOriginChange = { origin = it }, onDestinationChange = { destination = it })
                "When" -> WhenStep(
                    isAsap, date, time,
                    onAsapChange = { checked ->
                        isAsap = checked
                        // Clear any days picked before switching to ASAP so a stale selection
                        // can't leak into the submitted draft or the Confirm summary.
                        if (checked) repeatDays = emptySet()
                    },
                    onDateChange = { date = it },
                    onTimeChange = { time = it }
                )
                "Repeat" -> RepeatStep(repeatDays, repeatWeeks, onDaysChange = { repeatDays = it }, onWeeksChange = { repeatWeeks = it })
                "Note" -> NoteStep(seats, note, onSeatsChange = { seats = it }, onNoteChange = { note = it })
                else -> ConfirmStep(origin, destination, isAsap, date, time, repeatDays, repeatWeeks, seats, note)
            }
            errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
        }
    }
}

@Composable
private fun SearchStep(
    origin: NamedLocation?,
    destination: NamedLocation?,
    onOriginChange: (NamedLocation) -> Unit,
    onDestinationChange: (NamedLocation) -> Unit
) {
    Column {
        Text(
            "No live location in this trial, so pick your pickup and destination from known places.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text("Pickup", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            items(NAMED_LOCATIONS, key = { "origin-${it.name}" }) { location ->
                FilterChip(selected = origin == location, onClick = { onOriginChange(location) }, label = { Text(location.name) })
            }
        }
        Text("Destination", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            items(NAMED_LOCATIONS, key = { "dest-${it.name}" }) { location ->
                FilterChip(selected = destination == location, onClick = { onDestinationChange(location) }, label = { Text(location.name) })
            }
        }
    }
}

@Composable
private fun WhenStep(
    isAsap: Boolean,
    date: LocalDate?,
    time: LocalTime?,
    onAsapChange: (Boolean) -> Unit,
    onDateChange: (LocalDate) -> Unit,
    onTimeChange: (LocalTime) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Leave ASAP", fontWeight = FontWeight.SemiBold)
            Switch(checked = isAsap, onCheckedChange = onAsapChange)
        }
        if (!isAsap) {
            DatePickerField("Pick a date", date, onDateChange, modifier = Modifier.padding(top = 12.dp))
            TimePickerField("Pick a time", time, onTimeChange, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun RepeatStep(
    repeatDays: Set<DayOfWeek>,
    repeatWeeks: Int,
    onDaysChange: (Set<DayOfWeek>) -> Unit,
    onWeeksChange: (Int) -> Unit
) {
    Column {
        Text("Also want this on:", fontWeight = FontWeight.SemiBold)
        Text(
            "Optional - shown to drivers as a note, not a separate request for each day.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            items(WEEK_DAYS) { day ->
                FilterChip(
                    selected = repeatDays.contains(day),
                    onClick = { onDaysChange(if (repeatDays.contains(day)) repeatDays - day else repeatDays + day) },
                    label = { Text(day.name.take(3)) }
                )
            }
        }
        if (repeatDays.isNotEmpty()) {
            Text("For how many weeks?", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                OutlinedButton(onClick = { if (repeatWeeks > 1) onWeeksChange(repeatWeeks - 1) }) { Text("-") }
                Text(repeatWeeks.toString(), modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { if (repeatWeeks < 12) onWeeksChange(repeatWeeks + 1) }) { Text("+") }
            }
        }
    }
}

@Composable
private fun NoteStep(seats: Int, note: String, onSeatsChange: (Int) -> Unit, onNoteChange: (String) -> Unit) {
    Column {
        Text("Seats needed", fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            OutlinedButton(onClick = { if (seats > 1) onSeatsChange(seats - 1) }) { Text("-") }
            Text(seats.toString(), modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { if (seats < 4) onSeatsChange(seats + 1) }) { Text("+") }
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
private fun ConfirmStep(
    origin: NamedLocation?,
    destination: NamedLocation?,
    isAsap: Boolean,
    date: LocalDate?,
    time: LocalTime?,
    repeatDays: Set<DayOfWeek>,
    repeatWeeks: Int,
    seats: Int,
    note: String
) {
    if (origin == null || destination == null) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Review your request", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("${origin.name} → ${destination.name}")
            Text(if (isAsap) "Leaving ASAP" else "Scheduled${if (date != null && time != null) " for $date at $time" else ""}")
            Text("$seats seat(s)")
            if (repeatDays.isNotEmpty()) {
                Text("Also wants: ${repeatDays.sortedBy { it.value }.joinToString(", ") { it.name.take(3) }} for $repeatWeeks week(s)")
            }
            note.takeIf { it.isNotBlank() }?.let { Text("Note: $it") }
            Text(
                "No fare shown - request sent, waiting for a driver.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
