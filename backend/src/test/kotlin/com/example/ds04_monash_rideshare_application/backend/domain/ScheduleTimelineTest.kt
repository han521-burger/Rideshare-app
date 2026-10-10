package com.example.ds04_monash_rideshare_application.backend.domain

import com.example.ds04_monash_rideshare_application.backend.CODE_TRAVEL_TIME_UNAVAILABLE
import com.example.ds04_monash_rideshare_application.backend.CONFLICT_OVERLAPS_UNVERIFIED_LEGACY
import com.example.ds04_monash_rideshare_application.backend.CONFLICT_TOO_LATE_FOR_NEXT
import com.example.ds04_monash_rideshare_application.backend.CONFLICT_TOO_SOON_AFTER_PREVIOUS
import com.example.ds04_monash_rideshare_application.backend.FakeTravelTimeProvider
import com.example.ds04_monash_rideshare_application.backend.Journey
import com.example.ds04_monash_rideshare_application.backend.JourneyKind
import com.example.ds04_monash_rideshare_application.backend.JourneyState
import com.example.ds04_monash_rideshare_application.backend.Place
import com.example.ds04_monash_rideshare_application.backend.ScenarioTravelTimes
import com.example.ds04_monash_rideshare_application.backend.ServiceUnavailableException
import com.example.ds04_monash_rideshare_application.backend.TravelTimeProvider
import com.example.ds04_monash_rideshare_application.backend.ValidationException
import com.example.ds04_monash_rideshare_application.backend.buildTimelineNodes
import com.example.ds04_monash_rideshare_application.backend.findScheduleConflict
import com.example.ds04_monash_rideshare_application.backend.pendingJourneysToInvalidate
import com.example.ds04_monash_rideshare_application.backend.requireIntentRouteConsistent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The timeline arithmetic on its own, in minutes after a base time, with no database and no HTTP.
class ScheduleTimelineTest {
    private val matrix: TravelTimeProvider = ScenarioTravelTimes(
        20, Triple(Place.A, Place.B, 30L), Triple(Place.B, Place.A, 30L), Triple(Place.B, Place.C, 20L), Triple(Place.C, Place.B, 50L)
    )
    private fun m(n: Long) = n * 60_000L
    private var counter = 0

    private fun journey(
        from: String, to: String, depart: Long, durationMinutes: Long = 30,
        kind: JourneyKind = JourneyKind.OPEN_REQUEST, state: JourneyState = JourneyState.PENDING, unverified: Boolean = false,
        intent: String? = null
    ) = Journey("j${++counter}", kind, state, if (unverified) null else from, if (unverified) null else to, m(depart), m(depart + durationMinutes), unverified, intent)

    // A pending seat request under a declared journey intent: the only kind of journey that can be an alternative.
    private fun seat(from: String, to: String, depart: Long, durationMinutes: Long = 30, intent: String? = "search-1") =
        journey(from, to, depart, durationMinutes, JourneyKind.SEAT_REQUEST, intent = intent)

    private fun conflict(existing: List<Journey>, proposed: Journey, provider: TravelTimeProvider = matrix) =
        findScheduleConflict(buildTimelineNodes(existing), proposed, provider)

    // ---------------------------------------------------------------- the two inequalities
    @Test fun anEmptyTimelineAcceptsAnything() = assertNull(conflict(emptyList(), journey(Place.A, Place.B, 0)))

    @Test fun previousArrivalPlusRepositioningPlusBufferMustNotExceedTheNewDeparture() {
        val previous = journey(Place.A, Place.B, 0)                 // arrives B at 30
        // B -> A is 30, buffer 10: earliest 70.
        assertEquals(m(70), conflict(listOf(previous), journey(Place.A, Place.B, 69))!!.earliestFeasibleDeparture)
        assertNull(conflict(listOf(previous), journey(Place.A, Place.B, 70)))
        assertNull(conflict(listOf(previous), journey(Place.A, Place.B, 71)))
    }

    @Test fun theNewArrivalPlusRepositioningPlusBufferMustNotExceedTheNextDeparture() {
        val next = journey(Place.C, Place.D, 120)
        // New A -> B (30): arrives B, B -> C is 20, buffer 10. Latest departure 120 - 10 - 20 - 30 = 60.
        val tooLate = conflict(listOf(next), journey(Place.A, Place.B, 61))!!
        assertEquals(CONFLICT_TOO_LATE_FOR_NEXT, tooLate.type)
        assertEquals(m(60), tooLate.latestFeasibleDeparture)
        assertNull(conflict(listOf(next), journey(Place.A, Place.B, 60)))
    }

    @Test fun theSamePlaceStillNeedsTheBufferButNoTravel() {
        val previous = journey(Place.A, Place.B, 0)                 // arrives B at 30
        val c = conflict(listOf(previous), journey(Place.B, Place.C, 39))!!
        assertEquals(0L, c.repositioningSeconds)
        assertEquals(m(40), c.earliestFeasibleDeparture)
        assertNull(conflict(listOf(previous), journey(Place.B, Place.C, 40)))
    }

    @Test fun repositioningIsDirectional() {
        val toC = journey(Place.D, Place.C, 0)                       // ends at C (D -> C defaults to 20 -> arrives 30 with explicit duration)
        // From C back to B takes 50, from B to C only 20.
        assertEquals(m(30 + 50 + 10), conflict(listOf(toC), journey(Place.B, Place.A, 60))!!.earliestFeasibleDeparture)
        val toB = journey(Place.D, Place.B, 0)
        assertEquals(m(30 + 20 + 10), conflict(listOf(toB), journey(Place.C, Place.D, 59))!!.earliestFeasibleDeparture, "B -> C is the shorter way")
    }

    @Test fun bothNeighboursAreCheckedAndThePreviousIsReportedFirst() {
        val previous = journey(Place.A, Place.B, 0)
        val next = journey(Place.C, Place.D, 200)
        // Fits between, with room both sides: prev 30 + 30 + 10 = 70 <= 70; new arrives 100, B -> C 20 + 10 = 130 <= 200.
        assertNull(conflict(listOf(previous, next), journey(Place.A, Place.B, 70)))
        // Too close to the previous only.
        assertEquals(CONFLICT_TOO_SOON_AFTER_PREVIOUS, conflict(listOf(previous, next), journey(Place.A, Place.B, 40))!!.type)
        // Too close to the next only: leaving 141 arrives 171, + 20 + 10 = 201 > 200 (leaving 140 just fits).
        assertEquals(CONFLICT_TOO_LATE_FOR_NEXT, conflict(listOf(previous, next), journey(Place.A, Place.B, 141))!!.type)
        assertNull(conflict(listOf(previous, next), journey(Place.A, Place.B, 140)))
    }

    @Test fun thereIsNoSameRouteCooldownOnlyPhysics() {
        val quick: TravelTimeProvider = ScenarioTravelTimes(20, Triple(Place.A, Place.B, 10L), Triple(Place.B, Place.A, 5L))
        val previous = journey(Place.A, Place.B, 0, durationMinutes = 10)
        assertNotNull(conflict(listOf(previous), journey(Place.A, Place.B, 24, durationMinutes = 10), quick))
        assertNull(conflict(listOf(previous), journey(Place.A, Place.B, 25, durationMinutes = 10), quick))
    }

    @Test fun onlyTheNeighboursMatterNotJourneysFurtherAlongTheTimeline() {
        val timeline = listOf(journey(Place.A, Place.B, 0), journey(Place.A, Place.B, 300), journey(Place.A, Place.B, 600))
        assertNull(conflict(timeline, journey(Place.A, Place.B, 150)))
    }

    @Test fun aMissingMatrixValueIsRefusedRecoverablyNeverTreatedAsZero() {
        val sparse = FakeTravelTimeProvider(mapOf((Place.A to Place.B) to 1800L))
        val error = assertFailsWith<ServiceUnavailableException> {
            conflict(listOf(journey(Place.A, Place.B, 0)), journey(Place.A, Place.B, 300), sparse) // needs B -> A
        }
        assertEquals(CODE_TRAVEL_TIME_UNAVAILABLE, error.code)
    }

    // ---------------------------------------------------------------- alternatives = explicit intent + same route
    @Test fun seatRequestsSharingAJourneyIntentAndRouteShareOneSlot() {
        val alternatives = listOf(seat(Place.A, Place.B, 0), seat(Place.A, Place.B, 5), seat(Place.A, Place.B, 10))
        val nodes = buildTimelineNodes(alternatives)
        assertEquals(1, nodes.size)
        assertEquals(m(0), nodes.single().departure); assertEquals(m(40), nodes.single().arrival, "earliest departure, latest arrival")
        // And one more alternative under the same intent is not a conflict.
        assertNull(conflict(alternatives, seat(Place.A, Place.B, 15)))
    }

    @Test fun theSameRouteWithADifferentIntentIsAnotherJourney() {
        val nodes = buildTimelineNodes(listOf(seat(Place.A, Place.B, 0, intent = "search-1"), seat(Place.A, Place.B, 5, intent = "search-2")))
        assertEquals(2, nodes.size)
        // ...and so the second must physically fit after the first: it does not.
        val first = seat(Place.A, Place.B, 0, intent = "search-1")
        assertEquals(CONFLICT_TOO_SOON_AFTER_PREVIOUS, conflict(listOf(first), seat(Place.A, Place.B, 5, intent = "search-2"))!!.type)
    }

    @Test fun theSameRouteWithNoIntentIsNeverGroupedHoweverCloseInTime() {
        // Within a minute of each other, same route, no declared intent: still two journeys.
        assertEquals(2, buildTimelineNodes(listOf(seat(Place.A, Place.B, 0, intent = null), seat(Place.A, Place.B, 1, intent = null))).size)
        assertEquals(2, buildTimelineNodes(listOf(seat(Place.A, Place.B, 0, intent = "search-1"), seat(Place.A, Place.B, 1, intent = null))).size)
        assertNotNull(conflict(listOf(seat(Place.A, Place.B, 0, intent = null)), seat(Place.A, Place.B, 5, intent = null)))
    }

    @Test fun twentyFiveMinutesApartIsDecidedByTheIntentNotTheClock() {
        // Quick trip: A -> B 10, B -> A 5, buffer 10: a repeat is physically possible after 25 minutes.
        val quick: TravelTimeProvider = ScenarioTravelTimes(20, Triple(Place.A, Place.B, 10L), Triple(Place.B, Place.A, 5L))
        val first = seat(Place.A, Place.B, 0, 10, intent = "search-1")
        assertNull(conflict(listOf(first), seat(Place.A, Place.B, 25, 10, intent = "search-2"), quick), "two real journeys 25 min apart both fit")
        assertEquals(2, buildTimelineNodes(listOf(first, seat(Place.A, Place.B, 25, 10, intent = "search-2"))).size)
        // Under ONE intent the same two requests are alternatives, so they share a slot rather than being sequenced.
        assertEquals(1, buildTimelineNodes(listOf(first, seat(Place.A, Place.B, 25, 10, intent = "search-1"))).size)
        // ...which is why 24 minutes apart is fine as alternatives but not as two journeys.
        assertNull(conflict(listOf(first), seat(Place.A, Place.B, 24, 10, intent = "search-1"), quick))
        assertNotNull(conflict(listOf(first), seat(Place.A, Place.B, 24, 10, intent = "search-2"), quick))
    }

    @Test fun timeDoesNotSplitAnIntentThereIsNoWindow() {
        // A search can return rides well apart; the intent alone says they are options for one journey.
        val nodes = buildTimelineNodes(listOf(seat(Place.A, Place.B, 0), seat(Place.A, Place.B, 80), seat(Place.A, Place.B, 160)))
        assertEquals(1, nodes.size)
        assertEquals(m(0), nodes.single().departure); assertEquals(m(190), nodes.single().arrival)
    }

    @Test fun aSharedIntentOnADifferentRouteIsNotMergedAndIsRefusedWhenCreated() {
        val onAB = seat(Place.A, Place.B, 0, intent = "search-1")
        val onAD = seat(Place.A, Place.D, 5, intent = "search-1")
        assertEquals(2, buildTimelineNodes(listOf(onAB, onAD)).size, "never merged into one slot")
        assertFailsWith<ValidationException> { requireIntentRouteConsistent(listOf(onAB), onAD) }
        // Reversed direction is a different route too.
        assertFailsWith<ValidationException> { requireIntentRouteConsistent(listOf(onAB), seat(Place.B, Place.A, 5, intent = "search-1")) }
        // The same route under the same intent is fine, and another intent may use any route.
        requireIntentRouteConsistent(listOf(onAB), seat(Place.A, Place.B, 9, intent = "search-1"))
        requireIntentRouteConsistent(listOf(onAB), seat(Place.A, Place.D, 5, intent = "search-2"))
        requireIntentRouteConsistent(listOf(onAB), seat(Place.A, Place.D, 5, intent = null))
    }

    @Test fun openRequestsAreNeverAlternativesOfAnythingEvenOnTheSameRouteAndTime() {
        assertEquals(2, buildTimelineNodes(listOf(journey(Place.A, Place.B, 0), journey(Place.A, Place.B, 5))).size)
        assertEquals(2, buildTimelineNodes(listOf(journey(Place.A, Place.B, 0), seat(Place.A, Place.B, 5))).size, "a seat request does not absorb an open one")
        assertEquals(3, buildTimelineNodes(listOf(journey(Place.A, Place.B, 0), seat(Place.A, Place.B, 20), journey(Place.A, Place.B, 40), seat(Place.A, Place.B, 41))).size)
    }

    @Test fun acceptedJourneysAndDrivenRidesAreNeverAlternatives() {
        assertEquals(2, buildTimelineNodes(listOf(journey(Place.A, Place.B, 0, kind = JourneyKind.SEAT_REQUEST, state = JourneyState.ACCEPTED, intent = "search-1"), seat(Place.A, Place.B, 5))).size)
        assertEquals(2, buildTimelineNodes(listOf(journey(Place.A, Place.B, 0, kind = JourneyKind.RIDE, state = JourneyState.DRIVING, intent = "search-1"), seat(Place.A, Place.B, 5))).size)
    }

    @Test fun aSlotIsCheckedAtItsLatestArrivalForTheJourneyAfterIt() {
        val alternatives = listOf(seat(Place.A, Place.B, 0, 30), seat(Place.A, Place.B, 10, 50)) // arrivals 30 and 60
        // From B the next pickup needs latest arrival 60 + 0 + 10 = 70.
        assertEquals(m(70), conflict(alternatives, journey(Place.B, Place.C, 69))!!.earliestFeasibleDeparture)
        assertNull(conflict(alternatives, journey(Place.B, Place.C, 70)))
    }

    @Test fun joiningASetCanStillBeRefusedIfItsEnvelopeNoLongerFitsTheNextJourney() {
        val alternatives = listOf(seat(Place.A, Place.B, 0, 30))
        val next = journey(Place.A, Place.B, 100)      // origin A; B -> A is 30 + buffer 10
        // A slow alternative arriving at 80 would leave 80 + 30 + 10 = 120 > 100.
        val slow = seat(Place.A, Place.B, 30, 50)
        assertEquals(CONFLICT_TOO_LATE_FOR_NEXT, conflict(alternatives + next, slow)!!.type)
    }

    // ---------------------------------------------------------------- legacy
    @Test fun anUnverifiedJourneyIsJudgedByTimeOverlapAlone() {
        val old = journey(Place.A, Place.B, 0, 10, unverified = true)
        val overlapping = conflict(listOf(old), journey(Place.C, Place.D, 20, 30))!!
        assertEquals(CONFLICT_OVERLAPS_UNVERIFIED_LEGACY, overlapping.type)
        assertNull(overlapping.earliestFeasibleDeparture)
        assertNull(conflict(listOf(old), journey(Place.C, Place.D, 200, 30)))
    }

    @Test fun anUnverifiedJourneyDoesNotBecomeANeighbourInTheLocationArithmetic() {
        val old = journey(Place.A, Place.B, 0, 10, unverified = true)
        val real = journey(Place.A, Place.B, 300)
        assertNull(conflict(listOf(old, real), journey(Place.A, Place.B, 150)))
    }

    @Test fun nodesAreOrderedByDepartureEvenIfGivenOutOfOrder() {
        val a = journey(Place.A, Place.B, 300); val b = journey(Place.A, Place.B, 0); val c = journey(Place.A, Place.B, 600)
        assertEquals(listOf(m(0), m(300), m(600)), buildTimelineNodes(listOf(a, b, c)).map { it.departure })
        assertNotNull(buildTimelineNodes(listOf(a, b, c)).first())
    }

    // ---------------------------------------------------------------- which PENDING journeys an acceptance invalidates
    private fun named(id: String, from: String, to: String, depart: Long, durationMinutes: Long = 30,
        state: JourneyState = JourneyState.PENDING, kind: JourneyKind = JourneyKind.OPEN_REQUEST, intent: String? = null, unverified: Boolean = false) =
        Journey(id, kind, state, if (unverified) null else from, if (unverified) null else to, m(depart), m(depart + durationMinutes), unverified, intent)

    private fun invalidated(journeys: List<Journey>) = pendingJourneysToInvalidate(journeys, matrix).map { it.journey.id }

    @Test fun nothingIsInvalidatedWhenEverythingStillFits() {
        val committed = named("K", Place.A, Place.B, 0, state = JourneyState.ACCEPTED)
        assertEquals(emptyList(), invalidated(listOf(committed, named("P", Place.A, Place.B, 200))))
        assertEquals(emptyList(), invalidated(emptyList()))
    }

    @Test fun aPendingJourneyThatNoLongerFitsAroundACommittedOneIsInvalidatedWithTheReason() {
        val committed = named("K", Place.A, Place.B, 0, state = JourneyState.ACCEPTED)          // 0..30, back to A takes 30: free from 70
        val tooSoon = named("soon", Place.A, Place.B, 40)
        val fine = named("fine", Place.A, Place.B, 70)
        val result = pendingJourneysToInvalidate(listOf(committed, tooSoon, fine), matrix)
        assertEquals(listOf("soon"), result.map { it.journey.id })
        assertEquals(CONFLICT_TOO_SOON_AFTER_PREVIOUS, result.single().conflict.type)
        assertEquals("K", result.single().conflict.conflictingId)
        assertEquals(m(70), result.single().conflict.earliestFeasibleDeparture)
    }

    @Test fun aPendingJourneyTooCloseBeforeALaterCommittedOneIsInvalidatedToo() {
        val committed = named("K", Place.A, Place.B, 200, state = JourneyState.ACCEPTED)
        val result = pendingJourneysToInvalidate(listOf(committed, named("late", Place.A, Place.B, 175)), matrix)   // 205 + 30 + 10 > 200
        assertEquals(CONFLICT_TOO_LATE_FOR_NEXT, result.single().conflict.type)
    }

    @Test fun aSurvivorIsJudgedAgainstWhatRemainsNotAgainstWhatWasDropped() {
        val committed = named("K", Place.A, Place.B, 0, state = JourneyState.ACCEPTED)          // free from 70
        val p1 = named("P1", Place.A, Place.B, 40)                                              // clashes with K -> dropped
        val p2 = named("P2", Place.A, Place.B, 100)                                             // would clash with P1 (70 + 30 + 10 = 110), not with K
        assertEquals(listOf("P1"), invalidated(listOf(committed, p1, p2)))
    }

    @Test fun betweenTwoPendingJourneysTheEarlierOneWins() {
        val first = named("early", Place.A, Place.B, 0); val second = named("later", Place.A, Place.B, 20)
        assertEquals(listOf("later"), invalidated(listOf(first, second)))
        assertEquals(listOf("later"), invalidated(listOf(second, first)))
    }

    @Test fun theOutcomeNeverDependsOnTheOrderTheRowsArriveIn() {
        val journeys = listOf(
            named("K", Place.A, Place.B, 0, state = JourneyState.ACCEPTED),
            named("P1", Place.A, Place.B, 40), named("P2", Place.A, Place.B, 100), named("P3", Place.A, Place.B, 105), named("P4", Place.A, Place.B, 300)
        )
        val expected = invalidated(journeys)
        assertEquals(setOf("P1", "P3"), expected.toSet())
        fun permutations(items: List<Journey>): List<List<Journey>> =
            if (items.size <= 1) listOf(items) else items.flatMap { head -> permutations(items - head).map { listOf(head) + it } }
        permutations(journeys).forEach { assertEquals(expected.toSet(), invalidated(it).toSet()) }
    }

    @Test fun twoPendingJourneysAtTheSameTimeAreBrokenByIdNeverByRowOrder() {
        val a = named("a", Place.A, Place.B, 0); val b = named("b", Place.A, Place.B, 0)
        assertEquals(listOf("b"), invalidated(listOf(a, b)))
        assertEquals(listOf("b"), invalidated(listOf(b, a)))
    }

    @Test fun committedJourneysAreNeverInvalidatedEvenIfTheyClashWithEachOther() {
        val one = named("K1", Place.A, Place.B, 0, state = JourneyState.ACCEPTED)
        val two = named("K2", Place.A, Place.B, 10, state = JourneyState.ACCEPTED)
        assertEquals(emptyList(), invalidated(listOf(one, two)))
    }

    @Test fun alternativesOfOneIntentAreJudgedAsOneSlotAndEachOptionOnItsOwnMerits() {
        val committed = named("K", Place.A, Place.B, 0, state = JourneyState.ACCEPTED)          // free from 70
        val tooSoonOption = named("opt1", Place.A, Place.B, 40, kind = JourneyKind.SEAT_REQUEST, intent = "search-1")
        val fineOption = named("opt2", Place.A, Place.B, 100, kind = JourneyKind.SEAT_REQUEST, intent = "search-1")
        assertEquals(listOf("opt1"), invalidated(listOf(committed, tooSoonOption, fineOption)), "the option that still works survives")
    }

    @Test fun anOldRecordThatCannotBePlacedIsInvalidatedByTimeOverlapAlone() {
        val committed = named("K", Place.A, Place.B, 0, state = JourneyState.ACCEPTED)
        val old = named("old", Place.A, Place.B, 10, 10, unverified = true)
        val result = pendingJourneysToInvalidate(listOf(committed, old), matrix)
        assertEquals(CONFLICT_OVERLAPS_UNVERIFIED_LEGACY, result.single().conflict.type)
    }

    @Test fun aMissingMatrixValueMakesTheWholeRevalidationFailRatherThanGuess() {
        val sparse = FakeTravelTimeProvider(mapOf((Place.A to Place.B) to 1800L))
        assertFailsWith<ServiceUnavailableException> {
            pendingJourneysToInvalidate(listOf(named("K", Place.A, Place.B, 0, state = JourneyState.ACCEPTED), named("P", Place.A, Place.B, 100)), sparse)
        }
    }
}
