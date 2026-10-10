package com.example.ds04_monash_rideshare_application.backend

import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Rows made before planning fields existed are never rewritten, and must not distort the schedule rules:
//  * an old row whose endpoints are approved points takes part with an interval DERIVED in memory from the matrix;
//  * an old row that cannot be placed on the map still gets conservative time-overlap protection;
//  * a NEW journey never uses the legacy straight-line / MapKit estimates (LegacyScheduling counts every use).
class LegacyScheduleTest {
    private val base = futureDeparture(10)
    private fun at(minutes: Long) = base + minutes * MINUTE
    private fun matrix(vararg pairs: Triple<String, String, Long>) =
        ScenarioTravelTimes(20, Triple(Place.A, Place.B, 30L), Triple(Place.B, Place.A, 30L), *pairs)

    private fun legacyAsk(id: String, minute: Long, createdAt: Long = System.currentTimeMillis() - 1_000) =
        insertOpenAsk(id, desiredDeparture = at(minute), createdAt = createdAt)

    // ------------------------------------------------ canonicalisable old rows
    @Test
    fun anOldRequestAtApprovedPointsParticipatesWithAnIntervalDerivedFromTheMatrix() = withScheduling(matrix()) {
        legacyAsk("old-1", 0)
        // Derived: A -> B is 30 min, so it ends at 30; back at A needs B -> A 30 + 10: earliest 70.
        val conflict = openBetween(Place.A, Place.B, at(20)).scheduleConflict(CONFLICT_TOO_SOON_AFTER_PREVIOUS)
        assertEquals("old-1", conflict.conflictingId)
        assertEquals(at(30), conflict.conflictingArrivalTime)
        assertEquals(at(70), conflict.earliestFeasibleDeparture)
        assertEquals(HttpStatusCode.OK, openBetween(Place.A, Place.B, at(70)).status)
    }

    @Test
    fun derivingAnIntervalNeverWritesItBack() = withScheduling(matrix()) {
        legacyAsk("old-1", 0)
        openBetween(Place.A, Place.B, at(20))
        openBetween(Place.A, Place.B, at(70))
        val row = transaction { RideRequests.selectAll().where { RideRequests.requestId eq "old-1" }.first() }
        assertNull(row[RideRequests.plannedDurationSeconds]); assertNull(row[RideRequests.plannedArrivalTime])
        assertNull(row[RideRequests.originPointId]); assertNull(row[RideRequests.journeyKey])
    }

    @Test
    fun equivalentOldDuplicateRowsAreCollapsedSoTheOlderOneIsTheOneReported() = withScheduling(matrix()) {
        val now = System.currentTimeMillis()
        insertOpenAsk("old-newer", desiredDeparture = at(0) + 20_000, createdAt = now - 1_000)
        insertOpenAsk("old-oldest", desiredDeparture = at(0), createdAt = now - 60_000)
        assertEquals("old-oldest", openBetween(Place.A, Place.B, at(20)).scheduleConflict().conflictingId)
    }

    @Test
    fun anOldRideStillBeingDrivenIsJudgedByTheMatrixNotByItsStoredMapKitEstimate() = withScheduling(matrix()) {
        // Stored estimate 10 minutes (MapKit). Derived from coordinates + matrix it is 30.
        insertRideRow("old-ride", DRIVER_B, at(0), durationSeconds = 600, status = RideStatus.OPEN, seatsTotal = 3, seatsAvailable = 3)
        val tooSoon = openBetween(Place.A, Place.B, at(55), caller = DRIVER_B)
        // Derived: arrive B at 30, B -> A 30, buffer 10 -> 70. (The 10-minute MapKit figure would have allowed 50.)
        assertEquals("RIDE", tooSoon.scheduleConflict(CONFLICT_TOO_SOON_AFTER_PREVIOUS).conflictingKind)
        assertEquals(HttpStatusCode.OK, openBetween(Place.A, Place.B, at(70), caller = DRIVER_B).status)
    }

    // ------------------------------------------------ rows that cannot be placed on the map
    private val retired = -37.9 to 145.1 // not an approved point

    @Test
    fun anOldRequestThatCannotBePlacedStillBlocksAnOverlappingNewJourney() = withScheduling(matrix()) {
        insertOpenAsk("old-x", desiredDeparture = at(0), createdAt = System.currentTimeMillis() - 1_000, from = retired, to = Pts.clayton)
        val conflict = openBetween(Place.A, Place.B, at(20)).scheduleConflict(CONFLICT_OVERLAPS_UNVERIFIED_LEGACY)
        assertEquals("old-x", conflict.conflictingId)
        assertNull(conflict.earliestFeasibleDeparture, "nothing can be computed for a row with no location")
        // A journey well clear of it (more than the old 30-minute margin after its estimated end) is fine.
        assertEquals(HttpStatusCode.OK, openBetween(Place.A, Place.B, at(180)).status)
    }

    @Test
    fun anOldRideThatCannotBePlacedStillBlocksTheDriverFromOverlappingIt() = withScheduling(matrix()) {
        insertRideRow("old-ride-x", DRIVER_B, at(0), durationSeconds = 900, status = RideStatus.OPEN, seatsTotal = 3, seatsAvailable = 3)
        // Move its pickup off every approved point, as an old row at a since-retired hub would be.
        transaction { Rides.update({ Rides.rideId eq "old-ride-x" }) { it[originLat] = -37.9; it[originLng] = 145.1 } }
        openBetween(Place.A, Place.B, at(10), caller = DRIVER_B).scheduleConflict(CONFLICT_OVERLAPS_UNVERIFIED_LEGACY)
        assertEquals(HttpStatusCode.OK, openBetween(Place.A, Place.B, at(300), caller = DRIVER_B).status)
    }

    // ------------------------------------------------ new rows never use the legacy estimates
    @Test
    fun creatingAndCheckingNewJourneysNeverUsesTheStraightLineOrMapKitEstimates() = withScheduling(matrix()) {
        val before = LegacyScheduling.legacyEstimateUses

        // Open requests (scheduled, ASAP, repeating), including refusals.
        assertEquals(HttpStatusCode.OK, openBetween(Place.A, Place.B, at(0)).status)
        openBetween(Place.A, Place.B, at(20)).scheduleConflict()
        assertEquals(HttpStatusCode.OK, openBetween(Place.A, Place.B, 0, asap = true, caller = OTHER_PASSENGER).status)
        assertEquals(HttpStatusCode.OK, postOpen(openRequestBody(from = coords(Place.A), to = coords(Place.B), at = at(1320), repeatDays = listOf(1, 2, 3), repeatWeeks = 1)).status)

        // Posted rides (with a MapKit duration that must be ignored), and seat requests on them, and refusals.
        val ride = offerRide(DRIVER_A, at(2000), Pts.huntingdale, Pts.clayton)
        assertEquals(HttpStatusCode.OK, postSeat(ride.rideId, journeyIntentId = "intent-legacy-01").status)
        val second = offerRide(DRIVER_B, at(2005), Pts.huntingdale, Pts.clayton)
        assertEquals(HttpStatusCode.OK, postSeat(second.rideId, journeyIntentId = "intent-legacy-01").status)   // an alternative
        offerRide(DRIVER_A, at(5000), Pts.glenWaverley, Pts.clayton)
        // A different journey right after the alternatives does not fit.
        assertEquals(HttpStatusCode.Conflict, openBetween(Place.C, Place.D, at(2040)).status)

        // Driving rides take part in the passenger timeline too.
        assertEquals(HttpStatusCode.Conflict, openBetween(Place.A, Place.B, at(5010), caller = DRIVER_A).status)

        assertEquals(before, LegacyScheduling.legacyEstimateUses, "a new journey was scheduled with a legacy estimate")
    }

    @Test
    fun theLegacyEstimateCounterReallyCountsSoTheGuardAboveMeansSomething() = withScheduling(matrix()) {
        val before = LegacyScheduling.legacyEstimateUses
        insertOpenAsk("old-x", desiredDeparture = at(0), createdAt = System.currentTimeMillis() - 1_000, from = retired, to = Pts.clayton)
        openBetween(Place.A, Place.B, at(300))   // reading the unplaceable old row has to use the old estimate
        assertTrue(LegacyScheduling.legacyEstimateUses > before)
    }

    @Test
    fun newlyCreatedRowsAreNeverLeftWithoutPlanningFields() = withScheduling(matrix()) {
        val open = openBetween(Place.A, Place.B, at(0)).asRequest()
        val ride = offerRide(DRIVER_A, at(2000))
        val reqRow = transaction { RideRequests.selectAll().where { RideRequests.requestId eq open.requestId }.first() }
        assertNotNull(reqRow[RideRequests.plannedArrivalTime]); assertNotNull(reqRow[RideRequests.originPointId])
        val rideRow = transaction { Rides.selectAll().where { Rides.rideId eq ride.rideId }.first() }
        assertNotNull(rideRow[Rides.plannedArrivalTime]); assertNotNull(rideRow[Rides.originPointId]); assertNotNull(rideRow[Rides.plannedDurationSeconds])
    }
}
