package com.example.ds04_monash_rideshare_application.backend

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Completion races. A ride's completion (which stamps the real time and revalidates its people) is decided under the same
// scheduling locks as creating, accepting and cancelling, so each race has exactly the outcomes of the two orders and nothing
// in between. The tests do not assert which order happened to win; they run the race repeatedly and assert that the FINAL
// state is one of the coherent outcomes, and that the actual completion time was honoured in every one.
//
// Scenario (matrix: Clayton B <-> Caulfield D 30 minutes each way), repeated on its own day each round so rounds are independent:
//   Ride A  Clayton -> Caulfield, +0 .. +30, driver DRIVER_A, PASSENGER accepted. It really finishes at +50.
//   Anything leaving Caulfield before +60 is then unreachable (50 + 0 + 10).
class CompletionConcurrencyTest {
    private val base = futureDeparture(20)
    private val matrix = completionMatrix()
    private val rounds = 8

    private fun roundBase(round: Int) = base + round * 2 * DAY
    private fun at(round: Int, minutes: Long) = roundBase(round) + minutes * MINUTE

    private fun rideOf(round: Int) = "race-a-$round"
    private fun seatOf(round: Int) = "race-seat-$round"

    private fun setUp(round: Int) {
        insertPlannedRide(rideOf(round), DRIVER_A, Place.B, Place.D, at(round, 0), 30)
        insertSeatRequest(seatOf(round), PASSENGER, rideOf(round))
    }

    private suspend fun <T> race(vararg actions: suspend () -> T): List<T> =
        coroutineScope { actions.map { action -> async(Dispatchers.Default) { action() } }.awaitAll() }

    @Test
    fun `completion racing a new request leaves nothing pending that the actual completion made impossible`() = withScheduling(matrix) {
        var createdFirst = 0
        for (round in 0 until rounds) {
            setUp(round)
            val askAt = at(round, 55)
            val (completion, create) = atServerTime(at(round, 50)) {
                race(
                    { completeRide(DRIVER_A, rideOf(round)) },
                    { openBetween(Place.D, Place.B, askAt) }
                )
            }
            assertEquals(HttpStatusCode.OK, completion.status, completion.bodyAsText())
            assertEquals(at(round, 50), rideRow(rideOf(round))[Rides.completedAt])

            val asks = requestsLeaving(askAt)
            when (create.status) {
                // The request got in first: it was valid against the plan, and the completion then conflicted it.
                HttpStatusCode.OK -> {
                    createdFirst++
                    val ask = asks.single()
                    assertEquals(RideRequestStatus.CONFLICTED, ask[RideRequests.status], "round $round")
                    assertEquals(at(round, 60), conflictOf(ask[RideRequests.requestId])?.earliestFeasibleDeparture)
                }
                // The completion got in first: the actual time refused it.
                HttpStatusCode.Conflict -> {
                    assertEquals(CODE_SCHEDULE_INFEASIBLE, create.asError().code)
                    assertTrue(asks.isEmpty(), "a refused request left a row (round $round)")
                }
                else -> throw AssertionError("unexpected ${create.status}: ${create.bodyAsText()}")
            }
            // Either way: nothing the actual completion made impossible is left actionable.
            assertTrue(asks.none { it[RideRequests.status] == RideRequestStatus.PENDING }, "round $round")
        }
        println("completion-vs-create: request first in $createdFirst of $rounds rounds")
    }

    @Test
    fun `completion racing an acceptance ends either accepted with a warning or conflicted and refused`() = withScheduling(matrix) {
        var acceptedFirst = 0
        for (round in 0 until rounds) {
            setUp(round)
            val next = "race-r2-$round"
            val nextSeat = "race-next-$round"
            insertPlannedRide(next, DRIVER_B, Place.D, Place.B, at(round, 45), 30, status = RideStatus.OPEN, seatsAvailable = 3)
            insertSeatRequest(nextSeat, PASSENGER, next, RideRequestStatus.PENDING)

            val (completion, accept) = atServerTime(at(round, 50)) {
                race(
                    { completeRide(DRIVER_A, rideOf(round)) },
                    { respond(DRIVER_B, nextSeat, "ACCEPTED") }
                )
            }
            assertEquals(HttpStatusCode.OK, completion.status, completion.bodyAsText())
            val status = requestRow(nextSeat)[RideRequests.status]
            val warned = warningsOf(PASSENGER).filter { it.subjectId == nextSeat }
            when (accept.status) {
                // Accepted while A was still running (it fitted the plan): stays accepted, and the late finish is a warning.
                HttpStatusCode.OK -> {
                    acceptedFirst++
                    assertEquals(RideRequestStatus.ACCEPTED, status, "round $round")
                    assertEquals(1, warned.size, "an accepted-then-late journey must carry exactly one warning (round $round)")
                    assertEquals(2, rideRow(next)[Rides.seatsAvailable])
                    assertEquals(RideStatus.CONFIRMED, rideRow(next)[Rides.status])
                }
                // The completion won while it was still pending: conflicted, and the accept was told it is gone.
                HttpStatusCode.Conflict -> {
                    assertEquals(CODE_REQUEST_UNAVAILABLE, accept.asError().code)
                    assertEquals(RideRequestStatus.CONFLICTED, status, "round $round")
                    assertTrue(warned.isEmpty(), "a conflicted request is not also a warning (round $round)")
                    assertEquals(3, rideRow(next)[Rides.seatsAvailable])
                    assertEquals(RideStatus.OPEN, rideRow(next)[Rides.status])
                }
                else -> throw AssertionError("unexpected ${accept.status}: ${accept.bodyAsText()}")
            }
        }
        println("completion-vs-accept: accept first in $acceptedFirst of $rounds rounds")
    }

    @Test
    fun `completion racing the passenger cancelling their seat stays coherent`() = withScheduling(matrix) {
        var cancelledFirst = 0
        for (round in 0 until rounds) {
            setUp(round)
            val pending = "race-pend-$round"
            insertPlannedAsk(pending, PASSENGER, Place.D, Place.B, at(round, 45), 30)

            val (completion, cancel) = atServerTime(at(round, 50)) {
                race(
                    { completeRide(DRIVER_A, rideOf(round)) },
                    { respond(PASSENGER, seatOf(round), "CANCELLED") }
                )
            }
            assertEquals(HttpStatusCode.OK, completion.status, completion.bodyAsText())
            assertEquals(RideStatus.COMPLETED, rideRow(rideOf(round))[Rides.status])
            assertEquals(at(round, 50), rideRow(rideOf(round))[Rides.completedAt])
            when (cancel.status) {
                // Cancelled first: the passenger was not on the ride when it finished, so there is no arrival to hold them to.
                HttpStatusCode.OK -> {
                    cancelledFirst++
                    assertEquals(RideRequestStatus.CANCELLED, requestRow(seatOf(round))[RideRequests.status], "round $round")
                    assertEquals(RideRequestStatus.PENDING, requestRow(pending)[RideRequests.status], "round $round")
                }
                // Completed first: the seat finished, and the passenger's pending trip was judged against the real arrival.
                HttpStatusCode.Conflict -> {
                    assertEquals(RideRequestStatus.COMPLETED, requestRow(seatOf(round))[RideRequests.status], "round $round")
                    assertEquals(RideRequestStatus.CONFLICTED, requestRow(pending)[RideRequests.status], "round $round")
                }
                else -> throw AssertionError("unexpected ${cancel.status}: ${cancel.bodyAsText()}")
            }
        }
        println("completion-vs-cancel: cancel first in $cancelledFirst of $rounds rounds")
    }

    @Test
    fun `two simultaneous completions stamp one time and apply the consequences once`() = withScheduling(matrix) {
        for (round in 0 until rounds) {
            setUp(round)
            val next = "race-r2-$round"
            insertPlannedRide(next, DRIVER_B, Place.D, Place.B, at(round, 45), 30)
            insertSeatRequest("race-next-$round", PASSENGER, next)
            insertPlannedAsk("race-pend-$round", PASSENGER, Place.D, Place.B, at(round, 50), 30)

            val answers: List<HttpResponse> = atServerTime(at(round, 50)) {
                race({ completeRide(DRIVER_A, rideOf(round)) }, { completeRide(DRIVER_A, rideOf(round)) })
            }
            answers.forEach { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
            assertEquals(at(round, 50), rideRow(rideOf(round))[Rides.completedAt])
            assertEquals(RideRequestStatus.COMPLETED, requestRow(seatOf(round))[RideRequests.status])
            assertEquals(RideRequestStatus.CONFLICTED, requestRow("race-pend-$round")[RideRequests.status])
            assertEquals(1, warningsOf(PASSENGER).count { it.subjectId == "race-next-$round" }, "round $round")
        }
        assertEquals(rounds.toLong(), warningRowCount(PASSENGER))
    }

    @Test
    fun `a completion that lost to an earlier one never overwrites its time`() = withScheduling(matrix) {
        setUp(0)
        atServerTime(at(0, 50)) { assertEquals(HttpStatusCode.OK, completeRide(DRIVER_A, rideOf(0)).status) }
        // Later retries from different instants, one after another.
        for (minutes in listOf(55L, 120L, 600L)) {
            atServerTime(at(0, minutes)) { assertEquals(HttpStatusCode.OK, completeRide(DRIVER_A, rideOf(0)).status) }
            assertEquals(at(0, 50), rideRow(rideOf(0))[Rides.completedAt])
        }
        assertNull(rideRow(rideOf(0))[Rides.notes])
    }

    // PASSENGER's open requests leaving at a given instant (the one the race decides).
    private fun requestsLeaving(departure: Long) = transaction {
        RideRequests.selectAll().where { (RideRequests.passengerId eq PASSENGER) and (RideRequests.desiredDepartureTime eq departure) }.toList()
    }
}
