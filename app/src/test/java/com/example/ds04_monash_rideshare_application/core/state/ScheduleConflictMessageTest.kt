package com.example.ds04_monash_rideshare_application.core.state

import com.example.ds04_monash_rideshare_application.Repository.Remote.BackendException
import com.example.ds04_monash_rideshare_application.Repository.Remote.ScheduleConflict
import com.example.ds04_monash_rideshare_application.Repository.Remote.SCHEDULE_INFEASIBLE
import com.example.ds04_monash_rideshare_application.feature.rides.requestSubmitErrorTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

// The backend sends structured values (point names, epoch milliseconds, seconds); the app turns them into a short
// sentence in the viewer's own time zone. Melbourne and English are fixed here so the expected text is exact.
class ScheduleConflictMessageTest {
    private val melbourne = ZoneId.of("Australia/Melbourne")
    private fun ms(day: Int, hour: Int, minute: Int) =
        LocalDateTime.of(2027, 5, day, hour, minute).atZone(melbourne).toInstant().toEpochMilli()
    private fun message(conflict: ScheduleConflict) = scheduleConflictMessage(conflict, melbourne, Locale.ENGLISH)

    private val tooSoon = ScheduleConflict(
        type = SCHEDULE_CONFLICT_TOO_SOON_AFTER_PREVIOUS, conflictingId = "req-1", conflictingKind = "OPEN_REQUEST",
        conflictingDepartureTime = ms(10, 14, 0), conflictingArrivalTime = ms(10, 14, 30),
        fromPointId = "monash-clayton", fromPointName = "Monash Clayton", toPointId = "clayton-station", toPointName = "Clayton Station",
        repositioningSeconds = 20 * 60, transferBufferSeconds = 10 * 60, earliestFeasibleDeparture = ms(10, 15, 0),
        proposedDepartureTime = ms(10, 14, 45), proposedArrivalTime = ms(10, 15, 15)
    )

    @Test fun theTooSoonMessageReadsLikeTheProductExample() {
        assertEquals(
            "You already have a ride ending at Monash Clayton at 2:30 PM. You need about 20 minutes to reach Clayton Station " +
                "plus a 10-minute transfer buffer. Earliest available departure: 3:00 PM.",
            message(tooSoon)
        )
    }

    @Test fun whenNoTravelIsNeededOnlyTheBufferIsMentioned() {
        val text = message(tooSoon.copy(repositioningSeconds = 0, toPointName = "Monash Clayton", earliestFeasibleDeparture = ms(10, 14, 40)))
        assertEquals(
            "You already have a ride ending at Monash Clayton at 2:30 PM. A 10-minute transfer buffer applies. Earliest available departure: 2:40 PM.",
            text
        )
    }

    @Test fun partMinutesRoundUpSoThePassengerIsNeverTold1MinuteTooFew() {
        assertTrue(message(tooSoon.copy(repositioningSeconds = 20 * 60 + 1)).contains("about 21 minutes"))
        assertTrue(message(tooSoon.copy(repositioningSeconds = 30)).contains("about 1 minute to reach"))
    }

    @Test fun aRideTheyAreDrivingIsDescribedAsSuch() {
        assertTrue(message(tooSoon.copy(conflictingKind = "RIDE")).startsWith("You already have a ride you're driving ending at"))
    }

    @Test fun theTooLateForNextMessageSaysWhenThisRideMustLeaveByInstead() {
        val text = message(
            ScheduleConflict(
                type = SCHEDULE_CONFLICT_TOO_LATE_FOR_NEXT, conflictingId = "req-2", conflictingKind = "OPEN_REQUEST",
                conflictingDepartureTime = ms(10, 16, 0), conflictingArrivalTime = ms(10, 16, 30),
                fromPointName = "Monash Clayton", toPointName = "Clayton Station", repositioningSeconds = 20 * 60, transferBufferSeconds = 600,
                latestFeasibleDeparture = ms(10, 15, 0), proposedDepartureTime = ms(10, 15, 30), proposedArrivalTime = ms(10, 16, 0)
            )
        )
        assertEquals(
            "You have a ride leaving Clayton Station at 4:00 PM. This ride would end at Monash Clayton at 4:00 PM. " +
                "You need about 20 minutes to reach Clayton Station plus a 10-minute transfer buffer. Latest departure for this ride: 3:00 PM.",
            text
        )
    }

    @Test fun anOldRecordThatCannotBePlacedIsExplainedByTimeAlone() {
        val text = message(
            ScheduleConflict(
                type = SCHEDULE_CONFLICT_OVERLAPS_UNVERIFIED_LEGACY, conflictingId = "old", conflictingKind = "OPEN_REQUEST",
                conflictingDepartureTime = ms(10, 9, 15), conflictingArrivalTime = ms(10, 9, 25), transferBufferSeconds = 600,
                proposedDepartureTime = ms(10, 9, 30), proposedArrivalTime = ms(10, 10, 0)
            )
        )
        assertEquals("This overlaps another ride you have around 9:15 AM.", text)
    }

    @Test fun aTimeOnAnotherDayShowsTheDay() {
        val nextDay = tooSoon.copy(earliestFeasibleDeparture = ms(11, 0, 10))
        val text = message(nextDay)
        assertTrue(text, text.contains("Earliest available departure: Tue 11 May, 12:10 AM."))
        assertFalse("same-day times stay short", message(tooSoon).contains("May"))
    }

    @Test fun timesAreShownInTheViewersZoneNotTheServers() {
        val sydneyIsTheSameButPerthIsTwoHoursBehind = scheduleConflictMessage(tooSoon, ZoneId.of("Australia/Perth"), Locale.ENGLISH)
        assertTrue(sydneyIsTheSameButPerthIsTwoHoursBehind, sydneyIsTheSameButPerthIsTwoHoursBehind.contains("12:30 PM"))
    }

    @Test fun missingNamesFallBackToPlainWords() {
        val text = message(tooSoon.copy(fromPointName = null, toPointName = null))
        assertTrue(text, text.contains("your drop-off point") && text.contains("your pickup point"))
    }

    // ---- how the request screens use it ----
    @Test fun aScheduleConflictBecomesItsOwnKindOfErrorNeitherDuplicateNorGeneric() {
        val error = BackendException("This doesn't fit your schedule", SCHEDULE_INFEASIBLE, scheduleConflict = tooSoon).toRequestSubmitError("fallback")
        assertTrue(error.isScheduleConflict)
        assertFalse(error.isDuplicate)
        assertTrue(error.message.startsWith("You already have a ride ending at Monash Clayton"))
        assertEquals("Doesn’t fit your schedule", requestSubmitErrorTitle(error, "Request not sent"))
    }

    @Test fun aScheduleCodeWithoutDetailsStillShowsTheBackendsMessageRatherThanNothing() {
        val error = BackendException("This doesn't fit your schedule", SCHEDULE_INFEASIBLE).toRequestSubmitError("fallback")
        assertFalse(error.isScheduleConflict)
        assertEquals("This doesn't fit your schedule", error.message)
    }
}
