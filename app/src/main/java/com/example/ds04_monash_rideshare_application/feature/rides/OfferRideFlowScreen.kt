package com.example.ds04_monash_rideshare_application.feature.rides

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.example.ds04_monash_rideshare_application.Repository.Remote.CreateRideDraft
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideSummary
import com.example.ds04_monash_rideshare_application.Repository.Remote.StopInput
import com.example.ds04_monash_rideshare_application.core.ui.CampusRoutePicker
import com.example.ds04_monash_rideshare_application.core.ui.DatePickerField
import com.example.ds04_monash_rideshare_application.core.ui.RoutePickerResult
import com.example.ds04_monash_rideshare_application.core.ui.StepWizardScaffold
import com.example.ds04_monash_rideshare_application.core.ui.TimePickerField
import com.example.ds04_monash_rideshare_application.core.ui.combineDateAndTime
import com.example.ds04_monash_rideshare_application.core.ui.departureTooSoonMessage
import com.example.ds04_monash_rideshare_application.core.ui.isBookableDeparture
import com.example.ds04_monash_rideshare_application.ui.FIXED_POINTS
import com.example.ds04_monash_rideshare_application.ui.NamedLocation
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.Date
import java.util.Locale

private val STEP_LABELS = listOf("Route", "When", "Repeat", "Seats", "Confirm")
private val WEEK_DAYS = listOf(
    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY
)

// Offer-a-Ride flow: driver picks a route on the map (with optional multi-stop waypoints from the
// known named locations), when it departs, whether it repeats weekly, how many seats, then confirms.
@Composable
fun OfferRideFlowScreen(
    isPublishing: Boolean,
    // The driver's one registered vehicle caps how many seats they can offer - Offer-a-Ride is
    // gated behind having a vehicle at all (see RideshareNavHost.kt), so this is always > 0 here.
    maxSeats: Int,
    onPublish: (CreateRideDraft, onSuccess: (RideSummary) -> Unit, onError: (String) -> Unit) -> Unit,
    onExit: () -> Unit,
    onPublished: (RideSummary) -> Unit,
    modifier: Modifier = Modifier
) {
    var step by rememberSaveable { mutableStateOf(0) }
    var routePickerResult by remember { mutableStateOf<RoutePickerResult?>(null) }
    var selectedStops by remember { mutableStateOf<List<NamedLocation>>(emptyList()) }
    var date by remember { mutableStateOf<LocalDate?>(null) }
    var time by remember { mutableStateOf<LocalTime?>(null) }
    var repeatWeekly by remember { mutableStateOf(false) }
    var repeatDays by remember { mutableStateOf<Set<DayOfWeek>>(emptySet()) }
    var repeatWeeks by remember { mutableStateOf(4) }
    var seatsTotal by remember { mutableStateOf(minOf(3, maxSeats)) }
    var notes by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val canGoNext = when (step) {
        0 -> routePickerResult != null
        1 -> isBookableDeparture(combineDateAndTime(date, time))
        2 -> !repeatWeekly || repeatDays.isNotEmpty()
        3 -> seatsTotal in 1..maxSeats
        else -> true
    }

    StepWizardScaffold(
        title = "Offer a ride",
        stepLabels = STEP_LABELS,
        currentStep = step,
        canGoNext = canGoNext,
        isSubmitting = isPublishing,
        onBackStep = { step-- },
        onNextStep = {
            if (step < STEP_LABELS.lastIndex) {
                step++
                return@StepWizardScaffold
            }
            val route = routePickerResult ?: return@StepWizardScaffold
            val departureTime = combineDateAndTime(date, time) ?: return@StepWizardScaffold
            // The clock keeps moving while the driver fills in later steps - re-check at submit.
            if (!isBookableDeparture(departureTime)) {
                errorMessage = departureTooSoonMessage() + " Go back to When to change it."
                return@StepWizardScaffold
            }
            val draft = CreateRideDraft(
                originLat = route.origin.point.latitude, originLng = route.origin.point.longitude,
                destinationLat = route.destination.point.latitude, destinationLng = route.destination.point.longitude,
                distanceMeters = route.distanceMeters,
                estimatedDurationSeconds = route.estimatedDurationSeconds,
                departureTime = departureTime,
                seatsTotal = seatsTotal,
                notes = notes.trim().takeIf { it.isNotBlank() },
                stops = selectedStops.map { StopInput(it.point.latitude, it.point.longitude, it.name) },
                repeatDaysOfWeek = repeatDays.map { it.value }.sorted(),
                repeatWeeks = repeatWeeks
            )
            onPublish(draft, onPublished) { error -> errorMessage = error }
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
            when (step) {
                0 -> RouteStep(
                    routePickerResult = routePickerResult,
                    onRouteChange = { routePickerResult = it },
                    selectedStops = selectedStops,
                    onToggleStop = { location ->
                        selectedStops = if (selectedStops.contains(location)) {
                            selectedStops - location
                        } else {
                            selectedStops + location
                        }
                    }
                )
                1 -> WhenStep(date, time, onDateChange = { date = it }, onTimeChange = { time = it })
                2 -> RepeatStep(
                    repeatWeekly, repeatDays, repeatWeeks,
                    onRepeatWeeklyChange = { weekly ->
                        repeatWeekly = weekly
                        if (!weekly) repeatDays = emptySet()
                    },
                    onDaysChange = { repeatDays = it },
                    onWeeksChange = { repeatWeeks = it }
                )
                3 -> SeatsStep(seatsTotal, maxSeats, notes, onSeatsChange = { seatsTotal = it }, onNotesChange = { notes = it })
                else -> ConfirmOfferStep(routePickerResult, date, time, selectedStops, repeatDays, repeatWeeks, seatsTotal, notes)
            }
            errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
        }
    }
}

@Composable
private fun RouteStep(
    routePickerResult: RoutePickerResult?,
    onRouteChange: (RoutePickerResult?) -> Unit,
    selectedStops: List<NamedLocation>,
    onToggleStop: (NamedLocation) -> Unit
) {
    Column {
        CampusRoutePicker(onResult = onRouteChange)
        if (routePickerResult != null) {
            Text("Optional stops along the way", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                items(FIXED_POINTS, key = { it.name }) { location ->
                    FilterChip(
                        selected = selectedStops.contains(location),
                        onClick = { onToggleStop(location) },
                        label = { Text(location.name) }
                    )
                }
            }
            if (selectedStops.isNotEmpty()) {
                Text(
                    "Order: " + selectedStops.joinToString(" → ") { it.name },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun WhenStep(date: LocalDate?, time: LocalTime?, onDateChange: (LocalDate) -> Unit, onTimeChange: (LocalTime) -> Unit) {
    Column {
        Text("When does this ride leave?", fontWeight = FontWeight.SemiBold)
        DatePickerField("Pick a date", date, onDateChange, modifier = Modifier.padding(top = 12.dp), allowPastDates = false)
        TimePickerField("Pick a time", time, onTimeChange, modifier = Modifier.padding(top = 8.dp))
        if (date != null && time != null && !isBookableDeparture(combineDateAndTime(date, time))) {
            Text(departureTooSoonMessage(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun RepeatStep(
    repeatWeekly: Boolean,
    repeatDays: Set<DayOfWeek>,
    repeatWeeks: Int,
    onRepeatWeeklyChange: (Boolean) -> Unit,
    onDaysChange: (Set<DayOfWeek>) -> Unit,
    onWeeksChange: (Int) -> Unit
) {
    Column {
        Text("How often?", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            FilterChip(selected = !repeatWeekly, onClick = { onRepeatWeeklyChange(false) }, label = { Text("One-time only") })
            FilterChip(selected = repeatWeekly, onClick = { onRepeatWeeklyChange(true) }, label = { Text("Repeat weekly") })
        }
        if (!repeatWeekly) return@Column
        Text("Repeat on:", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
        Text(
            "Pick at least one day.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            items(WEEK_DAYS) { day ->
                FilterChip(
                    selected = repeatDays.contains(day),
                    onClick = {
                        onDaysChange(if (repeatDays.contains(day)) repeatDays - day else repeatDays + day)
                    },
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
private fun SeatsStep(seatsTotal: Int, maxSeats: Int, notes: String, onSeatsChange: (Int) -> Unit, onNotesChange: (String) -> Unit) {
    Column {
        Text("How many seats are you offering?", fontWeight = FontWeight.SemiBold)
        Text(
            "Up to $maxSeats - your registered vehicle's seat capacity.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            OutlinedButton(onClick = { if (seatsTotal > 1) onSeatsChange(seatsTotal - 1) }) { Text("-") }
            Text(seatsTotal.toString(), modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { if (seatsTotal < maxSeats) onSeatsChange(seatsTotal + 1) }) { Text("+") }
        }
        OutlinedTextField(
            value = notes,
            onValueChange = onNotesChange,
            label = { Text("Note for passengers (optional)") },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        )
    }
}

@Composable
private fun ConfirmOfferStep(
    routePickerResult: RoutePickerResult?,
    date: LocalDate?,
    time: LocalTime?,
    stops: List<NamedLocation>,
    repeatDays: Set<DayOfWeek>,
    repeatWeeks: Int,
    seatsTotal: Int,
    notes: String
) {
    val route = routePickerResult ?: return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Review your ride", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("${route.origin.name} → ${route.destination.name}")
            if (stops.isNotEmpty()) Text("Via: " + stops.joinToString(", ") { it.name })
            val departure = combineDateAndTime(date, time)
            if (departure != null) Text(SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(departure)))
            Text("%.1f km, about %d min".format(route.distanceMeters / 1000.0, route.estimatedDurationSeconds / 60))
            Text("$seatsTotal seat(s) offered")
            if (repeatDays.isNotEmpty()) {
                Text("Repeats on ${repeatDays.sortedBy { it.value }.joinToString(", ") { it.name.take(3) }} for $repeatWeeks week(s)")
            } else {
                Text("One-time trip")
            }
            notes.takeIf { it.isNotBlank() }?.let { Text("Note: $it") }
        }
    }
}
