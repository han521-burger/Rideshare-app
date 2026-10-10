package com.example.ds04_monash_rideshare_application.backend

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.decodeFromString
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Stage 9: a journey's REAL completion time replaces its planned arrival for what comes next, and completing a ride
// revalidates the schedules of the people on it. Every scenario is in minutes from `base`; the server clock is pinned to the
// instant the driver "pressed Complete".
//
// The recurring scenario (matrix: Clayton B <-> Caulfield D 30 min each way):
//   Ride A  Clayton -> Caulfield, base+0 .. base+30 (planned), driver DRIVER_A, PASSENGER accepted on it.
//   Request Caulfield -> Clayton at base+45: under the plan 30 + 0 + 10 = 40 <= 45, so it is valid.
//   Ride A really finishes at base+50: 50 + 0 + 10 = 60 > 45, so it no longer is.
class ActualCompletionTest {
    private val base = futureDeparture(10)
    private fun at(minutes: Long) = base + minutes * MINUTE
    private val matrix = completionMatrix()
    private val rideA = "ride-a"
    private val zone = SchedulingConfig.SERVICE_ZONE

    // Ride A with PASSENGER accepted on it (and the driver DRIVER_A).
    private fun rideAWithPassenger(seat: String = "seat-a") {
        insertPlannedRide(rideA, DRIVER_A, Place.B, Place.D, at(0), 30)
        insertSeatRequest(seat, PASSENGER, rideA)
    }

    // ================================================================================ completedAt
    @Test
    fun `explicit completion stamps the server clock and ignores anything the client sends`() = withScheduling(matrix) {
        rideAWithPassenger()
        val response = atServerTime(at(50)) { completeRide(DRIVER_A, rideA, """{"status":"COMPLETED","completedAt":1,"respondedAt":2}""") }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val dto = schedulingJson.decodeFromString<RideDto>(response.bodyAsText())
        assertEquals(RideStatus.COMPLETED, dto.status)
        assertEquals(at(50), dto.completedAt)
        assertEquals(at(50), rideRow(rideA)[Rides.completedAt])
        // Everyone on the ride reads the same value, and the accepted request finished at that moment.
        assertEquals(at(50), rideAs(PASSENGER, rideA).completedAt)
        assertEquals(RideRequestStatus.COMPLETED, requestRow("seat-a")[RideRequests.status])
        assertEquals(at(50), requestRow("seat-a")[RideRequests.respondedAt])
    }

    @Test
    fun `a retried Complete keeps the first timestamp and changes nothing`() = withScheduling(matrix) {
        rideAWithPassenger()
        completeRideAt(DRIVER_A, rideA, at(50))
        val rideBefore = rideRow(rideA)
        val requestBefore = requestRow("seat-a")
        val warningsBefore = warningRowCount()

        val retry = atServerTime(at(90)) { completeRide(DRIVER_A, rideA) }
        assertEquals(HttpStatusCode.OK, retry.status, retry.bodyAsText())
        assertEquals(at(50), schedulingJson.decodeFromString<RideDto>(retry.bodyAsText()).completedAt)
        assertEquals(at(50), rideRow(rideA)[Rides.completedAt])
        assertEquals(rideBefore[Rides.status], rideRow(rideA)[Rides.status])
        assertEquals(requestBefore[RideRequests.respondedAt], requestRow("seat-a")[RideRequests.respondedAt])
        assertEquals(warningsBefore, warningRowCount())

        // ... even when the retry carries other edits, which a finished ride ignores.
        val withEdits = atServerTime(at(120)) { completeRide(DRIVER_A, rideA, """{"status":"COMPLETED","notes":"late edit"}""") }
        assertEquals(HttpStatusCode.OK, withEdits.status)
        assertEquals(at(50), rideRow(rideA)[Rides.completedAt])
        assertNull(rideRow(rideA)[Rides.notes])
    }

    @Test
    fun `a ride that has not started cannot be completed`() = withScheduling(matrix) {
        rideAWithPassenger()
        // The real clock: the ride leaves in ten days.
        val early = completeRide(DRIVER_A, rideA)
        assertEquals(HttpStatusCode.Conflict, early.status)
        assertEquals(CODE_RIDE_NOT_STARTED, early.asError().code)
        assertEquals(RideStatus.CONFIRMED, rideRow(rideA)[Rides.status])
        assertNull(rideRow(rideA)[Rides.completedAt])
        assertEquals(RideRequestStatus.ACCEPTED, requestRow("seat-a")[RideRequests.status])
        assertEquals(0, warningRowCount())

        // One millisecond before departure is still too early; the departure instant itself is not.
        val justBefore = atServerTime(at(0) - 1) { completeRide(DRIVER_A, rideA) }
        assertEquals(CODE_RIDE_NOT_STARTED, justBefore.asError().code)
        assertNull(rideRow(rideA)[Rides.completedAt])
        completeRideAt(DRIVER_A, rideA, at(0))
        assertEquals(at(0), rideRow(rideA)[Rides.completedAt])
    }

    @Test
    fun `a call cannot move the departure to finish a ride`() = withScheduling(matrix) {
        rideAWithPassenger()
        // Into the past, so that the ride "has started": refused, the stored departure still counts.
        val past = completeRide(DRIVER_A, rideA, """{"status":"COMPLETED","departureTime":${at(0) - DAY}}""")
        assertEquals(CODE_RIDE_NOT_STARTED, past.asError().code)
        assertEquals(at(0), rideRow(rideA)[Rides.departureTime])
        assertNull(rideRow(rideA)[Rides.completedAt])
        // Into the future of a ride that has started: also refused.
        val future = atServerTime(at(50)) { completeRide(DRIVER_A, rideA, """{"status":"COMPLETED","departureTime":${at(0) + DAY}}""") }
        assertEquals(CODE_RIDE_NOT_STARTED, future.asError().code)
        assertEquals(RideStatus.CONFIRMED, rideRow(rideA)[Rides.status])
        assertEquals(at(0), rideRow(rideA)[Rides.departureTime])
    }

    @Test
    fun `completion that fails rolls back and never records a completion time`() = withScheduling(matrix) {
        rideAWithPassenger()
        insertPlannedAsk("pend-b", PASSENGER, Place.D, Place.B, at(45), 30)
        transaction {
            exec("""CREATE TRIGGER fail_conflicting BEFORE UPDATE OF status ON ride_requests
                WHEN NEW.request_id = 'pend-b' AND NEW.status = 'CONFLICTED'
                BEGIN SELECT RAISE(ABORT, 'test revalidation failure'); END""")
        }
        val response = atServerTime(at(50)) { completeRide(DRIVER_A, rideA) }
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals(RideStatus.CONFIRMED, rideRow(rideA)[Rides.status])
        assertNull(rideRow(rideA)[Rides.completedAt])
        assertEquals(RideRequestStatus.ACCEPTED, requestRow("seat-a")[RideRequests.status])
        assertEquals(RideRequestStatus.PENDING, requestRow("pend-b")[RideRequests.status])
    }

    @Test
    fun `automatic completion does not fabricate a completion time and scheduling falls back to the plan`() = withScheduling(matrix) {
        rideAWithPassenger()
        // The system completes it 30 minutes after the planned arrival (Lifecycle.kt): a status change, not an observed arrival.
        expireStaleRecords(at(30) + 31 * MINUTE)
        assertEquals(RideStatus.COMPLETED, rideRow(rideA)[Rides.status])
        assertEquals(RideRequestStatus.COMPLETED, requestRow("seat-a")[RideRequests.status])
        assertNull(rideRow(rideA)[Rides.completedAt])
        assertNull(rideAs(PASSENGER, rideA).completedAt)
        assertEquals(0, warningRowCount())
        // Scheduling uses the planned arrival: base+30 + 0 + 10.
        assertEquals(CODE_SCHEDULE_INFEASIBLE, openBetween(Place.D, Place.B, at(39)).asError().code)
        assertEquals(HttpStatusCode.OK, openBetween(Place.D, Place.B, at(40)).status)
    }

    @Test
    fun `completed rows from before completedAt existed stay null`() = withScheduling(matrix) {
        val seeded = transaction { Rides.selectAll().where { Rides.status eq RideStatus.COMPLETED }.toList() }
        assertTrue(seeded.isNotEmpty())
        seeded.forEach { assertNull(it[Rides.completedAt]) }
        assertNull(rideAs(DRIVER_A, "ride-completed-passenger").completedAt)
    }

    // ================================================================================ completed-location history
    @Test
    fun `a new request is held to where and when the ride actually ended`() = withScheduling(matrix) {
        rideAWithPassenger()
        completeRideAt(DRIVER_A, rideA, at(50))
        // Planned arrival (30) would have allowed 40; the real one (50) needs 50 + 0 + 10 = 60.
        val tooSoon = openBetween(Place.D, Place.B, at(55)).scheduleConflict(CONFLICT_TOO_SOON_AFTER_PREVIOUS)
        assertEquals(at(60), tooSoon.earliestFeasibleDeparture)
        assertEquals(at(50), tooSoon.conflictingArrivalTime)
        assertEquals(0L, tooSoon.repositioningSeconds)
        assertEquals(Place.D, tooSoon.fromPointId)
        assertEquals(600L, tooSoon.transferBufferSeconds)
        // The same-place boundary: 59 is not feasible, 60 is.
        openBetween(Place.D, Place.B, at(59)).scheduleConflict(CONFLICT_TOO_SOON_AFTER_PREVIOUS)
        assertEquals(HttpStatusCode.OK, openBetween(Place.D, Place.B, at(60)).status)
    }

    @Test
    fun `repositioning after completion uses the directional matrix`() {
        // B -> D 20 minutes, D -> B 35 minutes.
        val directional = ScenarioTravelTimes(20, Triple(Place.B, Place.D, 20L), Triple(Place.D, Place.B, 35L))
        withScheduling(directional) {
            // PASSENGER's ride ends at Clayton (D -> B), then wants to leave Caulfield (B -> D = 20): 40 + 20 + 10 = 70.
            insertPlannedRide("ride-ends-b", DRIVER_A, Place.D, Place.B, at(0), 35)
            insertSeatRequest("seat-ends-b", PASSENGER, "ride-ends-b")
            completeRideAt(DRIVER_A, "ride-ends-b", at(40))
            val toCaulfield = openBetween(Place.D, Place.A, at(69)).scheduleConflict(CONFLICT_TOO_SOON_AFTER_PREVIOUS)
            assertEquals(1200L, toCaulfield.repositioningSeconds)
            assertEquals(at(70), toCaulfield.earliestFeasibleDeparture)
            assertEquals(HttpStatusCode.OK, openBetween(Place.D, Place.A, at(70)).status)

            // OTHER_PASSENGER's ride ends at Caulfield (B -> D), then wants to leave Clayton (D -> B = 35): 25 + 35 + 10 = 70.
            insertPlannedRide("ride-ends-d", DRIVER_B, Place.B, Place.D, at(0), 20)
            insertSeatRequest("seat-ends-d", OTHER_PASSENGER, "ride-ends-d")
            completeRideAt(DRIVER_B, "ride-ends-d", at(25))
            val toClayton = openBetween(Place.B, Place.A, at(69), caller = OTHER_PASSENGER).scheduleConflict(CONFLICT_TOO_SOON_AFTER_PREVIOUS)
            assertEquals(2100L, toClayton.repositioningSeconds)
            assertEquals(at(70), toClayton.earliestFeasibleDeparture)
            assertEquals(HttpStatusCode.OK, openBetween(Place.B, Place.A, at(70), caller = OTHER_PASSENGER).status)
        }
    }

    @Test
    fun `finishing earlier than planned makes the next journey reachable earlier`() = withScheduling(matrix) {
        rideAWithPassenger()
        completeRideAt(DRIVER_A, rideA, at(10))
        // The actual arrival (10) replaces the planned one (30): 10 + 0 + 10 = 20.
        openBetween(Place.D, Place.B, at(19)).scheduleConflict(CONFLICT_TOO_SOON_AFTER_PREVIOUS)
        assertEquals(HttpStatusCode.OK, openBetween(Place.D, Place.B, at(20)).status)
    }

    @Test
    fun `a ride completed long ago constrains nothing`() = withScheduling(matrix) {
        // Finished five days before the base time, and long since back at Clayton.
        insertPlannedRide("ride-old", DRIVER_A, Place.B, Place.D, at(0) - 5 * DAY, 30, status = RideStatus.COMPLETED, completedAt = at(0) - 5 * DAY + 40 * MINUTE)
        insertSeatRequest("seat-old", PASSENGER, "ride-old", RideRequestStatus.COMPLETED)
        assertEquals(HttpStatusCode.OK, openBetween(Place.B, Place.A, at(0)).status)
        assertEquals(HttpStatusCode.OK, openBetween(Place.D, Place.B, at(120)).status)
    }

    @Test
    fun `the driver's own completed ride is history for the driver too`() = withScheduling(matrix) {
        rideAWithPassenger()
        completeRideAt(DRIVER_A, rideA, at(50))
        // Driver ends at Caulfield at 50; a new ride from Clayton needs 30 minutes + 10: 90.
        val refused = postRide(DRIVER_A, at(89), from = Place.B, to = Place.A).scheduleConflict(CONFLICT_TOO_SOON_AFTER_PREVIOUS)
        assertEquals(at(90), refused.earliestFeasibleDeparture)
        assertEquals(1800L, refused.repositioningSeconds)
        assertEquals(rideA, refused.conflictingId)
        assertEquals("RIDE", refused.conflictingKind)
        assertEquals(at(90), rideBetween(DRIVER_A, at(90), from = Place.B, to = Place.A).departureTime)
    }

    @Test
    fun `the latest completed journey is the anchor and an unplaceable one establishes nothing`() = withScheduling(matrix) {
        insertPlannedRide("ride-1", DRIVER_A, Place.B, Place.D, at(0) - 3 * DAY, 30, status = RideStatus.COMPLETED, completedAt = at(0) - 3 * DAY + 35 * MINUTE)
        insertPlannedRide("ride-2", DRIVER_A, Place.B, Place.A, at(0) - 2 * DAY, 10, status = RideStatus.COMPLETED)
        insertSeatRequest("seat-1", PASSENGER, "ride-1", RideRequestStatus.COMPLETED)
        insertSeatRequest("seat-2", PASSENGER, "ride-2", RideRequestStatus.COMPLETED)
        // A completed ride that cannot be placed on the map (legacy: coordinates that are not an approved point).
        transaction {
            Rides.insert {
                it[rideId] = "ride-legacy"; it[driverId] = DRIVER_A
                it[originLat] = 0.0; it[originLng] = 0.0; it[destinationLat] = 0.1; it[destinationLng] = 0.1
                it[estimatedDurationSeconds] = 600; it[departureTime] = at(0) - DAY
                it[seatsTotal] = 3; it[seatsAvailable] = 3; it[status] = RideStatus.COMPLETED; it[createdAt] = at(0) - 2 * DAY
            }
        }
        val anchor = transaction { latestCompletedJourneyOf(PASSENGER, matrix) }
        assertNotNull(anchor)
        assertEquals("seat-2", anchor.id)
        assertEquals(JourneyState.COMPLETED, anchor.state)
        assertEquals(Place.A, anchor.destinationPointId)
        // Planned fallback: no completedAt on ride-2.
        assertEquals(at(0) - 2 * DAY + 10 * MINUTE, anchor.arrival)
        // The unplaceable ride gives the driver nothing beyond ride-2 (which is placed).
        val driverAnchor = transaction { latestCompletedJourneyOf(DRIVER_A, matrix) }
        assertEquals("ride-2", driverAnchor?.id)
    }

    // ================================================================================ PENDING revalidation
    @Test
    fun `a late completion turns an impossible pending request into conflicted, not cancelled`() = withScheduling(matrix) {
        rideAWithPassenger()
        insertPlannedAsk("pend-b", PASSENGER, Place.D, Place.B, at(45), 30)
        completeRideAt(DRIVER_A, rideA, at(50))

        val row = requestRow("pend-b")
        assertEquals(RideRequestStatus.CONFLICTED, row[RideRequests.status])
        assertEquals(at(50), row[RideRequests.respondedAt])
        val conflict = assertNotNull(conflictOf("pend-b"))
        assertEquals(CONFLICT_TOO_SOON_AFTER_PREVIOUS, conflict.type)
        assertEquals(at(60), conflict.earliestFeasibleDeparture)
        assertEquals(at(50), conflict.conflictingArrivalTime)
        assertEquals(0L, conflict.repositioningSeconds)
        assertEquals(600L, conflict.transferBufferSeconds)
        // The passenger reads the reason; it is the passenger's own information.
        val mine = myRequests(PASSENGER).first { it.requestId == "pend-b" }
        assertEquals(RideRequestStatus.CONFLICTED, mine.status)
        assertEquals(at(60), mine.conflict?.earliestFeasibleDeparture)
    }

    @Test
    fun `a pending request that still fits stays pending`() = withScheduling(matrix) {
        rideAWithPassenger()
        insertPlannedAsk("pend-edge", PASSENGER, Place.D, Place.B, at(60), 30)
        // Far enough after pend-edge (which ends at Clayton at 90) to be reachable: 90 + 30 back to Caulfield + 10 <= 200.
        insertPlannedAsk("pend-late", PASSENGER, Place.D, Place.B, at(200), 30)
        completeRideAt(DRIVER_A, rideA, at(50))
        assertEquals(RideRequestStatus.PENDING, requestRow("pend-edge")[RideRequests.status])
        assertEquals(RideRequestStatus.PENDING, requestRow("pend-late")[RideRequests.status])
        assertNull(requestRow("pend-edge")[RideRequests.conflictDetails])
    }

    @Test
    fun `several pending journeys are judged in departure order, ties by id, whatever order they were stored in`() = withScheduling(matrix) {
        rideAWithPassenger()
        // Insertion order is deliberately not departure order.
        insertPlannedAsk("pend-y", PASSENGER, Place.D, Place.B, at(62), 30)
        insertPlannedAsk("pend-x", PASSENGER, Place.D, Place.B, at(55), 30)
        // Two asks leaving Caulfield at the same minute: the smaller id is judged first and kept.
        insertPlannedAsk("pend-b2", PASSENGER, Place.D, Place.A, at(300), 20)
        insertPlannedAsk("pend-a2", PASSENGER, Place.D, Place.B, at(300), 30)
        completeRideAt(DRIVER_A, rideA, at(50))

        // x leaves at 55 < 60: conflicted. Judged without x, y (62) fits; had x been kept, y would have clashed with it.
        assertEquals(RideRequestStatus.CONFLICTED, requestRow("pend-x")[RideRequests.status])
        assertEquals(RideRequestStatus.PENDING, requestRow("pend-y")[RideRequests.status])
        // a2 before b2 (same departure): a2 is kept and b2 clashes with it.
        assertEquals(RideRequestStatus.PENDING, requestRow("pend-a2")[RideRequests.status])
        assertEquals(RideRequestStatus.CONFLICTED, requestRow("pend-b2")[RideRequests.status])
        assertEquals("pend-a2", conflictOf("pend-b2")?.conflictingId)
    }

    @Test
    fun `nobody who was not on the ride is touched`() = withScheduling(matrix) {
        rideAWithPassenger()
        // The same impossible-after-late-completion request, for people who are NOT on ride A.
        insertPlannedAsk("other-pending", OTHER_PASSENGER, Place.D, Place.B, at(45), 30)
        insertPlannedAsk("driver-b-pending", DRIVER_B, Place.D, Place.B, at(45), 30)
        // A passenger with only a PENDING seat on ride A: completing a ride cancels those (existing rule), never conflicts them.
        insertSeatRequest("seat-waiting", OTHER_PASSENGER, rideA, RideRequestStatus.PENDING)
        insertPlannedAsk("mine-pending", PASSENGER, Place.D, Place.B, at(45), 30)
        completeRideAt(DRIVER_A, rideA, at(50))

        assertEquals(RideRequestStatus.CONFLICTED, requestRow("mine-pending")[RideRequests.status])
        assertEquals(RideRequestStatus.PENDING, requestRow("other-pending")[RideRequests.status])
        assertEquals(RideRequestStatus.PENDING, requestRow("driver-b-pending")[RideRequests.status])
        assertEquals(RideRequestStatus.CANCELLED, requestRow("seat-waiting")[RideRequests.status])
        assertNull(requestRow("seat-waiting")[RideRequests.conflictDetails])
        assertEquals(0, warningRowCount(OTHER_PASSENGER))
        assertEquals(0, warningRowCount(DRIVER_B))
    }

    @Test
    fun `the driver's own pending journeys are revalidated as well`() = withScheduling(matrix) {
        rideAWithPassenger()
        // DRIVER_A asked for a seat himself (as a passenger) on a trip that no longer fits after his own late finish.
        insertPlannedAsk("driver-ask", DRIVER_A, Place.D, Place.B, at(45), 30)
        completeRideAt(DRIVER_A, rideA, at(50))
        assertEquals(RideRequestStatus.CONFLICTED, requestRow("driver-ask")[RideRequests.status])
        assertEquals(at(60), conflictOf("driver-ask")?.earliestFeasibleDeparture)
    }

    @Test
    fun `a matrix that cannot judge a pair never aborts the completion or invents a conflict`() {
        // Only Clayton -> Caulfield is known: Caulfield -> Huntingdale cannot be verified.
        val holes = FakeTravelTimeProvider(mapOf((Place.B to Place.D) to 1800L))
        withScheduling(holes) {
            rideAWithPassenger()
            // Leaves Huntingdale, which is not where ride A ended: Caulfield -> Huntingdale is the pair the matrix lacks.
            insertPlannedAsk("unverifiable", PASSENGER, Place.A, Place.B, at(45), 10)
            completeRideAt(DRIVER_A, rideA, at(50))
            assertEquals(RideStatus.COMPLETED, rideRow(rideA)[Rides.status])
            assertEquals(RideRequestStatus.PENDING, requestRow("unverifiable")[RideRequests.status])
        }
    }

    // ================================================================================ ACCEPTED journeys: warn, never change
    private fun acceptedLaterRide(driver: String = DRIVER_B, departure: Long = at(45), passenger: String = PASSENGER, id: String = "ride-r2", seat: String = "seat-r2") {
        insertPlannedRide(id, driver, Place.D, Place.B, departure, 30)
        insertSeatRequest(seat, passenger, id)
    }

    @Test
    fun `an impossible accepted journey stays accepted and the passenger is warned`() = withScheduling(matrix) {
        rideAWithPassenger()
        acceptedLaterRide()
        val seatsBefore = rideRow("ride-r2")[Rides.seatsAvailable]
        completeRideAt(DRIVER_A, rideA, at(50))

        // Nothing about the booking changed.
        assertEquals(RideRequestStatus.ACCEPTED, requestRow("seat-r2")[RideRequests.status])
        assertNull(requestRow("seat-r2")[RideRequests.conflictDetails])
        assertEquals(RideStatus.CONFIRMED, rideRow("ride-r2")[Rides.status])
        assertEquals(seatsBefore, rideRow("ride-r2")[Rides.seatsAvailable])
        assertEquals(RideRequestStatus.ACCEPTED, myRequests(PASSENGER).first { it.requestId == "seat-r2" }.status)

        // The warning is stored, reachable later (a fresh read, not the Complete response), and fully described.
        val warning = warningsOf(PASSENGER).single()
        assertEquals(WARNING_PREVIOUS_RIDE_FINISHED_LATE, warning.type)
        assertEquals(WARNING_SUBJECT_REQUEST, warning.subjectKind)
        assertEquals("seat-r2", warning.subjectId)
        assertEquals(rideA, warning.details.previousRideId)
        assertEquals(at(50), warning.details.previousCompletedAt)
        assertEquals(at(30), warning.details.previousPlannedArrivalTime)
        assertEquals(Place.D, warning.details.fromPointId)
        assertEquals(Place.D, warning.details.toPointId)
        assertEquals(0L, warning.details.repositioningSeconds)
        assertEquals(600L, warning.details.transferBufferSeconds)
        assertEquals(at(60), warning.details.earliestFeasibleDeparture)
        assertEquals(at(45), warning.details.nextDepartureTime)
        assertEquals(at(50), warning.createdAt)
    }

    @Test
    fun `an accepted journey is warned only when the actual completion makes it unreachable`() = withScheduling(matrix) {
        rideAWithPassenger()
        // Accepted later rides leaving Caulfield at 59 (one minute short) and 60 (just makes it) after a finish at 50.
        insertPlannedRide("ride-59", DRIVER_B, Place.D, Place.B, at(59), 30); insertSeatRequest("seat-59", PASSENGER, "ride-59")
        insertPlannedRide("ride-60", OTHER_PASSENGER, Place.D, Place.B, at(60), 30); insertSeatRequest("seat-60", PASSENGER, "ride-60")
        completeRideAt(DRIVER_A, rideA, at(50))
        assertEquals(listOf("seat-59"), warningsOf(PASSENGER).map { it.subjectId })
    }

    @Test
    fun `completing on time or early warns nobody`() {
        for (finish in listOf(30L, 10L)) {
            withScheduling(matrix) {
                rideAWithPassenger()
                // Exactly reachable from a finish at 30 (40 <= 40) and from a finish at 10 (20 <= 40).
                acceptedLaterRide(departure = at(40))
                completeRideAt(DRIVER_A, rideA, at(finish))
                assertEquals(0, warningRowCount(), "finish at $finish")
                assertEquals(RideRequestStatus.ACCEPTED, requestRow("seat-r2")[RideRequests.status])
            }
        }
    }

    @Test
    fun `the driver is warned about a later ride of their own, and its passengers are not touched`() = withScheduling(matrix) {
        rideAWithPassenger()
        // DRIVER_A has posted a later ride from Caulfield; OTHER_PASSENGER is waiting on it and PASSENGER is not involved.
        insertPlannedRide("ride-r3", DRIVER_A, Place.D, Place.B, at(45), 30, status = RideStatus.OPEN, seatsAvailable = 3)
        insertSeatRequest("seat-r3", OTHER_PASSENGER, "ride-r3", RideRequestStatus.PENDING)
        completeRideAt(DRIVER_A, rideA, at(50))

        val warning = warningsOf(DRIVER_A).single()
        assertEquals(WARNING_SUBJECT_RIDE, warning.subjectKind)
        assertEquals("ride-r3", warning.subjectId)
        assertEquals(at(60), warning.details.earliestFeasibleDeparture)
        assertEquals(RideStatus.OPEN, rideRow("ride-r3")[Rides.status])
        assertEquals(at(45), rideRow("ride-r3")[Rides.departureTime])
        assertEquals(RideRequestStatus.PENDING, requestRow("seat-r3")[RideRequests.status])
        assertTrue(warningsOf(OTHER_PASSENGER).isEmpty())
        assertTrue(warningsOf(PASSENGER).isEmpty())
    }

    @Test
    fun `a warning is private to the person it is about`() = withScheduling(matrix) {
        rideAWithPassenger()
        acceptedLaterRide()
        completeRideAt(DRIVER_A, rideA, at(50))
        assertEquals(1, warningsOf(PASSENGER).size)
        // The driver of the next ride, a bystander and the first ride's driver (nothing at risk for them) see none.
        assertTrue(warningsOf(DRIVER_B).isEmpty())
        assertTrue(warningsOf(OTHER_PASSENGER).isEmpty())
        assertTrue(warningsOf(DRIVER_A).isEmpty())
        assertEquals(1, warningRowCount())
        assertEquals(1, warningRowCount(PASSENGER))
        // Nothing another person can read carries it: the next ride's driver's views of the ride and of its requests, and the
        // Complete response itself.
        val leakMarkers = listOf("previousCompletedAt", "previousRideId", "earliestFeasibleDeparture", "PREVIOUS_RIDE_FINISHED_LATE", "warning")
        val views = listOf(
            get("/rides/ride-r2") { header(HttpHeaders.Authorization, "Bearer $DRIVER_B") }.bodyAsText(),
            get("/requests") { header(HttpHeaders.Authorization, "Bearer $DRIVER_B") }.bodyAsText(),
            get("/rides") { header(HttpHeaders.Authorization, "Bearer $OTHER_PASSENGER") }.bodyAsText(),
            get("/requests/mine") { header(HttpHeaders.Authorization, "Bearer $OTHER_PASSENGER") }.bodyAsText(),
            get("/rides/$rideA") { header(HttpHeaders.Authorization, "Bearer $DRIVER_A") }.bodyAsText()
        )
        views.forEach { body -> leakMarkers.forEach { marker -> assertFalse(body.contains(marker, ignoreCase = true), "'$marker' leaked into: $body") } }
        // An anonymous caller gets nothing at all.
        assertEquals(HttpStatusCode.Unauthorized, get("/schedule-warnings").status)
    }

    @Test
    fun `a warning stops showing once its journey is no longer live`() = withScheduling(matrix) {
        rideAWithPassenger()
        acceptedLaterRide()
        completeRideAt(DRIVER_A, rideA, at(50))
        assertEquals(1, warningsOf(PASSENGER).size)
        val cancel = put("/requests/seat-r2") {
            header(HttpHeaders.Authorization, "Bearer $PASSENGER"); contentType(ContentType.Application.Json); setBody("""{"status":"CANCELLED"}""")
        }
        assertEquals(HttpStatusCode.OK, cancel.status, cancel.bodyAsText())
        assertTrue(warningsOf(PASSENGER).isEmpty())
    }

    @Test
    fun `a warning is not a status and does not change the request funnel`() = withScheduling(matrix) {
        rideAWithPassenger()
        acceptedLaterRide()
        suspend fun funnel() = get("/users/me/analytics") { header(HttpHeaders.Authorization, "Bearer $PASSENGER") }.let {
            schedulingJson.decodeFromString<AnalyticsDto>(it.bodyAsText()).passengerFunnel
        }
        val before = funnel()
        assertEquals(2, before.accepted)
        completeRideAt(DRIVER_A, rideA, at(50))
        val after = funnel()
        // seat-a completed with its ride; seat-r2 is still counted as ACCEPTED, exactly once; no conflict was invented.
        assertEquals(before.accepted - 1, after.accepted)
        assertEquals(before.completed + 1, after.completed)
        assertEquals(before.conflicted, after.conflicted)
        assertEquals(before.cancelled, after.cancelled)
    }

    // ================================================================================ terminal states stay terminal
    @Test
    fun `finishing early never resurrects a conflicted request`() = withScheduling(matrix) {
        rideAWithPassenger()
        val reason = """{"type":"TOO_SOON_AFTER_PREVIOUS","conflictingId":"x","conflictingKind":"RIDE","conflictingDepartureTime":1,"conflictingArrivalTime":2,"transferBufferSeconds":600,"proposedDepartureTime":3,"proposedArrivalTime":4}"""
        insertPlannedAsk("old-conflict", PASSENGER, Place.D, Place.B, at(25), 30, RideRequestStatus.CONFLICTED, respondedAt = 12345L, conflictDetails = reason)
        // Finishing at 10 would make a 25 departure comfortably reachable (10 + 10 = 20).
        completeRideAt(DRIVER_A, rideA, at(10))
        val row = requestRow("old-conflict")
        assertEquals(RideRequestStatus.CONFLICTED, row[RideRequests.status])
        assertEquals(reason, row[RideRequests.conflictDetails])
        assertEquals(12345L, row[RideRequests.respondedAt])
    }

    @Test
    fun `expired, cancelled and rejected requests are left exactly as they were`() = withScheduling(matrix) {
        rideAWithPassenger()
        val terminal = listOf(RideRequestStatus.EXPIRED, RideRequestStatus.CANCELLED, RideRequestStatus.REJECTED)
        terminal.forEachIndexed { i, status -> insertPlannedAsk("t-$i", PASSENGER, Place.D, Place.B, at(45), 30, status, respondedAt = 777L + i) }
        completeRideAt(DRIVER_A, rideA, at(50))
        terminal.forEachIndexed { i, status ->
            assertEquals(status, requestRow("t-$i")[RideRequests.status])
            assertEquals(777L + i, requestRow("t-$i")[RideRequests.respondedAt])
            assertNull(requestRow("t-$i")[RideRequests.conflictDetails])
        }
    }

    // ================================================================================ repeating requests and the Stage 8 snapshot
    @Test
    fun `an occurrence of a repeating request can become conflicted without touching its creation snapshot`() = withScheduling(matrix) {
        rideAWithPassenger()
        val weekday = Instant.ofEpochMilli(at(45)).atZone(zone).dayOfWeek.value
        val body = openRequestBody(
            from = coords(Place.D), to = coords(Place.B), at = at(45), clientRequestId = "stage9-series-key",
            repeatDays = listOf(weekday), repeatWeeks = 2
        )
        val created = postOpen(body)
        assertEquals(HttpStatusCode.OK, created.status)
        val firstAnswer = created.bodyAsText()
        val result = creationResult(firstAnswer)
        assertEquals(2, result.created)
        val (first, second) = result.requests.sortedBy { it.desiredDepartureTime }
        assertEquals(RideRequestStatus.PENDING, first.status)
        val storedBefore = submissionResult(PASSENGER, "stage9-series-key")
        assertNotNull(storedBefore)

        completeRideAt(DRIVER_A, rideA, at(50))

        // The occurrence that no longer fits is conflicted; the one a week later is untouched.
        assertEquals(RideRequestStatus.CONFLICTED, requestRow(first.requestId)[RideRequests.status])
        assertEquals(RideRequestStatus.PENDING, requestRow(second.requestId)[RideRequests.status])
        // The record of what the CREATION call came to is exactly what it was, and replays verbatim.
        assertEquals(storedBefore, submissionResult(PASSENGER, "stage9-series-key"))
        val replay = postOpen(body)
        assertEquals(HttpStatusCode.OK, replay.status)
        assertEquals(firstAnswer, replay.bodyAsText())
        assertEquals(RideRequestStatus.PENDING, creationResult(replay.bodyAsText()).requests.first { it.requestId == first.requestId }.status)
    }

    // ================================================================================ legacy, request-level completion, privacy
    @Test
    fun `a legacy completed ride without planning fields anchors from its derived interval and is not a legacy estimate`() = withScheduling(matrix) {
        // Seeded history of OTHER_PASSENGER (ride-completed-driver, Glen Waverley -> Monash Clayton): made before planning fields existed.
        val ride = rideRow("ride-completed-driver")
        assertNull(ride[Rides.originPointId]); assertNull(ride[Rides.completedAt])
        val usesBefore = LegacyScheduling.legacyEstimateUses
        val anchor = transaction { latestCompletedJourneyOf(OTHER_PASSENGER, matrix) }
        assertNotNull(anchor)
        assertEquals(JourneyState.COMPLETED, anchor.state)
        assertEquals(Place.C, anchor.originPointId)
        assertEquals(Place.B, anchor.destinationPointId)
        // No completedAt on a legacy row: the best derived arrival (departure + matrix duration), nothing invented.
        assertEquals(ride[Rides.departureTime] + matrix.travelSeconds(Place.C, Place.B)!! * 1000, anchor.arrival)
        assertEquals(usesBefore, LegacyScheduling.legacyEstimateUses)
        // ... and the row is untouched.
        assertNull(rideRow("ride-completed-driver")[Rides.completedAt])
        assertNull(rideRow("ride-completed-driver")[Rides.originPointId])
    }

    @Test
    fun `completing a single seat on a live ride is refused and cannot be used to escape the schedule`() = withScheduling(matrix) {
        rideAWithPassenger()
        val done = put("/requests/seat-a") {
            header(HttpHeaders.Authorization, "Bearer $PASSENGER"); contentType(ContentType.Application.Json); setBody("""{"status":"COMPLETED"}""")
        }
        assertEquals(HttpStatusCode.Conflict, done.status, done.bodyAsText())
        assertEquals(CODE_RIDE_NOT_COMPLETED, done.asError().code)
        assertEquals(RideRequestStatus.ACCEPTED, requestRow("seat-a")[RideRequests.status])
        assertEquals(RideStatus.CONFIRMED, rideRow(rideA)[Rides.status])
        assertNull(rideRow(rideA)[Rides.completedAt])
        assertEquals(0, warningRowCount())
        // The seat is still a live commitment ending at 30: 30 + 0 + 10 = 40.
        assertEquals(CODE_SCHEDULE_INFEASIBLE, openBetween(Place.D, Place.B, at(39)).asError().code)
        assertEquals(HttpStatusCode.OK, openBetween(Place.D, Place.B, at(40)).status)
    }

    @Test
    fun `two passengers on one ride are each warned about their own next ride only`() = withScheduling(matrix) {
        rideAWithPassenger()
        insertSeatRequest("seat-a-other", OTHER_PASSENGER, rideA)
        insertPlannedRide("ride-p1", DRIVER_B, Place.D, Place.B, at(45), 30); insertSeatRequest("seat-p1", PASSENGER, "ride-p1")
        insertPlannedRide("ride-p2", DRIVER_B, Place.D, Place.A, at(52), 20); insertSeatRequest("seat-p2", OTHER_PASSENGER, "ride-p2")
        completeRideAt(DRIVER_A, rideA, at(50))
        assertEquals(listOf("seat-p1"), warningsOf(PASSENGER).map { it.subjectId })
        assertEquals(listOf("seat-p2"), warningsOf(OTHER_PASSENGER).map { it.subjectId })
        // The shared driver of both later rides is not on ride A and learns nothing about either.
        assertTrue(warningsOf(DRIVER_B).isEmpty())
        assertEquals(RideRequestStatus.ACCEPTED, requestRow("seat-p1")[RideRequests.status])
        assertEquals(RideRequestStatus.ACCEPTED, requestRow("seat-p2")[RideRequests.status])
    }

    @Test
    fun `deleting an account removes its private warnings`() = withScheduling(matrix) {
        rideAWithPassenger()
        acceptedLaterRide()
        completeRideAt(DRIVER_A, rideA, at(50))
        assertEquals(1, warningRowCount(PASSENGER))
        transaction { deleteAccount(PASSENGER, System.currentTimeMillis()) }
        assertEquals(0, warningRowCount(PASSENGER))
    }
}
