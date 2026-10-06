package com.example.ds04_monash_rideshare_application.feature.rides

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.core.ui.ChoiceChipGroup
import com.example.ds04_monash_rideshare_application.core.ui.CountStepper
import com.example.ds04_monash_rideshare_application.core.ui.DatePickerField
import com.example.ds04_monash_rideshare_application.core.ui.FieldMessage
import com.example.ds04_monash_rideshare_application.core.ui.FormInfoRow
import com.example.ds04_monash_rideshare_application.core.ui.FormSectionCard
import com.example.ds04_monash_rideshare_application.core.ui.FormStatusPill
import com.example.ds04_monash_rideshare_application.core.ui.PickerChoice
import com.example.ds04_monash_rideshare_application.core.ui.RoutePickerResult
import com.example.ds04_monash_rideshare_application.core.ui.RouteRecap
import com.example.ds04_monash_rideshare_application.core.ui.RouteTimeline
import com.example.ds04_monash_rideshare_application.core.ui.TimePickerField
import com.example.ds04_monash_rideshare_application.core.ui.ToggleChip
import com.example.ds04_monash_rideshare_application.core.ui.combineDateAndTime
import com.example.ds04_monash_rideshare_application.core.ui.departureTooSoonMessage
import com.example.ds04_monash_rideshare_application.core.ui.isBookableDeparture
import com.example.ds04_monash_rideshare_application.core.ui.MIN_BOOKING_LEAD_MINUTES
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

// Step content shared by Request Ride and Offer Ride (Phase 8A design). State stays in the screens.

@Composable
internal fun RideRouteRecap(route: RoutePickerResult, stopCount: Int = 0) =
    RouteRecap(route.origin.name, route.destination.name,
        routeRecapDetail(route.distanceMeters, route.estimatedDurationSeconds, stopCount))

/** Date + time entry (unchanged Material pickers, same limits) with the lead-time message attached. */
@Composable
internal fun ColumnScope.DateTimeFields(
    date: LocalDate?,
    time: LocalTime?,
    onDateChange: (LocalDate) -> Unit,
    onTimeChange: (LocalTime) -> Unit
) {
    val tooSoon = date != null && time != null && !isBookableDeparture(combineDateAndTime(date, time))
    DatePickerField(
        "Pick a date", date, onDateChange, allowPastDates = false,
        trigger = { value, onClick ->
            PickerChoice(Icons.Rounded.CalendarToday, "Date", value, "Choose a date", onClick, Modifier.testTag("ride-date"))
        }
    )
    TimePickerField(
        "Pick a time", time, onTimeChange,
        trigger = { value, onClick ->
            PickerChoice(Icons.Rounded.Schedule, "Time (24-hour)", value, "Choose a time", onClick, Modifier.testTag("ride-time"), errored = tooSoon)
        }
    )
    when {
        tooSoon -> FieldMessage(departureTooSoonMessage(), isError = true)
        date != null && time != null -> Text(
            "Leaving ${departureSummary(date, time)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold
        )
        else -> FieldMessage("Rides must leave at least $MIN_BOOKING_LEAD_MINUTES minutes from now.", isError = false)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RideRepeatStep(
    repeatWeekly: Boolean,
    repeatDays: Set<DayOfWeek>,
    repeatWeeks: Int,
    onRepeatWeeklyChange: (Boolean) -> Unit,
    onDaysChange: (Set<DayOfWeek>) -> Unit,
    onWeeksChange: (Int) -> Unit,
    daysTitle: String,
    daysHelp: String
) {
    FormSectionCard("How often?", Modifier.testTag("ride-repeat")) {
        ChoiceChipGroup(
            options = listOf(false, true), selected = repeatWeekly,
            label = { if (it) "Repeat weekly" else "One-time only" },
            onSelect = onRepeatWeeklyChange, groupDescription = "How often"
        )
    }
    if (!repeatWeekly) return
    FormSectionCard(daysTitle, Modifier.testTag("ride-repeat-days"), supporting = daysHelp) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WEEK_DAYS.forEach { day ->
                ToggleChip(dayShortLabel(day), day in repeatDays,
                    onToggle = { onDaysChange(if (day in repeatDays) repeatDays - day else repeatDays + day) })
            }
        }
    }
    if (repeatDays.isNotEmpty()) {
        FormSectionCard("For how many weeks?", supporting = "From $MIN_REPEAT_WEEKS to $MAX_REPEAT_WEEKS weeks.") {
            CountStepper(repeatWeeks, MIN_REPEAT_WEEKS, MAX_REPEAT_WEEKS, onWeeksChange,
                unit = if (repeatWeeks == 1) "week" else "weeks", subject = "weeks")
            FormInfoRow(repeatSummary(repeatDays, repeatWeeks).orEmpty())
        }
    }
}

/** ROUTE review block: FROM / VIA / TO plus the (already calculated) distance and time. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReviewRouteBlock(route: RoutePickerResult, via: List<String> = emptyList()) {
    RouteTimeline(route.origin.name, route.destination.name, via = via)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FormStatusPill(if (via.isEmpty()) routeKmLabel(route.distanceMeters) else "Direct route ${routeKmLabel(route.distanceMeters)}")
        FormStatusPill("about ${routeMinutesLabel(route.estimatedDurationSeconds)}")
    }
    if (via.isNotEmpty()) FieldMessage(DIRECT_ROUTE_NOTE, isError = false)
}
