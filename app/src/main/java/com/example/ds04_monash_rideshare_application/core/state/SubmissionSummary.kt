package com.example.ds04_monash_rideshare_application.core.state

import com.example.ds04_monash_rideshare_application.Repository.Remote.DUPLICATE_OPEN_REQUEST
import com.example.ds04_monash_rideshare_application.Repository.Remote.OccurrenceOutcome
import com.example.ds04_monash_rideshare_application.Repository.Remote.RequestSubmissionResult
import com.example.ds04_monash_rideshare_application.Repository.Remote.SCHEDULE_INFEASIBLE
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// A repeating request (or a seat request across a recurring group) is several requests in one go, and some may not be
// possible. This turns what each date came to into a short summary for the passenger - never a backend code - e.g.
//
//   3 of 4 ride requests created.
//   Mon 5 May, 8:00 AM - Created
//   Mon 12 May, 8:00 AM - Doesn't fit your schedule
//   Mon 19 May, 8:00 AM - Already requested
//   Mon 26 May, 8:00 AM - Created
//
// A one-off request has no summary: it is a plain success, or the ordinary error.
data class SubmissionSummary(val title: String, val lines: List<String>)

internal const val CODE_TRAVEL_TIME_UNAVAILABLE = "TRAVEL_TIME_UNAVAILABLE"
internal const val CODE_RIDE_FULL = "RIDE_FULL"
internal const val CODE_RIDE_UNAVAILABLE = "RIDE_UNAVAILABLE"
internal const val CODE_OWN_RIDE = "OWN_RIDE"

fun summaryTitle(requested: Int, created: Int): String = when {
    created <= 0 -> "None of the $requested requests could be created."
    created >= requested -> "All $requested ride requests created."
    else -> "$created of $requested ride requests created."
}

// Why one date was not made, in words. Anything unrecognised (a newer backend) still reads as a plain sentence.
fun occurrenceStatusText(outcome: OccurrenceOutcome): String = if (outcome.created) "Created" else when (outcome.code) {
    DUPLICATE_OPEN_REQUEST -> "Already requested"
    SCHEDULE_INFEASIBLE -> "Doesn’t fit your schedule"
    CODE_TRAVEL_TIME_UNAVAILABLE -> "Couldn’t check your schedule"
    CODE_RIDE_FULL -> "Ride is full"
    CODE_RIDE_UNAVAILABLE -> "Ride is no longer open"
    CODE_OWN_RIDE -> "Your own ride"
    else -> "Couldn’t be requested"
}

// The request a one-off submission made, to open straight after it. A batch has several (and a summary of them),
// so it has none; the occurrence list is preferred, the request list covers a backend that leaves it out.
fun createdOneOffRequestId(result: RequestSubmissionResult): String? =
    if (result.isBatch || result.created == 0) null
    else result.occurrences.firstOrNull { it.created }?.requestId ?: result.requests.firstOrNull()?.requestId

fun summariseSubmission(
    result: RequestSubmissionResult,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault()
): SubmissionSummary {
    val format = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", locale)
    return SubmissionSummary(
        title = summaryTitle(result.requested, result.created),
        lines = result.occurrences.map { "${format.format(Instant.ofEpochMilli(it.departureTime).atZone(zone))} – ${occurrenceStatusText(it)}" }
    )
}
