package com.example.ds04_monash_rideshare_application.core.state

import com.example.ds04_monash_rideshare_application.Repository.Remote.ScheduleWarning
import com.example.ds04_monash_rideshare_application.Repository.Remote.WarningSubject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

// A schedule WARNING is not a status change and not an error: the person's next ride is still booked, but the ride before it
// finished later than planned and they may not make it. This turns the backend's structured warning (epoch milliseconds,
// seconds, point names) into a short sentence in the viewer's own time zone; the app never parses backend prose and never
// shows a backend code.
const val SCHEDULE_WARNING_TITLE = "Schedule warning"

fun scheduleWarningMessage(
    warning: ScheduleWarning,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault()
): String {
    val clock = DateTimeFormatter.ofPattern("h:mm a", locale)
    val dayAndClock = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", locale)
    val nextDay = Instant.ofEpochMilli(warning.nextDepartureTime).atZone(zone).toLocalDate()
    // Just the time when it is the same day as the next ride; the day too when it is not.
    fun at(millis: Long): String {
        val moment = Instant.ofEpochMilli(millis).atZone(zone)
        return (if (moment.toLocalDate() == nextDay) clock else dayAndClock).format(moment)
    }
    fun minutes(seconds: Long) = ceil(seconds / 60.0).toLong().coerceAtLeast(1)
    fun minutesText(count: Long) = if (count == 1L) "1 minute" else "$count minutes"

    val buffer = minutes(warning.transferBufferSeconds)
    val travel = if (warning.repositioningSeconds > 0) {
        val from = warning.fromPointName ?: "where it ended"
        val to = warning.toPointName ?: "your next pickup"
        "Getting from $from to $to takes about ${minutesText(minutes(warning.repositioningSeconds))}, plus a $buffer-minute transfer buffer."
    } else {
        "A $buffer-minute transfer buffer applies."
    }
    val stillOn = if (warning.subjectKind == WarningSubject.RIDE) "Your ride is still scheduled." else "Your booking is still confirmed."
    return "Your previous ride finished at ${at(warning.previousCompletedAt)}. " +
        "You may not have enough time to reach your next ride at ${at(warning.nextDepartureTime)}. " +
        "$travel The earliest you could be there is ${at(warning.earliestFeasibleDeparture)}. $stillOn"
}

/**
 * What Ride details shows: the driver sees the warning about their OWN ride; anyone else sees only the warning about their own
 * seat request on it (null if they have none). Warnings arrive already scoped to the signed-in user by the backend; this keeps
 * the screen from ever looking one up under anyone else's identity.
 */
fun rideDetailScheduleWarning(
    warnings: List<ScheduleWarning>?,
    rideId: String?,
    rideDriverId: String?,
    userId: String?,
    myRequestIdForRide: String?,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault()
): String? =
    if (userId != null && rideDriverId == userId) scheduleWarningFor(warnings, WarningSubject.RIDE, rideId, zone, locale)
    else scheduleWarningFor(warnings, WarningSubject.REQUEST, myRequestIdForRide, zone, locale)

/** The message for one request or ride the signed-in person has a warning about; null when there is none. */
fun scheduleWarningFor(
    warnings: List<ScheduleWarning>?,
    subjectKind: String,
    subjectId: String?,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault()
): String? {
    if (subjectId == null) return null
    // Several can exist for one journey (two earlier rides); the latest finding is the one that is current.
    val warning = warnings.orEmpty()
        .filter { it.subjectKind == subjectKind && it.subjectId == subjectId }
        .maxByOrNull { it.createdAt } ?: return null
    return scheduleWarningMessage(warning, zone, locale)
}
