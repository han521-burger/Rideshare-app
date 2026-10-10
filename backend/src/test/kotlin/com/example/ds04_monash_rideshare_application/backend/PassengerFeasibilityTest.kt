package com.example.ds04_monash_rideshare_application.backend

import io.ktor.client.HttpClient
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.decodeFromString
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Can the passenger physically get through their journeys? Every scenario here is built from the approved rule:
//   previous.arrival + reposition(previous.destination -> new.origin) + 10 min buffer <= new.departure
//   new.arrival      + reposition(new.destination -> next.origin)      + 10 min buffer <= next.departure
// Times are minutes after a base "2:00" (offset 0). Travel times come from a scenario matrix set to exact values,
// never from MapKit, distance or a fixed speed.
class PassengerFeasibilityTest {
    private val base = futureDeparture(10)
    private fun at(minutes: Long) = base + minutes * MINUTE

    // A -> B and B -> A both 30 minutes, everything else 20, unless a test says otherwise.
    private fun matrix(vararg pairs: Triple<String, String, Long>) =
        ScenarioTravelTimes(20, Triple(Place.A, Place.B, 30L), Triple(Place.B, Place.A, 30L), *pairs)

    // Live open requests the passenger has, by id.
    private fun openRequestIds(who: String = PASSENGER) = transaction {
        RideRequests.selectAll().where { (RideRequests.passengerId eq who) and RideRequests.originLat.isNotNull() }
            .filter { it[RideRequests.status] in LIVE_REQUEST_STATUSES }.map { it[RideRequests.requestId] }.sorted()
    }

    // The same wall-clock time N days later in Melbourne (a plain +24h would drift across a daylight-saving change).
    private fun dayLater(days: Long, minutes: Long = 0) =
        java.time.Instant.ofEpochMilli(base).atZone(SchedulingConfig.SERVICE_ZONE).plusDays(days).plusMinutes(minutes).toInstant().toEpochMilli()

    // ---------------------------------------------------------------- same route
    @Test
    fun sameRouteTooSoonIsRefusedUntilTheEarliestFeasibleMinute() = withScheduling(matrix()) {
        // Existing A -> B, 2:00-2:30. Back at A: arrive 2:30 + 30 (B -> A) + 10 buffer = 3:10.
        val first = openBetween(Place.A, Place.B, at(0)).asRequest()
        for (minutes in listOf(30L, 45L, 60L, 69L)) {
            val conflict = openBetween(Place.A, Place.B, at(minutes)).scheduleConflict(CONFLICT_TOO_SOON_AFTER_PREVIOUS)
            assertEquals(first.requestId, conflict.conflictingId, "at +$minutes")
            assertEquals(at(70), conflict.earliestFeasibleDeparture, "the earliest feasible departure is 3:10 whichever time was tried")
        }
        assertEquals(listOf(first.requestId), openRequestIds(), "a refused request leaves nothing behind")
        // Exactly the earliest feasible minute is fine, and so is anything later.
        assertEquals(HttpStatusCode.OK, openBetween(Place.A, Place.B, at(70)).status)
        assertEquals(HttpStatusCode.OK, openBetween(Place.A, Place.B, at(300)).status)
    }

    @Test
    fun theConflictIsDescribedWithStructuredValuesAndIsNotADuplicate() = withScheduling(matrix()) {
        openBetween(Place.A, Place.B, at(0)).asRequest()
        val response = openBetween(Place.A, Place.B, at(45))
        val error = response.asError()
        assertEquals(CODE_SCHEDULE_INFEASIBLE, error.code)
        assertNotEquals(CONFLICT_DUPLICATE_OPEN_REQUEST, error.code)
        assertNull(error.existingRequestId)
        val c = error.conflict!!
        assertEquals(CONFLICT_TOO_SOON_AFTER_PREVIOUS, c.type)
        assertEquals("OPEN_REQUEST", c.conflictingKind)
        assertEquals(Place.B, c.fromPointId); assertEquals("Monash Clayton", c.fromPointName)
        assertEquals(Place.A, c.toPointId); assertEquals("Huntingdale Station", c.toPointName)
        assertEquals(at(0), c.conflictingDepartureTime)
        assertEquals(at(30), c.conflictingArrivalTime)
        assertEquals(30 * 60L, c.repositioningSeconds)
        assertEquals(10 * 60L, c.transferBufferSeconds)
        assertEquals(at(70), c.earliestFeasibleDeparture)
        assertEquals(at(45), c.proposedDepartureTime)
        // No server-formatted clock time anywhere in the prose.
        assertTrue(Regex("\\d{1,2}:\\d{2}").find(error.error) == null, error.error)
    }

    @Test
    fun thereIsNoSameRouteCooldownAShortTripCanBeRepeatedAsSoonAsItIsPhysicallyPossible() =
        withScheduling(ScenarioTravelTimes(20, Triple(Place.A, Place.B, 10L), Triple(Place.B, Place.A, 5L))) {
            // A -> B takes only 10 minutes and B -> A only 5: arrive 10, back at A by 15, + 10 buffer = 25. There is no
            // "same route within N minutes" rule - 25 minutes later is fine, 24 is not, purely on the physics.
            openBetween(Place.A, Place.B, at(0)).asRequest()
            val conflict = openBetween(Place.A, Place.B, at(24)).scheduleConflict(CONFLICT_TOO_SOON_AFTER_PREVIOUS)
            assertEquals(at(25), conflict.earliestFeasibleDeparture)
            assertEquals(HttpStatusCode.OK, openBetween(Place.A, Place.B, at(25)).status)
        }

    // ---------------------------------------------------------------- same-location transfer
    @Test
    fun aJourneyStartingWhereThePreviousEndedStillNeedsTheTenMinuteBuffer() = withScheduling(matrix()) {
        // A -> B arrives at B (2:30). A new journey from B: repositioning 0, buffer 10 -> earliest 2:40.
        openBetween(Place.A, Place.B, at(0)).asRequest()
        for (minutes in listOf(30L, 35L, 39L)) {
            val conflict = openBetween(Place.B, Place.C, at(minutes)).scheduleConflict(CONFLICT_TOO_SOON_AFTER_PREVIOUS)
            assertEquals(0L, conflict.repositioningSeconds)
            assertEquals(at(40), conflict.earliestFeasibleDeparture)
        }
        assertEquals(HttpStatusCode.OK, openBetween(Place.B, Place.C, at(40)).status)
    }

    // ---------------------------------------------------------------- different locations
    @Test
    fun differentLocationsNeedTheMatrixRepositioningTimePlusTheBuffer() = withScheduling(matrix(Triple(Place.B, Place.C, 20L))) {
        // Existing A -> B arrives 2:30. C -> D at 2:45? B -> C is 20 min: 2:30 + 20 + 10 = 3:00, so no.
        openBetween(Place.A, Place.B, at(0)).asRequest()
        val conflict = openBetween(Place.C, Place.D, at(45)).scheduleConflict(CONFLICT_TOO_SOON_AFTER_PREVIOUS)
        assertEquals(20 * 60L, conflict.repositioningSeconds)
        assertEquals(Place.B, conflict.fromPointId); assertEquals(Place.C, conflict.toPointId)
        assertEquals(at(60), conflict.earliestFeasibleDeparture)
        openBetween(Place.C, Place.D, at(59)).scheduleConflict()
        assertEquals(HttpStatusCode.OK, openBetween(Place.C, Place.D, at(60)).status, "exactly enough repositioning + buffer")
    }

    @Test
    fun repositioningIsDirectionalAndNeverAssumedSymmetric() = withScheduling(
        // Arriving at C then needing to be at B takes 50 minutes; the reverse (B -> C) would only take 20.
        matrix(Triple(Place.C, Place.B, 50L), Triple(Place.B, Place.C, 20L), Triple(Place.D, Place.C, 30L))
    ) {
        openBetween(Place.D, Place.C, at(0)).asRequest()                 // D -> C, arrives C at 2:30
        // C -> B is 50 min: 2:30 + 50 + 10 = 3:30. A symmetric 20-minute guess would have allowed 3:00.
        val conflict = openBetween(Place.B, Place.A, at(60)).scheduleConflict()
        assertEquals(50 * 60L, conflict.repositioningSeconds)
        assertEquals(at(90), conflict.earliestFeasibleDeparture)
        assertEquals(HttpStatusCode.OK, openBetween(Place.B, Place.A, at(90)).status)
    }

    // ---------------------------------------------------------------- before AND after
    @Test
    fun aJourneyThatClashesOnlyWithThePreviousOneIsRefusedForThat() = withScheduling(matrix()) {
        openBetween(Place.A, Place.B, at(0)).asRequest()
        openBetween(Place.C, Place.D, at(600)).asRequest() // far in the future, no effect
        val conflict = openBetween(Place.A, Place.B, at(40)).scheduleConflict()
        assertEquals(CONFLICT_TOO_SOON_AFTER_PREVIOUS, conflict.type)
    }

    @Test
    fun aJourneyThatClashesOnlyWithTheNextOneIsRefusedForThat() = withScheduling(matrix()) {
        // Existing future journey C -> D at 4:00 (offset 120). New A -> B arrives B at (start + 30); B -> C = 20 (default).
        openBetween(Place.C, Place.D, at(120)).asRequest()
        // Leaves at offset 61: arrives 91 + 20 + 10 = 121 > 120 -> too late for the next one.
        val conflict = openBetween(Place.A, Place.B, at(61)).scheduleConflict(CONFLICT_TOO_LATE_FOR_NEXT)
        assertEquals("OPEN_REQUEST", conflict.conflictingKind)
        assertEquals(Place.B, conflict.fromPointId); assertEquals(Place.C, conflict.toPointId)
        assertEquals(20 * 60L, conflict.repositioningSeconds)
        assertEquals(at(60), conflict.latestFeasibleDeparture, "the latest it could leave is offset 60: 60 + 30 + 20 + 10 = 120")
        assertEquals(at(120), conflict.conflictingDepartureTime)
        // Offset 60 exactly makes it, and nothing was changed by the refusal.
        assertEquals(HttpStatusCode.OK, openBetween(Place.A, Place.B, at(60)).status)
        assertEquals(2, openRequestIds().size)
    }

    @Test
    fun aJourneyThatFitsBetweenTwoExistingOnesIsAccepted() = withScheduling(matrix()) {
        openBetween(Place.A, Place.B, at(0)).asRequest()      // ends 30
        openBetween(Place.C, Place.D, at(300)).asRequest()    // starts 300
        val between = openBetween(Place.A, Place.B, at(120))
        assertEquals(HttpStatusCode.OK, between.status, between.bodyAsText())
        assertEquals(3, openRequestIds().size)
    }

    @Test
    fun insertionBeforeALaterJourneyHonoursItsPickupLocation() = withScheduling(matrix(Triple(Place.B, Place.C, 20L))) {
        // The spec example: existing C -> D at 4:00 (offset 120 here), propose A -> B at 3:00 (offset 60).
        // Arrive B at 90, B -> C is 20, buffer 10: 120 <= 120. Exactly feasible.
        openBetween(Place.C, Place.D, at(120)).asRequest()
        assertEquals(HttpStatusCode.OK, openBetween(Place.A, Place.B, at(60)).status)
    }

    // ---------------------------------------------------------------- several journeys, atomic refusal
    @Test
    fun severalSeparatedJourneysCoexistAsPendingWithNoLimit() = withScheduling(matrix()) {
        val ids = listOf(0L, 300L, 600L, 900L).map { openBetween(Place.A, Place.B, at(it)).asRequest().requestId }
        assertEquals(4, ids.toSet().size)
        assertEquals(ids.sorted(), openRequestIds())
        assertEquals(4, driverFeedOpenAsks().size, "every one of them is an actionable ask for drivers")
    }

    @Test
    fun anImpossibleInsertionIsRefusedWithoutTouchingExistingRows() = withScheduling(matrix()) {
        val first = openBetween(Place.A, Place.B, at(0)).asRequest()
        val second = openBetween(Place.A, Place.B, at(300)).asRequest()
        val before = transaction {
            RideRequests.selectAll().where { RideRequests.passengerId eq PASSENGER }.map { r -> r[RideRequests.requestId] to r[RideRequests.status] }.sortedBy { it.first }
        }
        openBetween(Place.A, Place.B, at(150 + 130)).scheduleConflict()   // too close to the 300 one
        openBetween(Place.A, Place.B, at(20)).scheduleConflict()          // too close to the 0 one
        val after = transaction {
            RideRequests.selectAll().where { RideRequests.passengerId eq PASSENGER }.map { r -> r[RideRequests.requestId] to r[RideRequests.status] }.sortedBy { it.first }
        }
        assertEquals(before, after)
        assertEquals(listOf(first.requestId, second.requestId).sorted(), openRequestIds())
    }

    // ---------------------------------------------------------------- ASAP uses the same algorithm
    @Test
    fun asapIsJudgedByTheSameTimelineFromServerTime() = withScheduling(matrix()) {
        val now = System.currentTimeMillis()
        // A scheduled A -> B an hour from now. ASAP A -> B now arrives in 30; B -> A 30 + 10 > the 60 left: refused.
        openBetween(Place.A, Place.B, now + 60 * MINUTE + 5 * MINUTE).asRequest()
        val conflict = openBetween(Place.A, Place.B, 0, asap = true).scheduleConflict(CONFLICT_TOO_LATE_FOR_NEXT)
        assertTrue(conflict.proposedDepartureTime in now..System.currentTimeMillis(), "ASAP departs at server time")
        assertEquals(conflict.proposedDepartureTime + 30 * MINUTE, conflict.proposedArrivalTime, "arrives after the PLANNED duration")
    }

    @Test
    fun asapThatFitsBeforeALaterJourneyIsAcceptedAndStoredWithServerTimePlans() = withScheduling(matrix()) {
        val now = System.currentTimeMillis()
        openBetween(Place.A, Place.B, now + 120 * MINUTE).asRequest()
        val asap = openBetween(Place.A, Place.B, 0, asap = true).asRequest()
        assertEquals(asap.createdAt + 30 * MINUTE, asap.plannedArrivalTime)
    }

    // ---------------------------------------------------------------- concurrency
    @Test
    fun simultaneousIndividuallyValidButMutuallyInfeasibleCreatesLeaveAFeasibleTimeline() = withScheduling(matrix()) {
        // Every one of these is fine on its own; no two of them can both happen.
        val responses = coroutineScope {
            (0 until 6).map { i -> async(Dispatchers.Default) { openBetween(Place.A, Place.B, at(i * 8L)) } }.awaitAll()
        }
        val statuses = responses.groupingBy { it.status }.eachCount()
        assertEquals(1, statuses[HttpStatusCode.OK], statuses.toString())
        assertEquals(5, statuses[HttpStatusCode.Conflict], statuses.toString())
        assertTrue(responses.none { it.status.value >= 500 })
        responses.filter { it.status == HttpStatusCode.Conflict }.forEach { assertEquals(CODE_SCHEDULE_INFEASIBLE, it.asError().code) }
        assertEquals(1, openRequestIds().size)
    }

    @Test
    fun simultaneousFeasibleCreatesAllSucceed() = withScheduling(matrix()) {
        val responses = coroutineScope {
            (0 until 5).map { i -> async(Dispatchers.Default) { openBetween(Place.A, Place.B, at(i * 300L)) } }.awaitAll()
        }
        assertTrue(responses.all { it.status == HttpStatusCode.OK }, responses.map { it.status }.toString())
        assertEquals(5, openRequestIds().size)
    }

    @Test
    fun aDuplicateIsStillADuplicateAndIsAnsweredBeforeFeasibility() = withScheduling(matrix()) {
        val first = openBetween(Place.A, Place.B, at(0)).asRequest()
        val again = openBetween(Place.A, Place.B, at(0))
        assertEquals(CONFLICT_DUPLICATE_OPEN_REQUEST, again.asError().code)
        assertEquals(first.requestId, again.asError().existingRequestId)
        // Thirty seconds later is the same journey (±1 minute); a quarter hour later is a different one that does not fit.
        assertEquals(CONFLICT_DUPLICATE_OPEN_REQUEST, openBetween(Place.A, Place.B, at(0) + 30_000).asError().code)
        assertEquals(CODE_SCHEDULE_INFEASIBLE, openBetween(Place.A, Place.B, at(15)).asError().code)
    }

    @Test
    fun aRepeatingSeriesJudgesEveryDayOnItsOwnAndReportsTheOnesThatDoNotFit() = withScheduling(matrix()) {
        // One request on day 2 already; a daily series starting on day 0 includes day 2, which does not fit. The other days
        // are made - and the answer says which one was not, and why (it used to be skipped without a word).
        openBetween(Place.A, Place.B, dayLater(2, 10)).asRequest()   // day 2, 10 minutes after the series time
        val series = postOpen(
            openRequestBody(from = coords(Place.A), to = coords(Place.B), at = at(0), repeatDays = listOf(1, 2, 3, 4, 5, 6, 7), repeatWeeks = 1)
        )
        assertEquals(HttpStatusCode.OK, series.status, series.bodyAsText())
        val result = creationResult(series.bodyAsText())
        assertEquals(listOf(7, 6, 1), listOf(result.requested, result.created, result.rejected))
        val rejected = result.occurrences.single { it.outcome == OUTCOME_REJECTED }
        assertEquals(dayLater(2, 0), rejected.departureTime)
        assertEquals(CODE_SCHEDULE_INFEASIBLE, rejected.code)
        assertNotNull(rejected.conflict)
        val created = openRequestIds().size
        assertEquals(7, created, "6 of the 7 daily occurrences plus the pre-existing request")
        // Even when the FIRST occurrence does not fit, a series is a processed batch: every date is reported, none is made.
        val none = postOpen(openRequestBody(from = coords(Place.A), to = coords(Place.B), at = at(15), repeatDays = listOf(1, 2, 3), repeatWeeks = 1))
        assertEquals(HttpStatusCode.OK, none.status, none.bodyAsText())
        val noneResult = creationResult(none.bodyAsText())
        assertEquals(listOf(3, 0, 3), listOf(noneResult.requested, noneResult.created, noneResult.rejected))
        assertTrue(noneResult.occurrences.all { it.code == CODE_SCHEDULE_INFEASIBLE })
        assertEquals(created, openRequestIds().size)
    }
}
