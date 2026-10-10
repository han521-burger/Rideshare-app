package com.example.ds04_monash_rideshare_application.backend

import io.ktor.http.HttpStatusCode
import io.ktor.client.statement.bodyAsText
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// DIAGNOSTICS, not unit tests: Stage 9 scenarios run end to end (ride posted, seat requested, accepted, completed over HTTP) against
// the PRODUCTION travel-time matrix (OSRM over OpenStreetMap, src/main/resources/travel-times.json), with the server clock pinned
// to the instant the driver "pressed Complete". Every figure is read from the matrix, not typed in. Lines starting with DIAG are
// the report (run with `gradlew :backend:test --tests '*Stage9RealMatrixDiagnosticsTest' -i`); the assertions make it self-checking.
class Stage9RealMatrixDiagnosticsTest {
    private val matrix = ProductionTravelTimes.load()
    private val zone = SchedulingConfig.SERVICE_ZONE
    private val stamp = DateTimeFormatter.ofPattern("EEE d MMM HH:mm:ss").withZone(zone)

    private fun at14(daysAhead: Long): Long =
        LocalDate.now(zone).plusDays(daysAhead).atTime(14, 0).atZone(zone).toInstant().toEpochMilli()
    private fun fmt(ms: Long) = stamp.format(Instant.ofEpochMilli(ms))
    private fun secs(from: String, to: String) = matrix.repositioningSeconds(from, to)!!
    private fun mins(seconds: Long) = "%.2f min".format(seconds / 60.0)
    private fun diag(line: String) = println("DIAG $line")
    private fun name(id: String) = approvedPointById(id)!!.name
    private fun ceilToMinute(ms: Long) = (ms + MINUTE - 1) / MINUTE * MINUTE
    private val buffer = SchedulingConfig.TRANSFER_BUFFER_MILLIS

    // 1-4 and 6. One ride that finishes late, and what that does to everything around it.
    @Test
    fun lateCompletionAgainstTheRealMatrix() = withScheduling(matrix) {
        val b = Place.B; val d = Place.D
        val drive = secs(b, d)
        val t0 = at14(10)
        val plannedArrival = t0 + drive * 1000
        diag("--- Scenario A: ${name(b)} -> ${name(d)}, planned ${fmt(t0)} -> ${fmt(plannedArrival)}  (${name(b)}->${name(d)} = ${drive}s = ${mins(drive)})")

        val rideA = rideBetween(DRIVER_A, t0, b, d)
        assertEquals(plannedArrival, rideA.plannedArrivalTime)
        val seatA = postSeat(rideA.rideId, caller = PASSENGER).also { assertEquals(HttpStatusCode.OK, it.status) }
            .let { createdRequests(it.bodyAsText()).single().requestId }
        assertEquals(HttpStatusCode.OK, respond(DRIVER_A, seatA, "ACCEPTED").status)

        // Later journeys, all leaving Caulfield, each valid under the PLAN (plannedArrival + 0 + 10 min):
        val planEarliest = plannedArrival + buffer
        val tFuture = ceilToMinute(planEarliest) + 5 * MINUTE
        diag("plan: earliest departure from ${name(d)} = ${fmt(plannedArrival)} + 0s same place + 10 min buffer = ${fmt(planEarliest)}; the later journeys leave at ${fmt(tFuture)}")

        val driverAsk = openBetween(d, b, tFuture, caller = DRIVER_A)           // 1. a PENDING request of the driver's own
        assertEquals(HttpStatusCode.OK, driverAsk.status, driverAsk.bodyAsText())
        val pendingConflictId = createdRequests(driverAsk.bodyAsText()).single().requestId

        val rideR2 = rideBetween(DRIVER_B, tFuture, d, b)                        // 2. an ACCEPTED seat of the passenger's
        val seatR2 = postSeat(rideR2.rideId, caller = PASSENGER).also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
            .let { createdRequests(it.bodyAsText()).single().requestId }
        assertEquals(HttpStatusCode.OK, respond(DRIVER_B, seatR2, "ACCEPTED").status)

        val drive2 = rideR2.plannedArrivalTime!!
        val backToD = secs(b, d)
        val tCompatible = ceilToMinute(drive2 + backToD * 1000 + buffer) + 5 * MINUTE   // 3. leaves Clayton after R2, comfortably
        val compatible = openBetween(b, Place.A, tCompatible)                    // PENDING for the passenger
        assertEquals(HttpStatusCode.OK, compatible.status, compatible.bodyAsText())
        val compatibleId = createdRequests(compatible.bodyAsText()).single().requestId

        // The driver finishes 20 minutes late.
        val completedAt = plannedArrival + 20 * MINUTE
        completeRideAt(DRIVER_A, rideA.rideId, completedAt)
        val needed = completedAt + buffer
        diag("ACTUAL completion ${fmt(completedAt)} (20 min after plan). Earliest departure from ${name(d)} now = ${fmt(completedAt)} + 0s + 10 min = ${fmt(needed)}")

        // 1. PENDING -> CONFLICTED
        assertEquals(RideRequestStatus.CONFLICTED, requestRow(pendingConflictId)[RideRequests.status])
        val conflict = assertNotNull(conflictOf(pendingConflictId))
        diag("1) driver's pending request leaving ${fmt(tFuture)} -> CONFLICTED  (${conflict.type}, earliestFeasibleDeparture=${fmt(conflict.earliestFeasibleDeparture!!)}, repositioning=${conflict.repositioningSeconds}s, buffer=${conflict.transferBufferSeconds}s)")
        assertEquals(needed, conflict.earliestFeasibleDeparture)

        // 2. ACCEPTED stays ACCEPTED + warning
        assertEquals(RideRequestStatus.ACCEPTED, requestRow(seatR2)[RideRequests.status])
        val warning = warningsOf(PASSENGER).single()
        diag("2) passenger's ACCEPTED seat leaving ${fmt(tFuture)} stays ACCEPTED; warning: completed ${fmt(warning.details.previousCompletedAt)}, planned ${fmt(warning.details.previousPlannedArrivalTime!!)}, " +
            "repositioning ${warning.details.repositioningSeconds}s + buffer ${warning.details.transferBufferSeconds}s => earliest ${fmt(warning.details.earliestFeasibleDeparture)} > next departure ${fmt(warning.details.nextDepartureTime)}")
        assertEquals(needed, warning.details.earliestFeasibleDeparture)
        assertTrue(warning.details.earliestFeasibleDeparture > warning.details.nextDepartureTime)

        // 3. compatible PENDING stays
        assertEquals(RideRequestStatus.PENDING, requestRow(compatibleId)[RideRequests.status])
        diag("3) passenger's pending request leaving ${name(b)} at ${fmt(tCompatible)} (after seat R2 and the trip back) stays PENDING")

        // 4. same-place + buffer boundary, and 6. a new request uses the actual history
        val justShort = ceilToMinute(needed) - MINUTE
        val first = ceilToMinute(needed)
        val shortAnswer = openBetween(d, Place.A, justShort, caller = DRIVER_A)
        val detail = shortAnswer.scheduleConflict(CONFLICT_TOO_SOON_AFTER_PREVIOUS)
        diag("4/6) new request from ${name(d)} at ${fmt(justShort)} -> SCHEDULE_INFEASIBLE (earliest ${fmt(detail.earliestFeasibleDeparture!!)}, previous arrival used = ACTUAL ${fmt(detail.conflictingArrivalTime)})")
        val okAnswer = openBetween(d, Place.A, first, caller = DRIVER_A)
        diag("   same request at ${fmt(first)} -> HTTP ${okAnswer.status.value}")
        assertEquals(HttpStatusCode.OK, okAnswer.status, okAnswer.bodyAsText())
        assertEquals(completedAt, detail.conflictingArrivalTime)
    }

    // 5. Directional repositioning: the same pair of places, opposite directions, different answers.
    @Test
    fun directionalRepositioningAgainstTheRealMatrix() = withScheduling(matrix) {
        val b = Place.B; val d = Place.D
        val bd = secs(b, d); val db = secs(d, b)
        diag("--- Scenario B: ${name(b)}->${name(d)} = ${bd}s (${mins(bd)}), ${name(d)}->${name(b)} = ${db}s (${mins(db)}) - not symmetric: ${bd != db}")
        val t0 = at14(11)

        // Passenger finishes at Caulfield (ride B -> D), then wants to leave Clayton: needs D -> B.
        val ride1 = rideBetween(DRIVER_A, t0, b, d)
        val seat1 = createdRequests(postSeat(ride1.rideId, caller = PASSENGER).bodyAsText()).single().requestId
        respond(DRIVER_A, seat1, "ACCEPTED")
        val done1 = ride1.plannedArrivalTime!!
        completeRideAt(DRIVER_A, ride1.rideId, done1)
        val need1 = done1 + db * 1000 + buffer
        val short1 = openBetween(b, Place.A, ceilToMinute(need1) - MINUTE).scheduleConflict(CONFLICT_TOO_SOON_AFTER_PREVIOUS)
        diag("passenger ends at ${name(d)} ${fmt(done1)}; to leave ${name(b)} needs ${name(d)}->${name(b)} ${db}s + 10 min = ${fmt(need1)}; server said earliest ${fmt(short1.earliestFeasibleDeparture!!)} (repositioning ${short1.repositioningSeconds}s)")
        assertEquals(db, short1.repositioningSeconds)
        assertEquals(need1, short1.earliestFeasibleDeparture)
        assertEquals(HttpStatusCode.OK, openBetween(b, Place.A, ceilToMinute(need1)).status)

        // The mirror trip: finishes at Clayton (ride D -> B), then wants to leave Caulfield: needs B -> D.
        val ride2 = rideBetween(DRIVER_B, t0, d, b)
        val seat2 = createdRequests(postSeat(ride2.rideId, caller = OTHER_PASSENGER).bodyAsText()).single().requestId
        respond(DRIVER_B, seat2, "ACCEPTED")
        val done2 = ride2.plannedArrivalTime!!
        completeRideAt(DRIVER_B, ride2.rideId, done2)
        val need2 = done2 + bd * 1000 + buffer
        val short2 = openBetween(d, Place.A, ceilToMinute(need2) - MINUTE, caller = OTHER_PASSENGER).scheduleConflict(CONFLICT_TOO_SOON_AFTER_PREVIOUS)
        diag("passenger ends at ${name(b)} ${fmt(done2)}; to leave ${name(d)} needs ${name(b)}->${name(d)} ${bd}s + 10 min = ${fmt(need2)}; server said earliest ${fmt(short2.earliestFeasibleDeparture!!)} (repositioning ${short2.repositioningSeconds}s)")
        assertEquals(bd, short2.repositioningSeconds)
        assertEquals(need2, short2.earliestFeasibleDeparture)
        assertEquals(HttpStatusCode.OK, openBetween(d, Place.A, ceilToMinute(need2), caller = OTHER_PASSENGER).status)
    }

    // 7. Finishing early does not bring back a request that was already CONFLICTED. (The CONFLICTED row is written directly: the
    // engine only reaches that state through an accept-time or completion-time conflict, and what is under test is what a LATER
    // completion does to a row already in it.)
    @Test
    fun earlyCompletionDoesNotResurrectAConflictedRequestAgainstTheRealMatrix() = withScheduling(matrix) {
        val b = Place.B; val d = Place.D
        val drive = secs(b, d)
        val t0 = at14(12)
        val plannedArrival = t0 + drive * 1000
        val askDeparture = ceilToMinute(plannedArrival) + 5 * MINUTE    // 5 min after the planned arrival: inside the 10 min buffer
        val reason = """{"type":"TOO_SOON_AFTER_PREVIOUS","conflictingId":"earlier","conflictingKind":"RIDE","conflictingDepartureTime":1,"conflictingArrivalTime":2,"transferBufferSeconds":600,"proposedDepartureTime":3,"proposedArrivalTime":4}"""
        insertPlannedAsk("already-conflicted", PASSENGER, d, b, askDeparture, 12, RideRequestStatus.CONFLICTED, respondedAt = 4242L, conflictDetails = reason)

        val ride = rideBetween(DRIVER_A, t0, b, d)
        val seat = createdRequests(postSeat(ride.rideId, caller = PASSENGER).bodyAsText()).single().requestId
        assertEquals(HttpStatusCode.OK, respond(DRIVER_A, seat, "ACCEPTED").status)

        // The ride finishes EARLY (10 minutes before its planned arrival), after which the ask would comfortably have fitted.
        val completedAt = plannedArrival - 10 * MINUTE
        completeRideAt(DRIVER_A, ride.rideId, completedAt)
        val nowEarliest = completedAt + buffer
        diag("--- Scenario C: ask leaving ${name(d)} at ${fmt(askDeparture)} is CONFLICTED. Ride finishes EARLY at ${fmt(completedAt)} (planned ${fmt(plannedArrival)}): earliest departure now ${fmt(nowEarliest)} <= ask, so it would fit - yet it stays ${requestRow("already-conflicted")[RideRequests.status]}")
        assertTrue(nowEarliest <= askDeparture)
        assertEquals(RideRequestStatus.CONFLICTED, requestRow("already-conflicted")[RideRequests.status])
        assertEquals(reason, requestRow("already-conflicted")[RideRequests.conflictDetails])
        assertEquals(4242L, requestRow("already-conflicted")[RideRequests.respondedAt])
        // A NEW request for the same trip is how the passenger recovers - and it is accepted against the actual (early) history.
        val retry = openBetween(d, b, askDeparture)
        diag("   a NEW request for the same trip -> HTTP ${retry.status.value}")
        assertEquals(HttpStatusCode.OK, retry.status, retry.bodyAsText())
    }
}
