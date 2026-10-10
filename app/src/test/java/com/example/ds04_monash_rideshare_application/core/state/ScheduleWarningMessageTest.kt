package com.example.ds04_monash_rideshare_application.core.state

import com.example.ds04_monash_rideshare_application.Repository.Remote.ScheduleWarning
import com.example.ds04_monash_rideshare_application.Repository.Remote.WarningSubject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

// A schedule warning is information, not an error and not a status: the next ride is still booked, the one before it just
// finished later than planned. The backend sends structured values; the app writes the sentence, in the viewer's own time zone.
class ScheduleWarningMessageTest {
    private val melbourne = ZoneId.of("Australia/Melbourne")
    private fun ms(day: Int, hour: Int, minute: Int) =
        LocalDateTime.of(2027, 5, day, hour, minute).atZone(melbourne).toInstant().toEpochMilli()
    private fun message(w: ScheduleWarning) = scheduleWarningMessage(w, melbourne, Locale.ENGLISH)

    // Finished at 2:50 PM; next ride leaves the same place at 3:00 PM; 10-minute buffer => earliest 3:00 PM... here 3:10 PM.
    private val sameStation = ScheduleWarning(
        warningId = "w1", type = "PREVIOUS_RIDE_FINISHED_LATE", subjectKind = WarningSubject.REQUEST, subjectId = "req-1",
        createdAt = ms(10, 14, 50), previousRideId = "ride-a", previousCompletedAt = ms(10, 14, 50),
        previousPlannedArrivalTime = ms(10, 14, 30), fromPointName = "Monash Caulfield", toPointName = "Monash Caulfield",
        repositioningSeconds = 0, transferBufferSeconds = 600, earliestFeasibleDeparture = ms(10, 15, 0), nextDepartureTime = ms(10, 14, 55)
    )

    @Test fun theMessageReadsLikeTheProductExample() {
        assertEquals(
            "Your previous ride finished at 2:50 PM. You may not have enough time to reach your next ride at 2:55 PM. " +
                "A 10-minute transfer buffer applies. The earliest you could be there is 3:00 PM. Your booking is still confirmed.",
            message(sameStation)
        )
    }

    @Test fun travelBetweenDifferentPlacesIsExplained() {
        val text = message(sameStation.copy(toPointName = "Monash Clayton", repositioningSeconds = 20 * 60 + 1, earliestFeasibleDeparture = ms(10, 15, 21)))
        assertTrue(text, text.contains("Getting from Monash Caulfield to Monash Clayton takes about 21 minutes, plus a 10-minute transfer buffer."))
        assertTrue(text, text.contains("The earliest you could be there is 3:21 PM."))
        assertFalse(text, text.contains("A 10-minute transfer buffer applies."))
    }

    @Test fun aRideTheUserDrivesSaysItIsStillScheduled() {
        val text = message(sameStation.copy(subjectKind = WarningSubject.RIDE))
        assertTrue(text, text.endsWith("Your ride is still scheduled."))
        assertFalse(text, text.contains("booking"))
    }

    @Test fun theDayIsShownWhenTheNextRideIsOnADifferentDay() {
        val text = message(sameStation.copy(previousCompletedAt = ms(10, 23, 50), nextDepartureTime = ms(11, 0, 5), earliestFeasibleDeparture = ms(11, 0, 0)))
        assertTrue(text, text.contains("finished at Mon 10 May, 11:50 PM"))
        assertTrue(text, text.contains("next ride at 12:05 AM"))
    }

    @Test fun neverShowsABackendCodeOrRawIdentifier() {
        val text = message(sameStation)
        for (raw in listOf("PREVIOUS_RIDE_FINISHED_LATE", "REQUEST", "req-1", "ride-a", "TOO_SOON", "warning-")) {
            assertFalse("$raw leaked: $text", text.contains(raw))
        }
        assertEquals("Schedule warning", SCHEDULE_WARNING_TITLE)
    }

    @Test fun missingPointNamesStillReadWell() {
        val text = message(sameStation.copy(fromPointName = null, toPointName = null, repositioningSeconds = 600))
        assertTrue(text, text.contains("Getting from where it ended to your next pickup takes about 10 minutes"))
    }

    // ---------------------------------------------------------------- finding the warning for a request or ride
    @Test fun aWarningIsFoundByKindAndId() {
        val ride = sameStation.copy(warningId = "w2", subjectKind = WarningSubject.RIDE, subjectId = "ride-9")
        val all = listOf(sameStation, ride)
        assertEquals(message(sameStation), scheduleWarningFor(all, WarningSubject.REQUEST, "req-1", melbourne, Locale.ENGLISH))
        assertEquals(message(ride), scheduleWarningFor(all, WarningSubject.RIDE, "ride-9", melbourne, Locale.ENGLISH))
        // The same id under the other kind is a different thing.
        assertNull(scheduleWarningFor(all, WarningSubject.RIDE, "req-1", melbourne, Locale.ENGLISH))
        assertNull(scheduleWarningFor(all, WarningSubject.REQUEST, "ride-9", melbourne, Locale.ENGLISH))
    }

    @Test fun noWarningsOrNoIdMeansNoMessage() {
        assertNull(scheduleWarningFor(null, WarningSubject.REQUEST, "req-1"))
        assertNull(scheduleWarningFor(emptyList(), WarningSubject.REQUEST, "req-1"))
        assertNull(scheduleWarningFor(listOf(sameStation), WarningSubject.REQUEST, null))
        assertNull(scheduleWarningFor(listOf(sameStation), WarningSubject.REQUEST, "req-2"))
    }

    @Test fun theLatestFindingWinsWhenAJourneyHasSeveral() {
        val older = sameStation.copy(warningId = "old", createdAt = ms(10, 14, 0), previousCompletedAt = ms(10, 14, 40))
        val newer = sameStation.copy(warningId = "new", createdAt = ms(10, 14, 50))
        assertEquals(message(newer), scheduleWarningFor(listOf(older, newer), WarningSubject.REQUEST, "req-1", melbourne, Locale.ENGLISH))
        assertEquals(message(newer), scheduleWarningFor(listOf(newer, older), WarningSubject.REQUEST, "req-1", melbourne, Locale.ENGLISH))
    }
}
