package com.example.ds04_monash_rideshare_application.backend

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// THE RIDE OWNS JOURNEY COMPLETION. A passenger's seat becomes COMPLETED only when its ride does; completing one
// seat by hand while the ride is still travelling is refused (409 RIDE_NOT_COMPLETED), because a COMPLETED journey is now
// physical history and must not be fabricated.
//
// Scenario (matrix: Clayton B <-> Caulfield D 30 min each way): ride A Clayton -> Caulfield, +0 .. +30, PASSENGER accepted.
class RequestCompletionAuthorityTest {
    private val base = futureDeparture(10)
    private fun at(minutes: Long) = base + minutes * MINUTE
    private val matrix = completionMatrix()
    private val rideA = "ride-a"

    private suspend fun HttpClient.completeSeat(caller: String, requestId: String = "seat-a"): HttpResponse =
        put("/requests/$requestId") {
            header(HttpHeaders.Authorization, "Bearer $caller"); contentType(ContentType.Application.Json); setBody("""{"status":"COMPLETED"}""")
        }

    // The anchor this person's COMPLETED requests/rides establish, ignoring the seeded demo history.
    private fun anchorId(person: String = PASSENGER): String? = transaction { latestCompletedJourneyOf(person, matrix) }?.id
    private fun anchor(person: String = PASSENGER): Journey? = transaction { latestCompletedJourneyOf(person, matrix) }

    private suspend fun HttpClient.assertRefusedAndNothingChanged(caller: String) {
        val rideBefore = rideRow(rideA)
        val response = completeSeat(caller)
        assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
        assertEquals(CODE_RIDE_NOT_COMPLETED, response.asError().code)
        assertEquals("This trip is completed when the driver completes the ride.", response.asError().error)
        assertEquals(RideRequestStatus.ACCEPTED, requestRow("seat-a")[RideRequests.status])
        val rideAfter = rideRow(rideA)
        assertEquals(rideBefore[Rides.status], rideAfter[Rides.status])
        assertEquals(rideBefore[Rides.seatsAvailable], rideAfter[Rides.seatsAvailable])
        assertNull(rideAfter[Rides.completedAt])
        assertNotEquals("seat-a", anchorId())
        assertEquals(0, warningRowCount())
    }

    // ================================================================================ the refusal
    @Test
    fun `a seat cannot be completed before its ride departs - by the passenger or the driver`() = withScheduling(matrix) {
        insertPlannedRide(rideA, DRIVER_A, Place.B, Place.D, at(0), 30)
        insertSeatRequest("seat-a", PASSENGER, rideA)
        assertRefusedAndNothingChanged(PASSENGER)
        assertRefusedAndNothingChanged(DRIVER_A)
    }

    @Test
    fun `a seat cannot be completed while its ride is under way - by the passenger or the driver`() = withScheduling(matrix) {
        // Left five minutes ago on the real clock, planned to arrive in 25 minutes.
        val now = System.currentTimeMillis()
        insertPlannedRide(rideA, DRIVER_A, Place.B, Place.D, now - 5 * MINUTE, 30)
        insertSeatRequest("seat-a", PASSENGER, rideA)
        assertRefusedAndNothingChanged(PASSENGER)
        assertRefusedAndNothingChanged(DRIVER_A)
    }

    @Test
    fun `a stranger still gets the permission error, not the lifecycle one`() = withScheduling(matrix) {
        insertPlannedRide(rideA, DRIVER_A, Place.B, Place.D, at(0), 30)
        insertSeatRequest("seat-a", PASSENGER, rideA)
        assertEquals(HttpStatusCode.Forbidden, completeSeat(OTHER_PASSENGER).status)
    }

    @Test
    fun `a failed early completion cannot be used to book an impossible journey`() = withScheduling(matrix) {
        insertPlannedRide(rideA, DRIVER_A, Place.B, Place.D, at(0), 30)
        insertSeatRequest("seat-a", PASSENGER, rideA)
        assertEquals(HttpStatusCode.Conflict, completeSeat(PASSENGER).status)
        // Still on the ride until 30 + 10 buffer: a trip leaving Caulfield at 39 is refused, 40 fits.
        openBetween(Place.D, Place.B, at(39)).scheduleConflict(CONFLICT_TOO_SOON_AFTER_PREVIOUS)
        assertEquals(HttpStatusCode.OK, openBetween(Place.D, Place.B, at(40)).status)
        // ... and one that would overlap the ride itself is refused too.
        openBetween(Place.B, Place.D, at(10)).scheduleConflict()
    }

    // ================================================================================ the ride completes it
    @Test
    fun `when the driver completes the ride the seat completes with it and uses the ride's completion time`() = withScheduling(matrix) {
        insertPlannedRide(rideA, DRIVER_A, Place.B, Place.D, at(0), 30)
        insertSeatRequest("seat-a", PASSENGER, rideA)
        insertPlannedAsk("pend-b", PASSENGER, Place.D, Place.B, at(45), 30)
        completeRideAt(DRIVER_A, rideA, at(50))

        assertEquals(RideRequestStatus.COMPLETED, requestRow("seat-a")[RideRequests.status])
        val history = assertNotNull(anchor())
        assertEquals("seat-a", history.id)
        assertEquals(at(50), history.arrival)                       // completedAt, not the planned 30
        // The passenger got the completion-time revalidation.
        assertEquals(RideRequestStatus.CONFLICTED, requestRow("pend-b")[RideRequests.status])
        // ... and the seat is now finished, so completing it again is not a way to change anything.
        assertEquals(HttpStatusCode.Conflict, completeSeat(PASSENGER).status)
        assertEquals(at(50), rideRow(rideA)[Rides.completedAt])
    }

    @Test
    fun `an automatically completed ride gives its seat the planned arrival as history and no observed time`() = withScheduling(matrix) {
        insertPlannedRide(rideA, DRIVER_A, Place.B, Place.D, at(0), 30)
        insertSeatRequest("seat-a", PASSENGER, rideA)
        expireStaleRecords(at(30) + 31 * MINUTE)
        assertEquals(RideStatus.COMPLETED, rideRow(rideA)[Rides.status])
        assertEquals(RideRequestStatus.COMPLETED, requestRow("seat-a")[RideRequests.status])
        assertNull(rideRow(rideA)[Rides.completedAt])
        val history = assertNotNull(anchor())
        assertEquals("seat-a", history.id)
        assertEquals(at(30), history.arrival)
        assertEquals(0, warningRowCount())
    }

    @Test
    fun `a manually completed ride gives its seat the actual completion time as history`() = withScheduling(matrix) {
        insertPlannedRide(rideA, DRIVER_A, Place.B, Place.D, at(0), 30)
        insertSeatRequest("seat-a", PASSENGER, rideA)
        completeRideAt(DRIVER_A, rideA, at(20))                      // early
        assertEquals(at(20), assertNotNull(anchor()).arrival)
    }

    @Test
    fun `a seat that never took the ride is not revalidated when it completes`() = withScheduling(matrix) {
        insertPlannedRide(rideA, DRIVER_A, Place.B, Place.D, at(0), 30)
        insertSeatRequest("seat-a", PASSENGER, rideA)
        insertSeatRequest("seat-cancelled", OTHER_PASSENGER, rideA, RideRequestStatus.CANCELLED)
        insertSeatRequest("seat-rejected", DRIVER_B, rideA, RideRequestStatus.REJECTED)
        insertPlannedAsk("other-pend", OTHER_PASSENGER, Place.D, Place.B, at(45), 30)
        insertPlannedAsk("driver-b-pend", DRIVER_B, Place.D, Place.B, at(45), 30)
        completeRideAt(DRIVER_A, rideA, at(50))
        assertEquals(RideRequestStatus.PENDING, requestRow("other-pend")[RideRequests.status])
        assertEquals(RideRequestStatus.PENDING, requestRow("driver-b-pend")[RideRequests.status])
        assertEquals(RideRequestStatus.CANCELLED, requestRow("seat-cancelled")[RideRequests.status])
        assertEquals(RideRequestStatus.REJECTED, requestRow("seat-rejected")[RideRequests.status])
    }

    // ================================================================================ legacy / inconsistent rows
    @Test
    fun `a completed seat on a live ride is not history and does not leave the schedule`() = withScheduling(matrix) {
        insertPlannedRide(rideA, DRIVER_A, Place.B, Place.D, at(0), 30)
        insertSeatRequest("seat-a", PASSENGER, rideA, RideRequestStatus.COMPLETED)      // inconsistent: ride is CONFIRMED
        assertNotEquals("seat-a", anchorId())
        // Still a commitment ending at 30, so the same bypass attempt fails: 39 refused, 40 fits.
        openBetween(Place.D, Place.B, at(39)).scheduleConflict(CONFLICT_TOO_SOON_AFTER_PREVIOUS)
        assertEquals(HttpStatusCode.OK, openBetween(Place.D, Place.B, at(40)).status)
        // A direct attempt to change it is still refused (it is already COMPLETED: terminal) and nothing moves.
        assertEquals(HttpStatusCode.Conflict, completeSeat(PASSENGER).status)
        assertEquals(RideRequestStatus.COMPLETED, requestRow("seat-a")[RideRequests.status])
    }

    @Test
    fun `an early-completed seat from before this rule is revalidated when its ride really completes`() = withScheduling(matrix) {
        insertPlannedRide(rideA, DRIVER_A, Place.B, Place.D, at(0), 30)
        insertSeatRequest("seat-a", PASSENGER, rideA, RideRequestStatus.COMPLETED)
        val respondedBefore = requestRow("seat-a")[RideRequests.respondedAt]
        insertPlannedAsk("pend-b", PASSENGER, Place.D, Place.B, at(45), 30)
        completeRideAt(DRIVER_A, rideA, at(50))
        // It rode to the end, so it is history now - from the ride's real completion - and was revalidated.
        assertEquals(RideRequestStatus.CONFLICTED, requestRow("pend-b")[RideRequests.status])
        val history = assertNotNull(anchor())
        assertEquals("seat-a", history.id)
        assertEquals(at(50), history.arrival)
        assertEquals(respondedBefore, requestRow("seat-a")[RideRequests.respondedAt])
    }

    @Test
    fun `an accepted seat on an already completed ride is reconciled without inventing a time`() = withScheduling(matrix) {
        insertPlannedRide(rideA, DRIVER_A, Place.B, Place.D, at(0), 30, status = RideStatus.COMPLETED)
        insertSeatRequest("seat-a", PASSENGER, rideA, RideRequestStatus.ACCEPTED)
        val before = requestRow("seat-a")
        val response = completeSeat(PASSENGER)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val after = requestRow("seat-a")
        assertEquals(RideRequestStatus.COMPLETED, after[RideRequests.status])
        assertEquals(before[RideRequests.respondedAt], after[RideRequests.respondedAt])
        assertNull(rideRow(rideA)[Rides.completedAt])
        // History comes from the ride's planned arrival (no observed time exists).
        assertEquals(at(30), assertNotNull(anchor()).arrival)
        // Doing it again changes nothing (terminal).
        assertEquals(HttpStatusCode.Conflict, completeSeat(PASSENGER).status)
        assertEquals(RideRequestStatus.COMPLETED, requestRow("seat-a")[RideRequests.status])
    }

    @Test
    fun `a pending request still cannot be completed`() = withScheduling(matrix) {
        insertPlannedRide(rideA, DRIVER_A, Place.B, Place.D, at(0), 30)
        insertSeatRequest("seat-p", PASSENGER, rideA, RideRequestStatus.PENDING)
        val response = completeSeat(PASSENGER, "seat-p")
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals(RideRequestStatus.PENDING, requestRow("seat-p")[RideRequests.status])
        assertTrue(response.asError().code != CODE_RIDE_NOT_COMPLETED)
    }
}
