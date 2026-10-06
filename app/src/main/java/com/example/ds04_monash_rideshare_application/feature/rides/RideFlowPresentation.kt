package com.example.ds04_monash_rideshare_application.feature.rides

import com.example.ds04_monash_rideshare_application.core.ui.MIN_BOOKING_LEAD_MINUTES
import com.example.ds04_monash_rideshare_application.core.ui.formatDate
import com.example.ds04_monash_rideshare_application.core.ui.formatTime
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

// Presentation-only helpers for the Request Ride / Offer Ride wizards (Phase 8A design). Nothing here
// changes what is submitted: step lists, copy and formatting only.

internal const val STEP_ROUTE = "Route"
internal const val STEP_WHEN = "When"
internal const val STEP_REPEAT = "Repeat"
internal const val STEP_DETAILS = "Details"
internal const val STEP_REVIEW = "Review"

/** Request has no Repeat step for ASAP (an ASAP ask has no schedule), so its length is 4 or 5. */
internal fun requestStepLabels(isAsap: Boolean): List<String> =
    if (isAsap) listOf(STEP_ROUTE, STEP_WHEN, STEP_DETAILS, STEP_REVIEW)
    else listOf(STEP_ROUTE, STEP_WHEN, STEP_REPEAT, STEP_DETAILS, STEP_REVIEW)

/** Seats a passenger can ask for in one request - the same limit the legacy stepper enforced in the UI. */
internal const val MAX_REQUEST_SEATS = 4
internal const val MIN_REPEAT_WEEKS = 1
internal const val MAX_REPEAT_WEEKS = 12

/** "Thu 8 Oct 2026 · 08:30" - the same date and time formats the pickers display. */
internal fun departureSummary(date: LocalDate, time: LocalTime): String = "${formatDate(date)} · ${formatTime(time)}"

internal val OFFER_STEP_LABELS = listOf(STEP_ROUTE, STEP_WHEN, STEP_REPEAT, STEP_DETAILS, STEP_REVIEW)

internal val WEEK_DAYS = listOf(
    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY
)

/** "Mon", "Tue"... - fixed English short names so the review matches the chips in any locale. */
internal fun dayShortLabel(day: DayOfWeek): String =
    day.name.take(3).lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }

internal fun dayListLabel(days: Set<DayOfWeek>): String =
    days.sortedBy { it.value }.joinToString(", ") { dayShortLabel(it) }

internal fun weeksLabel(weeks: Int): String = "$weeks ${if (weeks == 1) "week" else "weeks"}"

/** Heading of the search/result sections: the searched place is the drop-off From Monash, else the pickup. */
internal fun searchPointNoun(fromMonash: Boolean): String = if (fromMonash) "drop-off" else "pickup"

internal fun searchCardTitle(fromMonash: Boolean): String = "${searchPointNoun(fromMonash).replaceFirstChar { it.titlecase(Locale.ROOT) }} point"

internal fun nearestHeading(fromMonash: Boolean): String = "Nearest ${searchPointNoun(fromMonash)} points"

internal fun directionSummary(campus: String?, fromMonash: Boolean): String = when {
    campus == null -> "Choose a campus to start."
    fromMonash -> "You’ll travel from $campus to an approved drop-off point."
    else -> "You’ll travel to $campus from an approved pickup point."
}

/** Straight-line distance exactly as the legacy picker showed it: "~0.6 km" (one decimal, device locale). */
internal fun hubDistanceLabel(distanceMeters: Double, locale: Locale = Locale.getDefault()): String =
    "~${String.format(locale, "%.1f", distanceMeters / 1000)} km"

internal fun routeKmLabel(distanceMeters: Double, locale: Locale = Locale.getDefault()): String =
    String.format(locale, "%.1f km", distanceMeters / 1000)

internal fun routeMinutesLabel(durationSeconds: Long): String = "${durationSeconds / 60} min"

/**
 * The distance and time come from the route between the two endpoints only (source behaviour); stops
 * are never routed. Whenever stops exist the label says so instead of implying a total.
 */
internal fun routeRecapDetail(distanceMeters: Double, durationSeconds: Long, stopCount: Int, locale: Locale = Locale.getDefault()): String =
    if (stopCount > 0) "Direct route: ${routeKmLabel(distanceMeters, locale)} · ${routeMinutesLabel(durationSeconds)} (stops not included)"
    else "${routeKmLabel(distanceMeters, locale)} · ${routeMinutesLabel(durationSeconds)} drive"

internal const val DIRECT_ROUTE_NOTE = "Distance and time are for the direct route and don’t include your stops."

/** "Mon, Wed for 4 weeks" for the Repeat step and review; null when nothing repeats. */
internal fun repeatSummary(days: Set<DayOfWeek>, weeks: Int): String? =
    if (days.isEmpty()) null else "${dayListLabel(days)} for ${weeksLabel(weeks)}"

internal fun seatsNeededLabel(seats: Int): String = "$seats ${if (seats == 1) "seat" else "seats"} needed"

internal fun seatsOfferedLabel(seats: Int): String = "$seats passenger ${if (seats == 1) "seat" else "seats"} offered"

/** The Offer stepper's maximum is the registered vehicle's capacity minus the driver. */
internal fun vehicleSeatsSupport(maxPassengerSeats: Int): String {
    val total = maxPassengerSeats + 1
    return if (maxPassengerSeats == 1)
        "Your vehicle seats $total including you, so you can offer 1 passenger seat."
    else "Your registered vehicle seats $total including you, so you can offer up to $maxPassengerSeats passenger seats."
}

/** Why Next is disabled on the When step, shown beside the button instead of leaving it silently grey. */
internal fun whenHint(hasDate: Boolean, hasTime: Boolean, tooSoon: Boolean): String = when {
    !hasDate && !hasTime -> "Choose a date and time to continue."
    !hasDate -> "Choose a date to continue."
    !hasTime -> "Choose a time to continue."
    tooSoon -> "Choose a time at least $MIN_BOOKING_LEAD_MINUTES minutes from now to continue."
    else -> ""
}
