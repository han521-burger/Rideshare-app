package com.example.ds04_monash_rideshare_application.core.state

import com.example.ds04_monash_rideshare_application.Repository.Remote.ScheduleConflict
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

// Turns the backend's structured "this doesn't fit your schedule" answer into a short sentence. The backend
// sends point names, epoch milliseconds and seconds; this is where they become local clock times and minutes,
// so the app never parses backend prose and always shows times in the viewer's own time zone.
const val SCHEDULE_CONFLICT_TOO_SOON_AFTER_PREVIOUS = "TOO_SOON_AFTER_PREVIOUS"
const val SCHEDULE_CONFLICT_TOO_LATE_FOR_NEXT = "TOO_LATE_FOR_NEXT"
const val SCHEDULE_CONFLICT_OVERLAPS_UNVERIFIED_LEGACY = "OVERLAPS_UNVERIFIED_LEGACY"

fun scheduleConflictMessage(
    conflict: ScheduleConflict,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault()
): String {
    val clock = DateTimeFormatter.ofPattern("h:mm a", locale)
    val dayAndClock = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", locale)
    val proposedDay = Instant.ofEpochMilli(conflict.proposedDepartureTime).atZone(zone).toLocalDate()
    // Just the time when it is the same day as the journey being requested; the day too when it is not.
    fun at(millis: Long): String {
        val moment = Instant.ofEpochMilli(millis).atZone(zone)
        return (if (moment.toLocalDate() == proposedDay) clock else dayAndClock).format(moment)
    }
    fun minutes(seconds: Long) = ceil(seconds / 60.0).toLong().coerceAtLeast(1)
    fun minutesText(count: Long) = if (count == 1L) "1 minute" else "$count minutes"

    val bufferMinutes = minutes(conflict.transferBufferSeconds)
    val reposition = conflict.repositioningSeconds ?: 0L
    val to = conflict.toPointName ?: "your pickup point"
    val from = conflict.fromPointName ?: "your drop-off point"
    val travel = if (reposition > 0) {
        "You need about ${minutesText(minutes(reposition))} to reach $to plus a $bufferMinutes-minute transfer buffer."
    } else {
        "A $bufferMinutes-minute transfer buffer applies."
    }
    val noun = if (conflict.conflictingKind == "RIDE") "a ride you're driving" else "a ride"

    return when (conflict.type) {
        SCHEDULE_CONFLICT_TOO_SOON_AFTER_PREVIOUS -> buildString {
            append("You already have $noun ending at $from at ${at(conflict.conflictingArrivalTime)}. ")
            append(travel)
            conflict.earliestFeasibleDeparture?.let { append(" Earliest available departure: ${at(it)}.") }
        }
        SCHEDULE_CONFLICT_TOO_LATE_FOR_NEXT -> buildString {
            append("You have $noun leaving $to at ${at(conflict.conflictingDepartureTime)}. ")
            append("This ride would end at $from at ${at(conflict.proposedArrivalTime)}. ")
            append(travel)
            conflict.latestFeasibleDeparture?.let { append(" Latest departure for this ride: ${at(it)}.") }
        }
        else -> "This overlaps another ride you have around ${at(conflict.conflictingDepartureTime)}."
    }
}
