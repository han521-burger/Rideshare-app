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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.decodeFromString
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// WHAT KIND OF CONCURRENCY IS THIS? Acceptance races come in different kinds and must not be mixed up:
//
//   A. two drivers, the SAME request       -> one underlying request: exactly one driver wins, the other is told it is gone.
//   B. one passenger, two DIFFERENT journeys, both still physically possible -> accepting one leaves the other PENDING.
//   C. ... but the acceptance moves the passenger onto a different interval that makes the other impossible -> CONFLICTED.
//   D. same journeyIntentId alternatives    -> one wins, the rest are CANCELLED (never CONFLICTED).
//   E. different passengers                 -> independent; nothing leaks across.
//
// For the concurrent cases the test does not assert "whichever status the race happened to produce". It reads the FINAL
// stored state back and checks the invariant with its own arithmetic (not the production code): every ACCEPTED journey
// fits the other ACCEPTED ones, every remaining PENDING journey still fits around them, and every CONFLICTED one really
// no longer does.
class AcceptanceRaceSemanticsTest {
    private val base = futureDeparture(10)
    private fun at(minutes: Long) = base + minutes * MINUTE
    private val third = OTHER_PASSENGER // a seeded account with a vehicle, used as a third driver / second passenger
    private val search = "intent-race-0001"

    // Clayton (B) <-> Caulfield (D) take 30 minutes each way; Huntingdale (A) -> Clayton 10, back 5; everything else 20.
    private val matrix = ScenarioTravelTimes(
        20, Triple(Place.B, Place.D, 30L), Triple(Place.D, Place.B, 30L), Triple(Place.A, Place.B, 10L), Triple(Place.B, Place.A, 5L)
    )

    // ------------------------------------------------------------------------------------------ set-up helpers
    private fun insertAsk(
        id: String, from: String, to: String, departure: Long, passenger: String = PASSENGER, state: String = RideRequestStatus.PENDING
    ) = transaction {
        val origin = approvedPointById(from)!!; val destination = approvedPointById(to)!!
        val seconds = matrix.travelSeconds(from, to)!!
        RideRequests.insert {
            it[requestId] = id; it[rideId] = null; it[passengerId] = passenger; it[seatsRequested] = 1
            it[RideRequests.status] = state
            it[originLat] = origin.lat; it[originLng] = origin.lng; it[destinationLat] = destination.lat; it[destinationLng] = destination.lng
            it[isAsap] = false; it[desiredDepartureTime] = departure; it[createdAt] = System.currentTimeMillis()
            it[originPointId] = from; it[destinationPointId] = to
            it[plannedDurationSeconds] = seconds; it[plannedArrivalTime] = departure + seconds * 1000
        }
    }

    private class Answer(val status: HttpStatusCode, val body: String) {
        val error: ErrorResponse? get() = runCatching { schedulingJson.decodeFromString<ErrorResponse>(body) }.getOrNull()
    }

    private suspend fun HttpClient.accept(driver: String, requestId: String, targetRideId: String? = null): Answer {
        val response = put("/requests/$requestId") {
            header(HttpHeaders.Authorization, "Bearer $driver")
            contentType(ContentType.Application.Json)
            val target = targetRideId?.let { ""","targetRideId":"$it"""" } ?: ""
            setBody("""{"status":"ACCEPTED"$target}""")
        }
        return Answer(response.status, response.bodyAsText())
    }

    private suspend fun HttpClient.feed(driver: String): List<RideRequestDto> =
        schedulingJson.decodeFromString(get("/requests") { header(HttpHeaders.Authorization, "Bearer $driver") }.bodyAsText())

    private suspend fun HttpClient.seatRequest(ride: RideDto, intent: String?, passenger: String = PASSENGER): String {
        val response = postSeat(ride.rideId, caller = passenger, journeyIntentId = intent)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return createdRequests(response.bodyAsText()).single().requestId
    }

    private suspend fun <T> race(vararg actions: suspend () -> T): List<T> =
        coroutineScope { actions.map { action -> async(Dispatchers.Default) { action() } }.awaitAll() }

    private fun rideRow(rideId: String) = transaction { Rides.selectAll().where { Rides.rideId eq rideId }.first() }
    private fun requestRow(id: String) = transaction { RideRequests.selectAll().where { RideRequests.requestId eq id }.first() }
    private fun conflictOf(id: String): ScheduleConflictDetails? =
        requestRow(id)[RideRequests.conflictDetails]?.let { schedulingJson.decodeFromString<ScheduleConflictDetails>(it) }

    // ------------------------------------------------------------------------------------------ the oracle
    // An independent re-statement of the rule, working from the stored rows only:
    //   previous.arrival + travel(previous.destination -> next.origin) + 10 minutes <= next.departure
    private class Leg(val id: String, val from: String, val to: String, val departure: Long, val arrival: Long)

    private fun needs(previous: Leg, next: Leg): Long =
        previous.arrival + matrix.travelSeconds(previous.to, next.from)!! * 1000 + 10 * MINUTE

    private fun legOf(row: org.jetbrains.exposed.sql.ResultRow): Leg {
        val rideId = row[RideRequests.rideId]
        return if (rideId != null) {
            val ride = rideRow(rideId)
            Leg(row[RideRequests.requestId], ride[Rides.originPointId]!!, ride[Rides.destinationPointId]!!, ride[Rides.departureTime], ride[Rides.plannedArrivalTime]!!)
        } else {
            Leg(row[RideRequests.requestId], row[RideRequests.originPointId]!!, row[RideRequests.destinationPointId]!!,
                row[RideRequests.desiredDepartureTime]!!, row[RideRequests.plannedArrivalTime]!!)
        }
    }

    private fun fitsAround(committed: List<Leg>, candidate: Leg): Boolean {
        val ordered = (committed + candidate).sortedWith(compareBy({ it.departure }, { if (it === candidate) 1 else 0 }))
        val i = ordered.indexOf(candidate)
        val before = ordered.getOrNull(i - 1); val after = ordered.getOrNull(i + 1)
        return (before == null || needs(before, candidate) <= candidate.departure) && (after == null || needs(candidate, after) <= after.departure)
    }

    private class Final(val accepted: List<String>, val pending: List<String>, val conflicted: List<String>, val cancelled: List<String>)

    // The invariant: ACCEPTED fit each other; every PENDING still fits around them; every CONFLICTED really does not.
    private fun assertFinalStateIsSound(passenger: String = PASSENGER, scope: Set<String>): Final {
        val rows = transaction { RideRequests.selectAll().where { RideRequests.passengerId eq passenger }.toList() }.filter { it[RideRequests.requestId] in scope }
        fun legs(state: String) = rows.filter { it[RideRequests.status] == state }.map(::legOf)
        val committed = legs(RideRequestStatus.ACCEPTED).sortedBy { it.departure }
        committed.zipWithNext { a, b -> assertTrue(needs(a, b) <= b.departure, "ACCEPTED ${a.id} and ${b.id} are physically impossible together") }
        legs(RideRequestStatus.PENDING).forEach { assertTrue(fitsAround(committed, it), "PENDING ${it.id} no longer fits but was left actionable") }
        legs(RideRequestStatus.CONFLICTED).forEach { assertTrue(!fitsAround(committed, it), "CONFLICTED ${it.id} would actually still fit") }
        fun ids(state: String) = rows.filter { it[RideRequests.status] == state }.map { it[RideRequests.requestId] }
        return Final(ids(RideRequestStatus.ACCEPTED), ids(RideRequestStatus.PENDING), ids(RideRequestStatus.CONFLICTED), ids(RideRequestStatus.CANCELLED))
    }

    // ================================================================ A. two drivers, the SAME request
    @Test
    fun twoDriversAcceptingTheSameRequestAtOnceExactlyOneWins() {
        for (existingRides in listOf(false, true)) repeat(6) {
            withScheduling(matrix) {
                insertAsk("o", Place.A, Place.B, at(0))
                val r1 = if (existingRides) rideBetween(DRIVER_A, at(0)) else null
                val r2 = if (existingRides) rideBetween(DRIVER_B, at(0)) else null
                val answers = race(
                    { accept(DRIVER_A, "o", r1?.rideId) },
                    { accept(DRIVER_B, "o", r2?.rideId) }
                )
                val winners = answers.indices.filter { answers[it].status == HttpStatusCode.OK }
                assertEquals(1, winners.size, "exactly one acceptance succeeds: ${answers.map { it.status }}")
                val winner = if (winners.single() == 0) DRIVER_A else DRIVER_B
                val loser = if (winner == DRIVER_A) DRIVER_B else DRIVER_A
                val lost = answers[1 - winners.single()]

                // The loser gets a clear, machine-readable, privacy-safe answer.
                assertEquals(HttpStatusCode.Conflict, lost.status)
                assertEquals(CODE_REQUEST_ALREADY_ACCEPTED, lost.error?.code)
                assertEquals(MESSAGE_ALREADY_ACCEPTED_BY_ANOTHER, lost.error?.error)
                val wonRideId = requestRow("o")[RideRequests.rideId]!!
                assertTrue(!lost.body.contains(winner) && !lost.body.contains(wonRideId), "the loser is not told who won: ${lost.body}")

                // One request, accepted once, attached to the winner's ride - and nothing was created for the loser.
                assertEquals(RideRequestStatus.ACCEPTED, requestStatus("o"))
                assertEquals(1L, transaction { RideRequests.selectAll().where { RideRequests.requestId eq "o" }.count() })
                assertEquals(winner, rideRow(wonRideId)[Rides.driverId])
                val ridesAtThatTime = transaction { Rides.selectAll().where { Rides.departureTime eq at(0) and (Rides.driverId inList listOf(DRIVER_A, DRIVER_B)) }.count() }
                assertEquals(if (existingRides) 2L else 1L, ridesAtThatTime, "no ride is made for the losing driver")
                val seatsTaken = listOfNotNull(r1, r2).sumOf { it.seatsTotal - rideRow(it.rideId)[Rides.seatsAvailable] }
                if (existingRides) {
                    assertEquals(1, seatsTaken, "exactly one seat in total")
                    val loserRide = if (loser == DRIVER_A) r1!! else r2!!
                    assertEquals(RideStatus.OPEN, rideRow(loserRide.rideId)[Rides.status], "the losing driver's ride is untouched")
                }

                // And it is gone from every other driver's feed straight away.
                assertTrue(feed(loser).none { it.requestId == "o" }, "the losing driver still sees it")
                assertTrue(feed(third).none { it.requestId == "o" }, "an uninvolved driver still sees it")
                assertEquals(RideRequestStatus.ACCEPTED, feed(winner).single { it.requestId == "o" }.status)
            }
        }
    }

    @Test
    fun theSameDriverPressingAcceptTwiceIsToldItIsAlreadyAcceptedNotThatSomeoneElseTookIt() = withScheduling(matrix) {
        insertAsk("o", Place.A, Place.B, at(0))
        assertEquals(HttpStatusCode.OK, accept(DRIVER_A, "o").status)
        val again = accept(DRIVER_A, "o")
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertEquals(CODE_REQUEST_ALREADY_ACCEPTED, again.error?.code)
        assertEquals(MESSAGE_ALREADY_ACCEPTED_BY_YOU, again.error?.error)
        assertEquals(1L, transaction { Rides.selectAll().where { Rides.departureTime eq at(0) and (Rides.driverId eq DRIVER_A) }.count() }, "no second ride")
    }

    @Test
    fun aRequestThatEndedAnyOtherWayIsReportedAsUnavailableWithoutSayingWhy() = withScheduling(matrix) {
        for ((id, state) in listOf("c" to RideRequestStatus.CANCELLED, "r" to RideRequestStatus.REJECTED, "k" to RideRequestStatus.COMPLETED, "x" to RideRequestStatus.CONFLICTED)) {
            insertAsk(id, Place.A, Place.B, at(0), state = state)
            val answer = accept(DRIVER_A, id)
            assertEquals(HttpStatusCode.Conflict, answer.status, id)
            assertEquals(CODE_REQUEST_UNAVAILABLE, answer.error?.code, id)
            assertEquals(MESSAGE_REQUEST_UNAVAILABLE, answer.error?.error, id)
            assertTrue(!answer.body.contains("clashes"), "a driver is not told about the passenger's other trip: ${answer.body}")
            assertEquals(state, requestStatus(id))
        }
    }

    // ================================================================ B. different journeys, still compatible
    @Test
    fun acceptingOneJourneyLeavesAnotherThatIsStillPossiblePending() = withScheduling(matrix) {
        insertAsk("outbound", Place.B, Place.D, at(0))        // Clayton -> Caulfield 2:00
        insertAsk("return", Place.D, Place.B, at(120))        // Caulfield -> Clayton 4:00
        val ride = rideBetween(DRIVER_A, at(0), Place.B, Place.D)
        assertEquals(HttpStatusCode.OK, accept(DRIVER_A, "outbound", ride.rideId).status)
        assertEquals(RideRequestStatus.ACCEPTED, requestStatus("outbound"))
        assertEquals(RideRequestStatus.PENDING, requestStatus("return"), "nothing about it became impossible")
        assertNull(conflictOf("return"))
        val state = assertFinalStateIsSound(scope = setOf("outbound", "return"))
        assertEquals(listOf("outbound"), state.accepted)
        assertEquals(listOf("return"), state.pending)
        // ...and it is still on offer to drivers.
        assertEquals(RideRequestStatus.PENDING, feed(third).single { it.requestId == "return" }.status)
    }

    // ================================================================ C. the acceptance moves the interval
    @Test
    fun anAcceptanceOntoALaterRideMakesTheReturnJourneyImpossible() = withScheduling(matrix) {
        insertAsk("outbound", Place.B, Place.D, at(0))        // wants 2:00..2:30
        insertAsk("return", Place.D, Place.B, at(75))         // 3:15: fine after 2:30 (2:30 + 0 + 10 = 2:40)
        insertAsk("later", Place.D, Place.B, at(80))          // 3:20: exactly enough after the ride below
        val ride = rideBetween(DRIVER_A, at(40), Place.B, Place.D)   // the ride that exists is 2:40..3:10
        assertEquals(HttpStatusCode.OK, accept(DRIVER_A, "outbound", ride.rideId).status)

        assertEquals(RideRequestStatus.ACCEPTED, requestStatus("outbound"))
        assertEquals(RideRequestStatus.CONFLICTED, requestStatus("return"), "3:10 + 0 repositioning + 10 buffer = 3:20 > 3:15")
        assertEquals(at(80), conflictOf("return")?.earliestFeasibleDeparture)
        assertEquals(RideRequestStatus.PENDING, requestStatus("later"), "3:20 is exactly feasible")
        val state = assertFinalStateIsSound(scope = setOf("outbound", "return", "later"))
        assertEquals(listOf("return"), state.conflicted)
        assertEquals(listOf("later"), state.pending)
        assertTrue(feed(third).none { it.requestId == "return" }, "a conflicted request is not offered to anyone")
        // (Only this scenario's requests: the demo data has some waiting asks of its own.)
        val mine = setOf("outbound", "return", "later")
        assertEquals(listOf("later"), feed(third).filter { it.requestId in mine && it.status == RideRequestStatus.PENDING }.map { it.requestId })
    }

    // ================================================================ same passenger, two DIFFERENT requests, at once
    private fun differentRequestsRace(
        label: String, from1: String, to1: String, wants1: Long, ride1At: Long, from2: String, to2: String, wants2: Long, ride2At: Long,
        allowed: Set<Set<String>>
    ) {
        repeat(5) {
            withScheduling(matrix) {
                insertAsk("o1", from1, to1, at(wants1)); insertAsk("o2", from2, to2, at(wants2))
                val r1 = rideBetween(DRIVER_A, at(ride1At), from1, to1); val r2 = rideBetween(DRIVER_B, at(ride2At), from2, to2)
                val answers = race({ accept(DRIVER_A, "o1", r1.rideId) }, { accept(DRIVER_B, "o2", r2.rideId) })
                val state = assertFinalStateIsSound(scope = setOf("o1", "o2"))
                val outcome = setOf("o1=" + requestStatus("o1"), "o2=" + requestStatus("o2"))
                assertTrue(outcome in allowed, "$label: unexpected final state $outcome (answers ${answers.map { it.status }})")
                // Whatever the race order, an answer of 200 is exactly an ACCEPTED request.
                assertEquals(state.accepted.size, answers.count { it.status == HttpStatusCode.OK })
                assertTrue(answers.all { it.status == HttpStatusCode.OK || it.status == HttpStatusCode.Conflict })
            }
        }
    }

    @Test
    fun differentRequestsWhoseAcceptedIntervalsClashLeaveOneAcceptedAndAFeasibleRest() = differentRequestsRace(
        "intervals clash", Place.A, Place.B, 0, 40, Place.C, Place.D, 60, 55,
        // o1 first: o2 (as asked) no longer fits -> CONFLICTED. o2 first: o1 (as asked 0..10) still fits -> stays PENDING.
        setOf(setOf("o1=ACCEPTED", "o2=CONFLICTED"), setOf("o1=PENDING", "o2=ACCEPTED"))
    )

    @Test
    fun differentRequestsThatAreStillCompatibleWhenAcceptedAreBothAccepted() = differentRequestsRace(
        "compatible as asked", Place.A, Place.B, 0, 0, Place.C, Place.D, 60, 60,
        setOf(setOf("o1=ACCEPTED", "o2=ACCEPTED"))
    )

    @Test
    fun differentRequestsWhereOneMovesDependOnWhichIsAcceptedFirstButAlwaysEndFeasible() = differentRequestsRace(
        "one moves", Place.A, Place.B, 0, 40, Place.C, Place.D, 60, 100,
        // o2 first (100..120): o1 as asked still fits and is then accepted onto its 40..50 ride -> both ACCEPTED.
        // o1 first (40..50): o2 AS ASKED (60..80) can no longer follow it, so it is CONFLICTED - even though a later ride
        // would have fitted - because a request is judged at the time it asked for.
        setOf(setOf("o1=ACCEPTED", "o2=ACCEPTED"), setOf("o1=ACCEPTED", "o2=CONFLICTED"))
    )

    // The two orders a race can take, each pinned exactly (the concurrent test above asserts only the invariant).
    @Test
    fun theClashingPairInEitherOrderEndsFeasibleWithTheRightLoserAnswer() {
        for (firstIsO1 in listOf(true, false)) withScheduling(matrix) {
            insertAsk("o1", Place.A, Place.B, at(0)); insertAsk("o2", Place.C, Place.D, at(60))
            val r1 = rideBetween(DRIVER_A, at(40)); val r2 = rideBetween(DRIVER_B, at(55), Place.C, Place.D)
            val first = if (firstIsO1) accept(DRIVER_A, "o1", r1.rideId) else accept(DRIVER_B, "o2", r2.rideId)
            assertEquals(HttpStatusCode.OK, first.status)
            val second = if (firstIsO1) accept(DRIVER_B, "o2", r2.rideId) else accept(DRIVER_A, "o1", r1.rideId)
            assertEquals(HttpStatusCode.Conflict, second.status)
            if (firstIsO1) {
                // o1 won: o2 as asked no longer fits, so it was conflicted the moment o1 was accepted; its driver is told it is gone.
                assertEquals(RideRequestStatus.CONFLICTED, requestStatus("o2"))
                assertEquals(CODE_REQUEST_UNAVAILABLE, second.error?.code)
            } else {
                // o2 won: o1 as asked (0..10) still fits, so it stays PENDING - but not onto THAT ride, which would clash.
                assertEquals(RideRequestStatus.PENDING, requestStatus("o1"))
                assertEquals(CODE_SCHEDULE_INFEASIBLE, second.error?.code)
                assertEquals(MESSAGE_PASSENGER_UNAVAILABLE, second.error?.error)
                assertNull(second.error?.conflict)
            }
            assertEquals(1, assertFinalStateIsSound(scope = setOf("o1", "o2")).accepted.size)
        }
    }

    // ================================================================ D. same-intent alternatives
    @Test
    fun alternativesOfOneJourneyAcceptedAtOnceLeaveOneAcceptedAndTheRestCancelledNeverConflicted() {
        repeat(6) {
            withScheduling(matrix) {
                val rides = listOf(rideBetween(DRIVER_A, at(0)), rideBetween(DRIVER_B, at(5)), rideBetween(third, at(10)))
                val drivers = listOf(DRIVER_A, DRIVER_B, third)
                val ids = rides.map { seatRequest(it, search) }
                val answers = race({ accept(drivers[0], ids[0]) }, { accept(drivers[1], ids[1]) }, { accept(drivers[2], ids[2]) })

                assertEquals(1, answers.count { it.status == HttpStatusCode.OK }, "${answers.map { it.status }}")
                val state = assertFinalStateIsSound(scope = ids.toSet())
                assertEquals(1, state.accepted.size)
                assertEquals(2, state.cancelled.size, "the others were options for the same journey: CANCELLED")
                assertTrue(state.pending.isEmpty() && state.conflicted.isEmpty(), "no alternative is left waiting, none is 'conflicted'")
                answers.filter { it.status != HttpStatusCode.OK }.forEach {
                    assertEquals(CODE_REQUEST_UNAVAILABLE, it.error?.code)
                }
                assertEquals(1, rides.sumOf { r -> r.seatsTotal - rideRow(r.rideId)[Rides.seatsAvailable] }, "exactly one seat taken in total")
                // No same-intent request remains actionable in any driver's feed.
                for (driver in drivers) assertTrue(feed(driver).none { it.status == RideRequestStatus.PENDING && it.requestId in ids }, "$driver")
            }
        }
    }

    // ================================================================ E. different passengers
    @Test
    fun anotherPassengersRequestIsNeverAffectedByAnAcceptance() = withScheduling(matrix) {
        // The same two requests that conflict for ONE passenger (see the scenario above), but belonging to two people.
        insertAsk("mine", Place.B, Place.D, at(0))
        insertAsk("theirs", Place.D, Place.B, at(75), passenger = third)
        val ride = rideBetween(DRIVER_A, at(40), Place.B, Place.D)
        assertEquals(HttpStatusCode.OK, accept(DRIVER_A, "mine", ride.rideId).status)
        assertEquals(RideRequestStatus.ACCEPTED, requestStatus("mine"))
        assertEquals(RideRequestStatus.PENDING, requestStatus("theirs"), "a different passenger's journey is none of this schedule's business")
        assertNull(conflictOf("theirs"))
        assertTrue(feed(DRIVER_B).any { it.requestId == "theirs" && it.status == RideRequestStatus.PENDING })
    }

    // ================================================================ the actionable feed
    @Test
    fun aDriversFeedOffersOnlyWhatIsStillPendingAndPossible() = withScheduling(matrix) {
        insertAsk("accepted", Place.B, Place.D, at(0))            // will be accepted (onto a later ride)
        insertAsk("impossible", Place.D, Place.B, at(75))         // ...which makes this one impossible
        insertAsk("possible", Place.D, Place.B, at(300))          // ...and leaves this one alone
        val ride = rideBetween(DRIVER_A, at(40), Place.B, Place.D)
        assertEquals(HttpStatusCode.OK, accept(DRIVER_A, "accepted", ride.rideId).status)

        // Alternatives of one search for a different trip: one is accepted, the others lose.
        val a1 = rideBetween(DRIVER_B, at(600), Place.A, Place.B); val a2 = rideBetween(third, at(605), Place.A, Place.B)
        val keep = seatRequest(a1, search); val lose = seatRequest(a2, search)
        assertEquals(HttpStatusCode.OK, accept(DRIVER_B, keep).status)

        val scenario = setOf("accepted", "impossible", "possible", keep, lose)
        for (driver in listOf(DRIVER_B, third)) {
            val actionable = feed(driver).filter { it.requestId in scenario && it.status == RideRequestStatus.PENDING }.map { it.requestId }
            assertEquals(listOf("possible"), actionable, "$driver is only offered the one request that is pending and possible")
        }
        assertEquals(RideRequestStatus.CANCELLED, feed(third).single { it.requestId == lose }.status, "a lost alternative shows as history, not as something to act on")
    }
}
