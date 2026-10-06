package com.example.ds04_monash_rideshare_application.feature.rides

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.Repository.Remote.CreateRideDraft
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideSummary
import com.example.ds04_monash_rideshare_application.Repository.Remote.StopInput
import com.example.ds04_monash_rideshare_application.core.ui.CampusRoutePicker
import com.example.ds04_monash_rideshare_application.core.ui.CountStepper
import com.example.ds04_monash_rideshare_application.core.ui.FormBanner
import com.example.ds04_monash_rideshare_application.core.ui.FormBannerKind
import com.example.ds04_monash_rideshare_application.core.ui.FormInfoRow
import com.example.ds04_monash_rideshare_application.core.ui.FormSectionCard
import com.example.ds04_monash_rideshare_application.core.ui.FormStatusPill
import com.example.ds04_monash_rideshare_application.core.ui.OptionalNoteField
import com.example.ds04_monash_rideshare_application.core.ui.ReviewDivider
import com.example.ds04_monash_rideshare_application.core.ui.ReviewSection
import com.example.ds04_monash_rideshare_application.core.ui.RideFlowScaffold
import com.example.ds04_monash_rideshare_application.core.ui.RoutePickerResult
import com.example.ds04_monash_rideshare_application.core.ui.SeatBlocks
import com.example.ds04_monash_rideshare_application.core.ui.StopPicker
import com.example.ds04_monash_rideshare_application.core.ui.combineDateAndTime
import com.example.ds04_monash_rideshare_application.core.ui.departureTooSoonMessage
import com.example.ds04_monash_rideshare_application.core.ui.isBookableDeparture
import com.example.ds04_monash_rideshare_application.ui.APPROVED_POINTS
import com.example.ds04_monash_rideshare_application.ui.NamedLocation
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

// Offer-a-Ride flow: driver picks a route (with optional multi-stop waypoints from the known named
// locations), when it departs, whether it repeats weekly, how many seats, then confirms.
// Phase 8B: presentation only (RideFlowScaffold + approved cards). State, validation and the published
// draft are exactly as before.
@Composable
fun OfferRideFlowScreen(
    isPublishing: Boolean,
    // The driver's one registered vehicle caps how many seats they can offer - Offer-a-Ride is
    // gated behind having a vehicle at all (see RideshareNavHost.kt), so this is always > 0 here.
    maxSeats: Int,
    onPublish: (CreateRideDraft, onSuccess: (RideSummary) -> Unit, onError: (String) -> Unit) -> Unit,
    onExit: () -> Unit,
    onPublished: (RideSummary) -> Unit,
    modifier: Modifier = Modifier,
    // The route picker inside the Route step; tests substitute a fake (see RequestRideFlowScreen).
    routeStep: @Composable (onResult: (RoutePickerResult?) -> Unit) -> Unit = { CampusRoutePicker(onResult = it) }
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
    val hint = when (step) {
        0 -> "Choose a campus and an approved point to continue."
        1 -> whenHint(date != null, time != null, date != null && time != null && !canGoNext)
        2 -> "Pick at least one day to continue."
        else -> null
    }

    RideFlowScaffold(
        title = "Offer a ride",
        stepLabels = OFFER_STEP_LABELS,
        currentStep = step,
        canGoNext = canGoNext,
        hint = hint,
        isSubmitting = isPublishing,
        finalLabel = "Publish ride",
        busyLabel = "Publishing ride…",
        onBackStep = { step-- },
        onNextStep = {
            if (step < OFFER_STEP_LABELS.lastIndex) {
                step++
                return@RideFlowScaffold
            }
            val route = routePickerResult ?: return@RideFlowScaffold
            val departureTime = combineDateAndTime(date, time) ?: return@RideFlowScaffold
            // The clock keeps moving while the driver fills in later steps - re-check at submit.
            if (!isBookableDeparture(departureTime)) {
                errorMessage = departureTooSoonMessage() + " Go back to When to change it."
                return@RideFlowScaffold
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
    ) {
        // The publish error lives with the Review step it belongs to; hidden while a retry is in flight.
        if (step == OFFER_STEP_LABELS.lastIndex && !isPublishing) {
            errorMessage?.let {
                FormBanner(it, FormBannerKind.Error, Modifier.testTag("ride-error"), title = "Ride not published")
            }
        }
        if (step in 1 until OFFER_STEP_LABELS.lastIndex) routePickerResult?.let { RideRouteRecap(it, selectedStops.size) }
        when (step) {
            0 -> OfferRouteStep(
                routePickerResult = routePickerResult,
                onRouteChange = { routePickerResult = it },
                routeStep = routeStep,
                selectedStops = selectedStops,
                onToggleStop = { location ->
                    selectedStops = if (selectedStops.contains(location)) {
                        selectedStops - location
                    } else {
                        selectedStops + location
                    }
                }
            )
            1 -> OfferWhenStep(date, time, onDateChange = { date = it }, onTimeChange = { time = it })
            2 -> RideRepeatStep(
                repeatWeekly, repeatDays, repeatWeeks,
                onRepeatWeeklyChange = { weekly ->
                    repeatWeekly = weekly
                    if (!weekly) repeatDays = emptySet()
                },
                onDaysChange = { repeatDays = it },
                onWeeksChange = { repeatWeeks = it },
                daysTitle = "Repeat on",
                daysHelp = "Pick at least one day."
            )
            3 -> OfferSeatsStep(seatsTotal, maxSeats, notes, onSeatsChange = { seatsTotal = it }, onNotesChange = { notes = it })
            else -> OfferReviewStep(routePickerResult, date, time, selectedStops, repeatDays, repeatWeeks, seatsTotal, notes)
        }
    }
}

@Composable
internal fun OfferRouteStep(
    routePickerResult: RoutePickerResult?,
    onRouteChange: (RoutePickerResult?) -> Unit,
    selectedStops: List<NamedLocation>,
    onToggleStop: (NamedLocation) -> Unit,
    routeStep: @Composable (onResult: (RoutePickerResult?) -> Unit) -> Unit = { CampusRoutePicker(onResult = it) }
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        routeStep(onRouteChange)
        if (routePickerResult != null) {
            val endpoints = setOf(routePickerResult.origin, routePickerResult.destination)
            FormSectionCard(
                "Optional stops", Modifier.testTag("offer-stops"),
                supporting = "Add approved places the ride passes on the way. Stops are visited in the order you add them. " +
                    "The distance and time shown for your route don’t include stops."
            ) {
                StopPicker(
                    places = APPROVED_POINTS.filter { it !in endpoints },
                    selected = selectedStops,
                    onToggle = onToggleStop
                )
            }
        }
    }
}

@Composable
internal fun OfferWhenStep(date: LocalDate?, time: LocalTime?, onDateChange: (LocalDate) -> Unit, onTimeChange: (LocalTime) -> Unit) {
    FormSectionCard("When does this ride leave?", Modifier.testTag("offer-when")) {
        DateTimeFields(date, time, onDateChange, onTimeChange)
    }
}

@Composable
internal fun OfferSeatsStep(seatsTotal: Int, maxSeats: Int, notes: String, onSeatsChange: (Int) -> Unit, onNotesChange: (String) -> Unit) {
    FormSectionCard("Passenger seats available", Modifier.testTag("offer-seats")) {
        CountStepper(seatsTotal, 1, maxSeats, onSeatsChange,
            unit = if (seatsTotal == 1) "seat" else "seats", subject = "passenger seats available")
        SeatBlocks(seatsTotal, capacity = maxSeats)
        FormInfoRow(vehicleSeatsSupport(maxSeats))
    }
    FormSectionCard("Note for passengers", Modifier.testTag("offer-note"), trailing = { FormStatusPill("Optional") }) {
        OptionalNoteField(notes, onNotesChange, label = "Anything passengers should know", subject = "Note for passengers")
    }
}

@Composable
internal fun OfferReviewStep(
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
    FormSectionCard("Review your ride", Modifier.testTag("offer-review")) {
        ReviewSection("ROUTE") { ReviewRouteBlock(route, via = stops.map { it.name }) }
        ReviewDivider()
        ReviewSection("WHEN") {
            if (date != null && time != null) Text(departureSummary(date, time), fontWeight = FontWeight.Bold)
            Text(
                repeatSummary(repeatDays, repeatWeeks)?.let { "Repeats on $it" } ?: "One-time trip",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        ReviewDivider()
        ReviewSection("SEATS OFFERED") { Text(seatsOfferedLabel(seatsTotal), fontWeight = FontWeight.Bold) }
        notes.trim().takeIf { it.isNotEmpty() }?.let {
            ReviewDivider()
            ReviewSection("NOTE") { Text("“$it”") }
        }
    }
}
