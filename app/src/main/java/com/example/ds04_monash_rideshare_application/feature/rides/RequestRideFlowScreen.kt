package com.example.ds04_monash_rideshare_application.feature.rides

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
import com.example.ds04_monash_rideshare_application.Repository.Remote.StandaloneRequestDraft
import com.example.ds04_monash_rideshare_application.core.state.RequestSubmitError
import com.example.ds04_monash_rideshare_application.core.ui.AsapToggleRow
import com.example.ds04_monash_rideshare_application.core.ui.CampusRoutePicker
import com.example.ds04_monash_rideshare_application.core.ui.CountStepper
import com.example.ds04_monash_rideshare_application.core.ui.FormInfoRow
import com.example.ds04_monash_rideshare_application.core.ui.FormSectionCard
import com.example.ds04_monash_rideshare_application.core.ui.FormStatusPill
import com.example.ds04_monash_rideshare_application.core.ui.OptionalNoteField
import com.example.ds04_monash_rideshare_application.core.ui.ReviewDivider
import com.example.ds04_monash_rideshare_application.core.ui.ReviewSection
import com.example.ds04_monash_rideshare_application.core.ui.RideFlowScaffold
import com.example.ds04_monash_rideshare_application.core.ui.RoutePickerResult
import com.example.ds04_monash_rideshare_application.core.ui.SeatBlocks
import com.example.ds04_monash_rideshare_application.core.ui.combineDateAndTime
import com.example.ds04_monash_rideshare_application.core.ui.departureTooSoonMessage
import com.example.ds04_monash_rideshare_application.core.ui.isBookableDeparture
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

// Request-a-Ride flow: a standalone ask with no driver picked yet - route + time, posted as
// PENDING ("waiting for a driver") until some driver accepts it from their Requests tab. Distinct
// from RequestSeatFlowScreen, which requests a seat on a ride you've already picked by browsing.
// Phase 8B: presentation only (RideFlowScaffold + approved cards). State, validation and the submitted
// draft are exactly as before.
@Composable
fun RequestRideFlowScreen(
    isSubmitting: Boolean,
    onSubmit: (StandaloneRequestDraft, onDone: (RequestSubmitError?) -> Unit) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    // The Route step. Defaults to the real picker; tests substitute a fake so later steps can be reached
    // without a live geocoder / driving-route lookup.
    routeStep: @Composable (onResult: (RoutePickerResult?) -> Unit) -> Unit = { CampusRoutePicker(onResult = it) },
    // Opens the request the backend says this one duplicates (shown as a "View" action on the banner).
    onViewRequest: (String) -> Unit = {}
) {
    var step by rememberSaveable { mutableStateOf(0) }
    var routePickerResult by remember { mutableStateOf<RoutePickerResult?>(null) }
    var isAsap by remember { mutableStateOf(true) }
    var date by remember { mutableStateOf<LocalDate?>(null) }
    var time by remember { mutableStateOf<LocalTime?>(null) }
    var repeatWeekly by remember { mutableStateOf(false) }
    var repeatDays by remember { mutableStateOf<Set<DayOfWeek>>(emptySet()) }
    var repeatWeeks by remember { mutableStateOf(4) }
    var seats by remember { mutableStateOf(1) }
    var note by remember { mutableStateOf("") }
    var submitError by remember { mutableStateOf<RequestSubmitError?>(null) }

    // ASAP has no scheduling concept, so the Repeat step (and any days already picked there)
    // never applies to it - drop straight from When to Details.
    val stepLabels = requestStepLabels(isAsap)
    val currentLabel = stepLabels.getOrNull(step)

    val canGoNext = when (currentLabel) {
        STEP_ROUTE -> routePickerResult != null
        STEP_WHEN -> isAsap || isBookableDeparture(combineDateAndTime(date, time))
        STEP_REPEAT -> !repeatWeekly || repeatDays.isNotEmpty()
        else -> true
    }
    val hint = when (currentLabel) {
        STEP_ROUTE -> "Choose a campus and an approved point to continue."
        STEP_WHEN -> whenHint(date != null, time != null, date != null && time != null && !canGoNext)
        STEP_REPEAT -> "Pick at least one day to continue."
        else -> null
    }

    RideFlowScaffold(
        title = "Request a ride",
        stepLabels = stepLabels,
        currentStep = step,
        canGoNext = canGoNext,
        hint = hint,
        isSubmitting = isSubmitting,
        finalLabel = "Confirm request",
        busyLabel = "Sending request…",
        onBackStep = { step-- },
        onNextStep = {
            if (step < stepLabels.lastIndex) {
                step++
                return@RideFlowScaffold
            }
            val route = routePickerResult ?: return@RideFlowScaffold
            // The clock keeps moving while the passenger fills in later steps - re-check at submit.
            if (!isAsap && !isBookableDeparture(combineDateAndTime(date, time))) {
                submitError = RequestSubmitError(departureTooSoonMessage() + " Go back to When to change it.")
                return@RideFlowScaffold
            }
            val draft = StandaloneRequestDraft(
                originLat = route.origin.point.latitude, originLng = route.origin.point.longitude,
                destinationLat = route.destination.point.latitude, destinationLng = route.destination.point.longitude,
                isAsap = isAsap,
                desiredDepartureTime = if (isAsap) null else combineDateAndTime(date, time),
                seatsRequested = seats,
                note = note.trim().takeIf { it.isNotBlank() },
                repeatDaysOfWeek = repeatDays.map { it.value }.sorted(),
                repeatWeeks = repeatWeeks.takeIf { repeatDays.isNotEmpty() }
            )
            onSubmit(draft) { error -> submitError = error }
        },
        onExit = onExit,
        modifier = modifier
    ) {
        // The submit error lives with the Review step it belongs to; hidden while a retry is in flight.
        if (currentLabel == STEP_REVIEW && !isSubmitting) {
            submitError?.let {
                RequestSubmitErrorBanner(it, failureTitle = "Request not sent", onViewRequest = onViewRequest, modifier = Modifier.testTag("ride-error"))
            }
        }
        if (currentLabel != STEP_ROUTE && currentLabel != STEP_REVIEW) routePickerResult?.let { RideRouteRecap(it) }
        when (currentLabel) {
            STEP_ROUTE -> routeStep { routePickerResult = it }
            STEP_WHEN -> RequestWhenStep(
                isAsap, date, time,
                onAsapChange = { checked ->
                    isAsap = checked
                    // Clear any days picked before switching to ASAP so a stale selection
                    // can't leak into the submitted draft or the review summary.
                    if (checked) {
                        repeatWeekly = false
                        repeatDays = emptySet()
                    }
                },
                onDateChange = { date = it },
                onTimeChange = { time = it }
            )
            STEP_REPEAT -> RideRepeatStep(
                repeatWeekly, repeatDays, repeatWeeks,
                onRepeatWeeklyChange = { weekly ->
                    repeatWeekly = weekly
                    if (!weekly) repeatDays = emptySet()
                },
                onDaysChange = { repeatDays = it },
                onWeeksChange = { repeatWeeks = it },
                daysTitle = "Also repeat on",
                daysHelp = "Pick at least one day. Each day becomes its own request a driver can accept."
            )
            STEP_DETAILS -> RequestDetailsStep(seats, note, onSeatsChange = { seats = it }, onNoteChange = { note = it })
            else -> RequestReviewStep(routePickerResult, isAsap, date, time, repeatDays, repeatWeeks, seats, note)
        }
    }
}

@Composable
internal fun RequestWhenStep(
    isAsap: Boolean,
    date: LocalDate?,
    time: LocalTime?,
    onAsapChange: (Boolean) -> Unit,
    onDateChange: (LocalDate) -> Unit,
    onTimeChange: (LocalTime) -> Unit
) {
    FormSectionCard("When do you need to leave?", Modifier.testTag("request-when")) {
        AsapToggleRow(isAsap, onAsapChange, Modifier.testTag("request-asap"))
        if (isAsap) {
            FormInfoRow("ASAP requests don’t need a date or time and can’t repeat. Turn it off to pick a set time — scheduled requests add a Repeat step.")
        } else {
            DateTimeFields(date, time, onDateChange, onTimeChange)
            FormInfoRow("Scheduled requests have 5 steps — a Repeat step is added after this one.")
        }
    }
}

@Composable
internal fun RequestDetailsStep(seats: Int, note: String, onSeatsChange: (Int) -> Unit, onNoteChange: (String) -> Unit) {
    FormSectionCard("Seats needed", Modifier.testTag("request-seats")) {
        CountStepper(seats, 1, MAX_REQUEST_SEATS, onSeatsChange, unit = if (seats == 1) "seat" else "seats", subject = "seats needed")
        SeatBlocks(seats)
        FormInfoRow("You can request 1 to $MAX_REQUEST_SEATS seats.")
    }
    FormSectionCard("Note to driver", Modifier.testTag("request-note"), trailing = { FormStatusPill("Optional") }) {
        OptionalNoteField(note, onNoteChange, label = "Anything the driver should know", subject = "Note to driver")
    }
}

@Composable
internal fun RequestReviewStep(
    routePickerResult: RoutePickerResult?,
    isAsap: Boolean,
    date: LocalDate?,
    time: LocalTime?,
    repeatDays: Set<DayOfWeek>,
    repeatWeeks: Int,
    seats: Int,
    note: String
) {
    val route = routePickerResult ?: return
    FormSectionCard("Review your request", Modifier.testTag("request-review")) {
        ReviewSection("ROUTE") { ReviewRouteBlock(route) }
        ReviewDivider()
        ReviewSection("WHEN") {
            if (isAsap) {
                Text("Leaving ASAP", fontWeight = FontWeight.Bold)
                Text("No set time — waits for a driver.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                if (date != null && time != null) Text(departureSummary(date, time), fontWeight = FontWeight.Bold)
                Text(
                    repeatSummary(repeatDays, repeatWeeks)?.let { "Also wants: $it" } ?: "One-time trip",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        ReviewDivider()
        ReviewSection("SEATS") { Text(seatsNeededLabel(seats), fontWeight = FontWeight.Bold) }
        note.trim().takeIf { it.isNotEmpty() }?.let {
            ReviewDivider()
            ReviewSection("NOTE") { Text("“$it”") }
        }
        FormInfoRow("No fare is shown. Your request is sent and waits for a driver to accept.")
    }
}
