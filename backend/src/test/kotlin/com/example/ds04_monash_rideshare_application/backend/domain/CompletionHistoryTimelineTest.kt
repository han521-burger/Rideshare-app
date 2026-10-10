package com.example.ds04_monash_rideshare_application.backend.domain

import com.example.ds04_monash_rideshare_application.backend.CONFLICT_TOO_LATE_FOR_NEXT
import com.example.ds04_monash_rideshare_application.backend.CONFLICT_TOO_SOON_AFTER_PREVIOUS
import com.example.ds04_monash_rideshare_application.backend.Journey
import com.example.ds04_monash_rideshare_application.backend.JourneyKind
import com.example.ds04_monash_rideshare_application.backend.JourneyState
import com.example.ds04_monash_rideshare_application.backend.Place
import com.example.ds04_monash_rideshare_application.backend.ScenarioTravelTimes
import com.example.ds04_monash_rideshare_application.backend.TravelTimeProvider
import com.example.ds04_monash_rideshare_application.backend.buildTimelineNodes
import com.example.ds04_monash_rideshare_application.backend.committed
import com.example.ds04_monash_rideshare_application.backend.committedJourneysAtRisk
import com.example.ds04_monash_rideshare_application.backend.findScheduleConflict
import com.example.ds04_monash_rideshare_application.backend.pendingJourneysToInvalidate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// How a COMPLETED journey acts as history in the timeline, and how the warning rule reads it. Pure: minutes after a base
// time, no database.
class CompletionHistoryTimelineTest {
    // Clayton B -> Caulfield D 20 min, D -> B 35 min (asymmetric on purpose); Huntingdale A -> D 15; everything else 20.
    private val matrix: TravelTimeProvider = ScenarioTravelTimes(
        20, Triple(Place.B, Place.D, 20L), Triple(Place.D, Place.B, 35L), Triple(Place.A, Place.D, 15L)
    )
    private fun m(n: Long) = n * 60_000L
    private var counter = 0

    private fun journey(
        from: String, to: String, depart: Long, arrive: Long, state: JourneyState = JourneyState.PENDING,
        kind: JourneyKind = JourneyKind.OPEN_REQUEST, unverified: Boolean = false
    ) = Journey("j${++counter}", kind, state, if (unverified) null else from, if (unverified) null else to, m(depart), m(arrive), unverified)

    // A finished journey: it started at `depart` and ENDED at `arrive` (the effective arrival).
    private fun completed(from: String, to: String, depart: Long, arrive: Long) =
        journey(from, to, depart, arrive, JourneyState.COMPLETED, JourneyKind.RIDE)

    private fun conflict(existing: List<Journey>, proposed: Journey) =
        findScheduleConflict(buildTimelineNodes(existing), proposed, matrix)

    // ---------------------------------------------------------------- history is a "previous" journey like any other
    @Test fun aCompletedJourneyConstrainsWhatComesAfterItByTheNormalInequality() {
        val history = completed(Place.B, Place.D, 0, 50)                 // ended at Caulfield at 50
        val tooSoon = conflict(listOf(history), journey(Place.D, Place.B, 59, 94))!!
        assertEquals(CONFLICT_TOO_SOON_AFTER_PREVIOUS, tooSoon.type)
        assertEquals(m(60), tooSoon.earliestFeasibleDeparture)           // 50 + 0 (same place) + 10
        assertEquals(m(50), tooSoon.conflictingArrivalTime)
        assertNull(conflict(listOf(history), journey(Place.D, Place.B, 60, 95)))
    }

    @Test fun theActualArrivalIsWhatCountsNotWhatWasPlanned() {
        val plannedTo30 = completed(Place.B, Place.D, 0, 30)
        val lateTo50 = completed(Place.B, Place.D, 0, 50)
        val proposed = journey(Place.D, Place.B, 45, 80)
        assertNull(conflict(listOf(plannedTo30), proposed))               // 30 + 10 = 40 <= 45
        assertNotNull(conflict(listOf(lateTo50), proposed))               // 50 + 10 = 60 > 45
    }

    @Test fun repositioningFromTheCompletedDestinationIsDirectional() {
        val endedAtD = completed(Place.B, Place.D, 0, 40)
        // From Clayton the way back is D -> B = 35: 40 + 35 + 10 = 85.
        assertEquals(m(85), conflict(listOf(endedAtD), journey(Place.B, Place.A, 84, 104))!!.earliestFeasibleDeparture)
        assertNull(conflict(listOf(endedAtD), journey(Place.B, Place.A, 85, 105)))
        val endedAtB = completed(Place.D, Place.B, 0, 40)
        // Ending at Clayton and leaving from Caulfield is B -> D = 20: 40 + 20 + 10 = 70 - a different answer for the mirror trip.
        assertEquals(m(70), conflict(listOf(endedAtB), journey(Place.D, Place.A, 69, 89))!!.earliestFeasibleDeparture)
        assertNull(conflict(listOf(endedAtB), journey(Place.D, Place.A, 70, 90)))
    }

    @Test fun anOldCompletedJourneyBlocksNothing() {
        val old = completed(Place.B, Place.D, -2000, -1950)
        assertNull(conflict(listOf(old), journey(Place.B, Place.A, 0, 20)))
        assertNull(conflict(listOf(old), journey(Place.D, Place.B, 0, 35)))
    }

    @Test fun historyIsNeverTheJourneyAfter() {
        // A proposed journey that starts before a completed one started is not refused on account of it.
        val history = completed(Place.B, Place.D, 100, 140)
        assertNull(conflict(listOf(history), journey(Place.A, Place.D, 0, 15)))
        // ... but a real later journey still counts: history is skipped, the next one that has not happened is checked.
        val later = journey(Place.A, Place.B, 200, 230, JourneyState.ACCEPTED)
        val c = conflict(listOf(history, later), journey(Place.A, Place.D, 190, 205))!!
        assertEquals(CONFLICT_TOO_LATE_FOR_NEXT, c.type)
    }

    @Test fun aCommittedJourneyThatLeftBeforeHistoryFinishedDoesNotHideWhereThePersonProvablyWas() {
        // The person's ride ended at Caulfield at 115 (it ran very late). A later accepted ride Y was due to leave Huntingdale at 60:
        // it is warned about, not cancelled, so it stays on the timeline - and it is now the node just before a journey at 120.
        val history = completed(Place.B, Place.D, 0, 115)
        val y = journey(Place.A, Place.B, 60, 70, JourneyState.ACCEPTED, JourneyKind.SEAT_REQUEST)
        // Judged only against Y (arrives Clayton 70, back to Caulfield 20 + 10) it would fit; but at 115 they were at Caulfield.
        val tooSoon = conflict(listOf(history, y), journey(Place.D, Place.B, 120, 155))!!
        assertEquals(CONFLICT_TOO_SOON_AFTER_PREVIOUS, tooSoon.type)
        assertEquals(history.id, tooSoon.conflictingId)
        assertEquals(m(125), tooSoon.earliestFeasibleDeparture)
        assertNull(conflict(listOf(history, y), journey(Place.D, Place.B, 125, 160)))
        // A journey that leaves AFTER history finished is the ordinary case: it is the node before, and nothing extra is checked.
        val after = journey(Place.D, Place.B, 130, 165, JourneyState.ACCEPTED)
        assertNull(conflict(listOf(history, after), journey(Place.B, Place.A, 200, 215)))
    }

    @Test fun completedHistoryIsCommittedSoItIsNeverInvalidated() {
        val history = completed(Place.B, Place.D, 0, 50)
        assertTrue(listOf(history).committed().contains(history))
        val pending = journey(Place.D, Place.B, 45, 80)
        val invalidated = pendingJourneysToInvalidate(listOf(history, pending), matrix)
        assertEquals(listOf(pending.id), invalidated.map { it.journey.id })
        assertEquals(m(60), invalidated.single().conflict.earliestFeasibleDeparture)
    }

    // ---------------------------------------------------------------- the warning rule
    private fun atRisk(completed: Journey, vararg journeys: Journey) = committedJourneysAtRisk(completed, journeys.toList(), matrix)

    @Test fun anAcceptedJourneyThatCannotBeReachedIsAtRisk() {
        val history = completed(Place.B, Place.D, 0, 50)
        val tight = journey(Place.D, Place.B, 59, 94, JourneyState.ACCEPTED, JourneyKind.SEAT_REQUEST)
        val ok = journey(Place.D, Place.B, 60, 95, JourneyState.ACCEPTED, JourneyKind.SEAT_REQUEST)
        val risky = atRisk(history, tight, ok)
        assertEquals(listOf(tight.id), risky.map { it.journey.id })
        assertEquals(m(60), risky.single().earliestFeasibleDeparture)
        assertEquals(0L, risky.single().repositioningSeconds)
    }

    @Test fun aDrivenRideIsCoveredToo() {
        val history = completed(Place.B, Place.D, 0, 50)
        val ride = journey(Place.D, Place.B, 55, 90, JourneyState.DRIVING, JourneyKind.RIDE)
        assertEquals(listOf(ride.id), atRisk(history, ride).map { it.journey.id })
    }

    @Test fun theWarningUsesTheDirectionalRepositioning() {
        val history = completed(Place.B, Place.D, 0, 40)                 // ends at Caulfield
        // To Clayton: D -> B = 35, buffer 10: needs 85.
        val toClayton = journey(Place.B, Place.A, 84, 104, JourneyState.ACCEPTED)
        val risk = atRisk(history, toClayton).single()
        assertEquals(35 * 60L, risk.repositioningSeconds)
        assertEquals(m(85), risk.earliestFeasibleDeparture)
        assertTrue(atRisk(history, journey(Place.B, Place.A, 85, 105, JourneyState.ACCEPTED)).isEmpty())
    }

    @Test fun pendingJourneysAreNotWarnedAbout() {
        val history = completed(Place.B, Place.D, 0, 50)
        assertTrue(atRisk(history, journey(Place.D, Place.B, 45, 80, JourneyState.PENDING)).isEmpty())
    }

    @Test fun onlyJourneysAfterTheCompletedOneStartedAreConsidered() {
        val history = completed(Place.B, Place.D, 100, 150)
        assertTrue(atRisk(history, journey(Place.D, Place.B, 90, 125, JourneyState.ACCEPTED)).isEmpty())
    }

    @Test fun unplaceableAndUnknownPairsAreNotJudged() {
        val history = completed(Place.B, Place.D, 0, 50)
        assertTrue(atRisk(history, journey(Place.D, Place.B, 55, 90, JourneyState.ACCEPTED, unverified = true)).isEmpty())
        val sparse = object : TravelTimeProvider { override fun travelSeconds(fromPointId: String, toPointId: String): Long? = null }
        val tight = journey(Place.A, Place.B, 55, 90, JourneyState.ACCEPTED)
        assertTrue(committedJourneysAtRisk(history, listOf(tight), sparse).isEmpty())
    }

    @Test fun severalAtRiskJourneysAreReportedInDepartureOrderThenId() {
        val history = completed(Place.B, Place.D, 0, 50)
        val late = journey(Place.D, Place.B, 58, 93, JourneyState.ACCEPTED)
        val early = journey(Place.D, Place.B, 52, 87, JourneyState.ACCEPTED)
        assertEquals(listOf(early.id, late.id), atRisk(history, late, early).map { it.journey.id })
    }
}
