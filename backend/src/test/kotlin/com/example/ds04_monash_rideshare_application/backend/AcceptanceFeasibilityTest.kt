package com.example.ds04_monash_rideshare_application.backend

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
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
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Accepting a request is judged by the same physical-feasibility model as making one (ScheduleFeasibility.kt):
//   * what the passenger has ACCEPTED always wins over a new acceptance;
//   * their other PENDING journeys that no longer fit become CONFLICTED (terminal);
//   * their other options for the SAME journey intent become CANCELLED (and Undo restores exactly those).
// Times are minutes after a base time. The matrix: A -> B 10, B -> A 5, everything else 20 (buffer: 10).
class AcceptanceFeasibilityTest {
    private val base = futureDeparture(10)
    private fun at(minutes: Long) = base + minutes * MINUTE
    private val third = OTHER_PASSENGER // a seeded account with a vehicle, used as a third driver
    private val search = "intent-search-0001"

    private fun quick(vararg more: Triple<String, String, Long>) =
        ScenarioTravelTimes(20, Triple(Place.A, Place.B, 10L), Triple(Place.B, Place.A, 5L), *more)

    // ---- state set up directly, so a scenario can start from "these are pending / these are accepted" ----
    private fun insertAsk(
        id: String, from: String, to: String, departure: Long, plannedMinutes: Long,
        passenger: String = PASSENGER, state: String = RideRequestStatus.PENDING
    ) = transaction {
        val origin = approvedPointById(from)!!; val destination = approvedPointById(to)!!
        RideRequests.insert {
            it[requestId] = id; it[rideId] = null; it[passengerId] = passenger; it[seatsRequested] = 1
            it[RideRequests.status] = state
            it[originLat] = origin.lat; it[originLng] = origin.lng
            it[destinationLat] = destination.lat; it[destinationLng] = destination.lng
            it[isAsap] = false; it[desiredDepartureTime] = departure; it[createdAt] = System.currentTimeMillis()
            it[originPointId] = from; it[destinationPointId] = to
            it[plannedDurationSeconds] = plannedMinutes * 60; it[plannedArrivalTime] = departure + plannedMinutes * MINUTE
        }
    }

    private suspend fun HttpClient.accept(
        driver: String, requestId: String, targetRideId: String? = null, cancelMine: Boolean = false
    ): HttpResponse = put("/requests/$requestId") {
        header(HttpHeaders.Authorization, "Bearer $driver")
        contentType(ContentType.Application.Json)
        val target = targetRideId?.let { ""","targetRideId":"$it"""" } ?: ""
        setBody("""{"status":"ACCEPTED"$target,"cancelMyConflictingRequests":$cancelMine}""")
    }

    private suspend fun HttpClient.setStatus(caller: String, requestId: String, status: String): HttpResponse =
        put("/requests/$requestId") {
            header(HttpHeaders.Authorization, "Bearer $caller")
            contentType(ContentType.Application.Json)
            setBody("""{"status":"$status"}""")
        }

    private suspend fun HttpClient.undo(driver: String, requestId: String): HttpResponse =
        post("/requests/$requestId/undo-accept") { header(HttpHeaders.Authorization, "Bearer $driver") }

    // The passenger asks a posted ride for a seat and the ride's driver accepts: an ACCEPTED journey via the real API.
    private suspend fun HttpClient.bookedOn(ride: RideDto, driver: String, passenger: String = PASSENGER, intent: String? = null): String {
        val response = postSeat(ride.rideId, caller = passenger, journeyIntentId = intent)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val id = createdRequests(response.bodyAsText()).single().requestId
        assertEquals(HttpStatusCode.OK, accept(driver, id).status)
        return id
    }

    private suspend fun HttpClient.seatRequest(ride: RideDto, intent: String?, passenger: String = PASSENGER): String {
        val response = postSeat(ride.rideId, caller = passenger, journeyIntentId = intent)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return createdRequests(response.bodyAsText()).single().requestId
    }

    private fun conflictOf(requestId: String): ScheduleConflictDetails? = transaction {
        RideRequests.selectAll().where { RideRequests.requestId eq requestId }.first()[RideRequests.conflictDetails]
            ?.let { schedulingJson.decodeFromString<ScheduleConflictDetails>(it) }
    }

    private fun ridesOf(driver: String, departure: Long): Long =
        transaction { Rides.selectAll().where { (Rides.driverId eq driver) and (Rides.departureTime eq departure) }.count() }

    private fun rideRow(rideId: String) = transaction { Rides.selectAll().where { Rides.rideId eq rideId }.first() }

    private suspend fun HttpClient.refusedAsUnavailable(response: HttpResponse) {
        assertEquals(HttpStatusCode.Conflict, response.status)
        val error = response.asError()
        assertEquals(CODE_SCHEDULE_INFEASIBLE, error.code)
        assertEquals(MESSAGE_PASSENGER_UNAVAILABLE, error.error)
        assertNull(error.conflict, "the driver is not told about the passenger's other trips")
    }

    // ================================================================ physical feasibility of an acceptance
    @Test
    fun anAcceptanceTooSoonAfterAnAlreadyAcceptedJourneyIsRefusedAndTheAcceptedOneIsUntouched() = withScheduling(quick()) {
        val booked = bookedOn(rideBetween(DRIVER_A, at(0)), DRIVER_A)               // A -> B, 0..10
        insertAsk("q", Place.A, Place.B, at(24), 10)                                // back at A needs 10 + 5 + 10 = 25
        refusedAsUnavailable(accept(DRIVER_B, "q"))
        assertEquals(RideRequestStatus.PENDING, requestStatus("q"))
        assertEquals(RideRequestStatus.ACCEPTED, requestStatus(booked), "the earlier commitment wins and is untouched")
        assertEquals(0L, ridesOf(DRIVER_B, at(24)), "no ride was created for the refused acceptance")
    }

    @Test
    fun anAcceptanceWithEnoughRepositioningTimeIsAccepted() = withScheduling(quick()) {
        bookedOn(rideBetween(DRIVER_A, at(0)), DRIVER_A)
        insertAsk("q", Place.A, Place.B, at(25), 10)
        assertEquals(HttpStatusCode.OK, accept(DRIVER_B, "q").status)
        assertEquals(RideRequestStatus.ACCEPTED, requestStatus("q"))
        assertEquals(1L, ridesOf(DRIVER_B, at(25)))
    }

    @Test
    fun anAcceptanceThatLeavesNoTimeForALaterAcceptedJourneyIsRefused() = withScheduling(quick()) {
        bookedOn(rideBetween(DRIVER_A, at(100)), DRIVER_A)                          // A -> B at 100
        insertAsk("q85", Place.A, Place.B, at(85), 10)
        insertAsk("q76", Place.A, Place.B, at(76), 10)
        insertAsk("q75", Place.A, Place.B, at(75), 10)                              // 75..85, + 5 + 10 = 100: just fits
        refusedAsUnavailable(accept(DRIVER_B, "q85"))
        refusedAsUnavailable(accept(DRIVER_B, "q76"))
        assertEquals(HttpStatusCode.OK, accept(DRIVER_B, "q75").status)
        // ...and the two that could not coexist with it are now impossible, not left waiting.
        assertEquals(RideRequestStatus.CONFLICTED, requestStatus("q76"))
        assertEquals(RideRequestStatus.CONFLICTED, requestStatus("q85"))
    }

    @Test
    fun theSamePlaceStillNeedsTheTenMinuteBuffer() = withScheduling(quick()) {
        bookedOn(rideBetween(DRIVER_A, at(0)), DRIVER_A)                            // ends at B at 10
        insertAsk("early", Place.B, Place.C, at(19), 20)
        insertAsk("ok", Place.B, Place.C, at(20), 20)
        refusedAsUnavailable(accept(DRIVER_B, "early"))                             // 0 repositioning + 10 buffer = 20
        assertEquals(HttpStatusCode.OK, accept(DRIVER_B, "ok").status)
    }

    @Test
    fun repositioningIsDirectionalNotAssumedSymmetric() = withScheduling(quick(Triple(Place.B, Place.A, 30L))) {
        bookedOn(rideBetween(DRIVER_A, at(0)), DRIVER_A)                            // A -> B 0..10; back to A takes 30 (A -> B only 10)
        insertAsk("q49", Place.A, Place.B, at(49), 10)
        insertAsk("q50", Place.A, Place.B, at(50), 10)
        refusedAsUnavailable(accept(DRIVER_B, "q49"))                               // a symmetric 10 would have allowed 30
        assertEquals(HttpStatusCode.OK, accept(DRIVER_B, "q50").status)
    }

    // ================================================================ pending journeys: conflicted vs untouched
    private suspend fun HttpClient.shiftedAcceptanceScenario(): String {
        // o1 asked for 0..10 but the only ride available leaves at 40: accepting it moves the passenger to 40..50, which
        // the other things they were waiting on were never checked against.
        insertAsk("o1", Place.A, Place.B, at(0), 10)
        insertAsk("o2", Place.C, Place.D, at(60), 20)       // fine after o1 as asked (10 + 20 + 10 = 40), not after 50 (-> 80)
        insertAsk("o3", Place.C, Place.D, at(120), 20)      // fine either way
        val ride = rideBetween(DRIVER_A, at(40))
        assertEquals(HttpStatusCode.OK, accept(DRIVER_A, "o1", ride.rideId).status)
        return ride.rideId
    }

    @Test
    fun aNewlyImpossiblePendingJourneyIsConflictedAndAFeasibleOneStaysPending() = withScheduling(quick()) {
        shiftedAcceptanceScenario()
        assertEquals(RideRequestStatus.ACCEPTED, requestStatus("o1"))
        assertEquals(RideRequestStatus.CONFLICTED, requestStatus("o2"), "not CANCELLED: the system ended it")
        assertEquals(RideRequestStatus.PENDING, requestStatus("o3"))
        val why = assertNotNull(conflictOf("o2"))
        assertEquals(CONFLICT_TOO_SOON_AFTER_PREVIOUS, why.type)
        assertEquals("o1", why.conflictingId)
        assertEquals(at(80), why.earliestFeasibleDeparture, "50 + 20 to reach C + 10 buffer")
        assertNull(conflictOf("o3"))
    }

    @Test
    fun theSurvivorsAreJudgedAgainstTheResultingScheduleNotAgainstTheOnesThatWereDropped() = withScheduling(quick()) {
        insertAsk("o1", Place.A, Place.B, at(0), 10)
        insertAsk("o2", Place.C, Place.D, at(60), 20)       // dropped by the accepted 40..50
        insertAsk("o3", Place.C, Place.D, at(85), 20)       // clashes with o2 (D -> C 20 + 10 after 80) but NOT with 50 + 20 + 10 = 80
        val ride = rideBetween(DRIVER_A, at(40))
        assertEquals(HttpStatusCode.OK, accept(DRIVER_A, "o1", ride.rideId).status)
        assertEquals(RideRequestStatus.CONFLICTED, requestStatus("o2"))
        assertEquals(RideRequestStatus.PENDING, requestStatus("o3"), "o3 only clashed with a journey that no longer exists")
    }

    @Test
    fun aConflictedRequestIsExplainedToItsPassengerButNeverToTheDriver() = withScheduling(quick()) {
        val rc = rideBetween(DRIVER_B, at(60), Place.C, Place.D)
        insertAsk("o1", Place.A, Place.B, at(0), 10)
        val seat = seatRequest(rc, intent = "intent-search-0002")            // a seat request, wanted 60..80: fine beside o1 as asked
        val ride = rideBetween(DRIVER_A, at(40))
        assertEquals(HttpStatusCode.OK, accept(DRIVER_A, "o1", ride.rideId).status)
        assertEquals(RideRequestStatus.CONFLICTED, requestStatus(seat))

        val mine = schedulingJson.decodeFromString<List<RideRequestDto>>(get("/requests/mine") { header(HttpHeaders.Authorization, "Bearer $PASSENGER") }.bodyAsText())
        val theirs = mine.single { it.requestId == seat }
        assertEquals(RideRequestStatus.CONFLICTED, theirs.status)
        assertEquals(CONFLICT_TOO_SOON_AFTER_PREVIOUS, assertNotNull(theirs.conflict).type, "the passenger is told why")

        // The driver of the ride it was aimed at still sees it as history, but nothing about the passenger's other trip.
        val feed = schedulingJson.decodeFromString<List<RideRequestDto>>(get("/requests") { header(HttpHeaders.Authorization, "Bearer $DRIVER_B") }.bodyAsText())
        val seen = feed.single { it.requestId == seat }
        assertEquals(RideRequestStatus.CONFLICTED, seen.status)
        assertNull(seen.conflict)
    }

    @Test
    fun aConflictedStandaloneRequestIsNeverInAnyDriversFeed() = withScheduling(quick()) {
        shiftedAcceptanceScenario()
        for (driver in listOf(DRIVER_A, DRIVER_B, third)) {
            val feed = schedulingJson.decodeFromString<List<RideRequestDto>>(get("/requests") { header(HttpHeaders.Authorization, "Bearer $driver") }.bodyAsText())
            assertTrue(feed.none { it.requestId == "o2" }, "$driver was offered a conflicted request")
            assertTrue(feed.none { it.status == RideRequestStatus.CONFLICTED && it.rideId == null })
        }
    }

    // ================================================================ journey intents
    @Test
    fun acceptingOneAlternativeCancelsTheOthersAndUndoRestoresThemButNeverAConflictedOne() = withScheduling(quick()) {
        val r1 = rideBetween(DRIVER_A, at(40)); val r2 = rideBetween(DRIVER_B, at(100)); val r3 = rideBetween(third, at(300))
        val q1 = seatRequest(r1, search); val q2 = seatRequest(r2, search); val q3 = seatRequest(r3, search)
        assertEquals(HttpStatusCode.OK, accept(DRIVER_A, q1).status)
        assertEquals(RideRequestStatus.ACCEPTED, requestStatus(q1))
        assertEquals(RideRequestStatus.CANCELLED, requestStatus(q2), "an alternative that lost")
        assertEquals(RideRequestStatus.CANCELLED, requestStatus(q3))
        assertNull(conflictOf(q2), "an alternative is cancelled, not conflicted")

        assertEquals(HttpStatusCode.OK, undo(DRIVER_A, q1).status)
        assertEquals(RideRequestStatus.PENDING, requestStatus(q1))
        assertEquals(RideRequestStatus.PENDING, requestStatus(q2), "Undo restores what that accept cancelled")
        assertEquals(RideRequestStatus.PENDING, requestStatus(q3))
    }

    @Test
    fun undoRestoresAlternativesButNotAConflictedRequestFromTheSameAccept() = withScheduling(quick()) {
        val ride = shiftedAcceptanceScenario()                                    // o1 accepted; o2 CONFLICTED; o3 PENDING
        val response = undo(DRIVER_A, "o1")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals(RideRequestStatus.PENDING, requestStatus("o1"))
        assertEquals(RideRequestStatus.CONFLICTED, requestStatus("o2"), "terminal: Undo must not resurrect it")
        assertEquals(RideRequestStatus.PENDING, requestStatus("o3"))
        assertEquals(rideRow(ride)[Rides.seatsTotal], rideRow(ride)[Rides.seatsAvailable], "the seat came back")
    }

    @Test
    fun undoDoesNotRestoreAnAlternativeThatWasCancelledForAnotherReason() = withScheduling(quick()) {
        val r1 = rideBetween(DRIVER_A, at(40)); val r2 = rideBetween(DRIVER_B, at(100))
        val q1 = seatRequest(r1, search); val q2 = seatRequest(r2, search)
        assertEquals(HttpStatusCode.OK, setStatus(PASSENGER, q2, RideRequestStatus.CANCELLED).status)   // the passenger withdrew it
        Thread.sleep(5)
        assertEquals(HttpStatusCode.OK, accept(DRIVER_A, q1).status)
        assertEquals(HttpStatusCode.OK, undo(DRIVER_A, q1).status)
        assertEquals(RideRequestStatus.CANCELLED, requestStatus(q2))
    }

    @Test
    fun undoRestoresOnlyWhatThatAcceptCancelledEvenIfSomethingElseWasCancelledAtTheSameInstant() = withScheduling(quick()) {
        val r1 = rideBetween(DRIVER_A, at(40)); val r2 = rideBetween(DRIVER_B, at(100))
        val q1 = seatRequest(r1, search)
        val unrelated = seatRequest(r2, "intent-search-0002")                   // a different journey that still fits
        assertEquals(HttpStatusCode.OK, accept(DRIVER_A, q1).status)
        val acceptedAt = transaction { RideRequests.selectAll().where { RideRequests.requestId eq q1 }.first()[RideRequests.respondedAt]!! }
        // It was cancelled for its own reasons, but at exactly the same millisecond the accept happened.
        transaction { RideRequests.update({ RideRequests.requestId eq unrelated }) { it[status] = RideRequestStatus.CANCELLED; it[respondedAt] = acceptedAt } }
        assertEquals(HttpStatusCode.OK, undo(DRIVER_A, q1).status)
        assertEquals(RideRequestStatus.CANCELLED, requestStatus(unrelated), "not an alternative of the accepted journey, and not the driver's own")
    }

    @Test
    fun undoDoesNotRestoreAnAlternativeThePassengerHasSinceRequestedAgain() = withScheduling(quick()) {
        val r1 = rideBetween(DRIVER_A, at(40)); val r2 = rideBetween(DRIVER_B, at(100))
        val q1 = seatRequest(r1, search); val q2 = seatRequest(r2, search)
        assertEquals(HttpStatusCode.OK, accept(DRIVER_A, q1).status)
        assertEquals(RideRequestStatus.CANCELLED, requestStatus(q2))
        val again = seatRequest(r2, search)                                          // asked for the same ride again
        assertEquals(HttpStatusCode.OK, undo(DRIVER_A, q1).status)
        assertEquals(RideRequestStatus.CANCELLED, requestStatus(q2), "restoring it would create the duplicate refused everywhere else")
        assertEquals(RideRequestStatus.PENDING, requestStatus(again))
    }

    @Test
    fun aDifferentIntentIsNotCancelledMerelyBecauseTheRouteAndTimeLookSimilar() = withScheduling(quick()) {
        val r1 = rideBetween(DRIVER_A, at(0)); val r2 = rideBetween(DRIVER_B, at(25))   // 25 apart: both real journeys
        val q1 = seatRequest(r1, "intent-search-0001"); val q2 = seatRequest(r2, "intent-search-0002")
        assertEquals(HttpStatusCode.OK, accept(DRIVER_A, q1).status)
        assertEquals(RideRequestStatus.PENDING, requestStatus(q2), "a different journey that still fits is untouched")
    }

    @Test
    fun aRequestWithNoIntentIsNeverTreatedAsAnExplicitAlternative() = withScheduling(quick()) {
        val r1 = rideBetween(DRIVER_A, at(0)); val r2 = rideBetween(DRIVER_B, at(25))
        val q1 = seatRequest(r1, null); val q2 = seatRequest(r2, null)
        assertEquals(HttpStatusCode.OK, accept(DRIVER_A, q1).status)
        assertEquals(RideRequestStatus.PENDING, requestStatus(q2))
    }

    // ================================================================ CONFLICTED is terminal
    @Test
    fun aConflictedRequestCannotBeActedOnAndStaysConflictedWhateverHappensToTheTripThatCausedIt() = withScheduling(quick()) {
        val rideId = shiftedAcceptanceScenario()
        assertEquals(RideRequestStatus.CONFLICTED, requestStatus("o2"))
        for (status in listOf(RideRequestStatus.PENDING, RideRequestStatus.ACCEPTED, RideRequestStatus.CANCELLED, RideRequestStatus.COMPLETED)) {
            assertEquals(HttpStatusCode.Conflict, setStatus(PASSENGER, "o2", status).status, status)
        }
        assertEquals(HttpStatusCode.Conflict, accept(DRIVER_B, "o2").status)
        // The accepted trip that caused it is cancelled: the schedule is free again, but nothing is silently reactivated.
        assertEquals(HttpStatusCode.OK, setStatus(PASSENGER, "o1", RideRequestStatus.CANCELLED).status)
        put("/rides/$rideId") {
            header(HttpHeaders.Authorization, "Bearer $DRIVER_A"); contentType(ContentType.Application.Json); setBody("""{"status":"CANCELLED"}""")
        }
        assertEquals(RideRequestStatus.CONFLICTED, requestStatus("o2"))
        // The passenger can simply ask again, and it is not a duplicate of the conflicted one.
        assertEquals(HttpStatusCode.OK, openBetween(Place.C, Place.D, at(60)).status)
    }

    // ================================================================ open request -> a ride
    @Test
    fun aNewRideMadeFromARequestMustFitTheDriversOwnSchedule() = withScheduling(quick()) {
        rideBetween(DRIVER_B, at(100))                                              // DRIVER_B drives A -> B at 100..110
        insertAsk("overlap", Place.A, Place.B, at(105), 10)
        insertAsk("fits", Place.A, Place.B, at(125), 10)                            // 110 + 5 + 10 = 125
        val refused = accept(DRIVER_B, "overlap")
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals(CODE_SCHEDULE_INFEASIBLE, refused.asError().code)
        assertNotNull(refused.asError().conflict, "it is the driver's OWN schedule, so the details are theirs to see")
        assertEquals(RideRequestStatus.PENDING, requestStatus("overlap"))
        assertEquals(0L, ridesOf(DRIVER_B, at(105)))
        assertEquals(HttpStatusCode.OK, accept(DRIVER_B, "fits").status)
    }

    @Test
    fun aPassengerJoiningARideThatAlreadyExistsDoesNotCountTheDriversJourneyTwice() = withScheduling(quick()) {
        val ride = rideBetween(DRIVER_B, at(100))
        insertAsk("p2", Place.A, Place.B, at(100), 10, passenger = third)
        val response = accept(DRIVER_B, "p2", ride.rideId)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals(ride.seatsTotal - 1, rideRow(ride.rideId)[Rides.seatsAvailable])
    }

    @Test
    fun theDriversOwnPendingRequestStillNeedsTheirConfirmationAndIsCancelledNotConflicted() = withScheduling(quick()) {
        insertAsk("mine", Place.C, Place.D, at(120), 20, passenger = DRIVER_B)       // DRIVER_B is waiting for a ride at 120
        insertAsk("theirs", Place.A, Place.B, at(105), 10)                           // accepting makes B -> C (20) + 10 after 115 = 145 > 120
        val prompt = accept(DRIVER_B, "theirs")
        assertEquals(HttpStatusCode.Conflict, prompt.status)
        assertEquals(CONFLICT_OWN_PENDING_REQUEST, prompt.asError().code)
        assertEquals(RideRequestStatus.PENDING, requestStatus("mine"))
        assertEquals(HttpStatusCode.OK, accept(DRIVER_B, "theirs", cancelMine = true).status)
        assertEquals(RideRequestStatus.CANCELLED, requestStatus("mine"), "their own choice: cancelled, restorable by Undo")
        assertEquals(HttpStatusCode.OK, undo(DRIVER_B, "theirs").status)
        assertEquals(RideRequestStatus.PENDING, requestStatus("mine"))
    }

    // ================================================================ route compatibility with an EXISTING ride
    @Test
    fun anOpenRequestCanJoinARideOnlyOnExactlyItsCanonicalRoute() = withScheduling(quick()) {
        val ride = rideBetween(DRIVER_A, at(100))                                   // A -> B
        insertAsk("wrongOrigin", Place.E, Place.B, at(100), 20)
        insertAsk("wrongDestination", Place.A, Place.D, at(100), 20)
        insertAsk("reversed", Place.B, Place.A, at(100), 5)
        insertAsk("exact", Place.A, Place.B, at(100), 10)
        for (id in listOf("wrongOrigin", "wrongDestination", "reversed")) {
            val refused = accept(DRIVER_A, id, ride.rideId)
            assertEquals(HttpStatusCode.BadRequest, refused.status, id)
            assertTrue(refused.bodyAsText().contains("goes from"), refused.bodyAsText())
            assertEquals(RideRequestStatus.PENDING, requestStatus(id))
        }
        assertEquals(ride.seatsTotal, rideRow(ride.rideId)[Rides.seatsAvailable], "a refused join takes no seat")
        assertEquals(HttpStatusCode.OK, accept(DRIVER_A, "exact", ride.rideId).status)
    }

    @Test
    fun aRidesIntermediateStopsAreNotPickupPointsSoNoSubRouteIsEverMatched() = withScheduling(quick()) {
        val c = coords(Place.C)
        val response = post("/rides") {
            header(HttpHeaders.Authorization, "Bearer $DRIVER_A"); contentType(ContentType.Application.Json)
            val f = coords(Place.A); val t = coords(Place.B)
            setBody("""{"originLat":${f.first},"originLng":${f.second},"destinationLat":${t.first},"destinationLng":${t.second},
                "distanceMeters":4000.0,"estimatedDurationSeconds":600,"departureTime":${at(100)},"seatsTotal":3,
                "stops":[{"lat":${c.first},"lng":${c.second},"label":"Glen Waverley"}],"repeatDaysOfWeek":[],"repeatWeeks":1}""")
        }
        val ride = schedulingJson.decodeFromString<RideDto>(response.bodyAsText())
        assertEquals(1, ride.stops.size)
        insertAsk("fromTheStop", Place.C, Place.B, at(100), 20)                       // would be a sub-route of A -> C -> B
        assertEquals(HttpStatusCode.BadRequest, accept(DRIVER_A, "fromTheStop", ride.rideId).status)
    }

    @Test
    fun aRequestThatCannotBeReducedToApprovedPointsCanOnlyBeAcceptedByCreatingANewRide() = withScheduling(quick()) {
        val ride = rideBetween(DRIVER_A, at(100))
        // An old row from a since-retired hub: not an approved point, so it can't be verified against any ride's route.
        insertOpenAsk("legacy", desiredDeparture = at(100), createdAt = System.currentTimeMillis(), from = -37.9 to 145.1)
        assertEquals(HttpStatusCode.BadRequest, accept(DRIVER_A, "legacy", ride.rideId).status)
        assertEquals(RideRequestStatus.PENDING, requestStatus("legacy"))
        assertEquals(HttpStatusCode.OK, accept(DRIVER_B, "legacy").status)           // a new ride made from the request itself
    }

    // ================================================================ privacy and ownership
    @Test
    fun youCannotAcceptYourOwnRequest() = withScheduling(quick()) {
        insertAsk("mine", Place.A, Place.B, at(100), 10)
        assertEquals(HttpStatusCode.Forbidden, accept(PASSENGER, "mine").status)
        assertEquals(RideRequestStatus.PENDING, requestStatus("mine"))
    }

    @Test
    fun aRequestWhoseTimeHasPassedIsNeverAccepted() = withScheduling(quick()) {
        insertOpenAsk("stale", desiredDeparture = System.currentTimeMillis() - 60_000, createdAt = System.currentTimeMillis() - DAY)
        val response = accept(DRIVER_A, "stale")
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals(RideRequestStatus.EXPIRED, requestStatus("stale"))
    }

    // ================================================================ concurrency
    // (The two-drivers / two-different-requests race lives in AcceptanceRaceSemanticsTest, where the final state is checked
    // against an independent statement of the rule rather than a fixed status per race order.)

    @Test
    fun acceptanceRacingPassengerCancellationLeavesACoherentState() {
        repeat(6) {
            withScheduling(quick()) {
                val ride = rideBetween(DRIVER_A, at(0))
                val q = seatRequest(ride, null)
                val answers = coroutineScope {
                    listOf(async(Dispatchers.Default) { accept(DRIVER_A, q).status }, async(Dispatchers.Default) { setStatus(PASSENGER, q, RideRequestStatus.CANCELLED).status }).awaitAll()
                }
                assertEquals(HttpStatusCode.OK, answers[1], "cancelling always works, before or after the accept: $answers")
                assertEquals(RideRequestStatus.CANCELLED, requestStatus(q))
                assertEquals(ride.seatsTotal, rideRow(ride.rideId)[Rides.seatsAvailable], "the seat is back (or was never taken)")
                assertEquals(RideStatus.OPEN, rideStatus(ride.rideId), "an emptied ride reopens")
            }
        }
    }

    @Test
    fun acceptanceRacingANewRequestLeavesAFeasibleSchedule() {
        repeat(6) {
            withScheduling(quick()) {
                insertAsk("o1", Place.A, Place.B, at(0), 10)
                val ride = rideBetween(DRIVER_A, at(40))
                val answers = coroutineScope {
                    listOf(
                        async(Dispatchers.Default) { accept(DRIVER_A, "o1", ride.rideId).status },
                        async(Dispatchers.Default) { openBetween(Place.C, Place.D, at(55)).status }   // fine beside o1 as asked, not beside 40..50
                    ).awaitAll()
                }
                assertEquals(HttpStatusCode.OK, answers[0], "$answers")
                assertEquals(RideRequestStatus.ACCEPTED, requestStatus("o1"))
                val created = transaction {
                    RideRequests.selectAll().where { (RideRequests.passengerId eq PASSENGER) and (RideRequests.desiredDepartureTime eq at(55)) }
                        .map { it[RideRequests.status] }
                }
                // Either the new request was refused outright, or it was made first and then invalidated by the accept.
                if (answers[1] == HttpStatusCode.OK) assertEquals(listOf(RideRequestStatus.CONFLICTED), created, "$answers")
                else { assertEquals(HttpStatusCode.Conflict, answers[1]); assertTrue(created.isEmpty()) }
            }
        }
    }

    @Test
    fun twoAlternativesAcceptedAtOnceLeaveExactlyOneWinner() {
        repeat(6) {
            withScheduling(quick()) {
                val r1 = rideBetween(DRIVER_A, at(0)); val r2 = rideBetween(DRIVER_B, at(40))
                val q1 = seatRequest(r1, search); val q2 = seatRequest(r2, search)
                val answers = coroutineScope {
                    listOf(async(Dispatchers.Default) { accept(DRIVER_A, q1).status }, async(Dispatchers.Default) { accept(DRIVER_B, q2).status }).awaitAll()
                }
                assertEquals(1, answers.count { it == HttpStatusCode.OK }, "$answers")
                val statuses = listOf(requestStatus(q1), requestStatus(q2)).sorted()
                assertEquals(listOf(RideRequestStatus.ACCEPTED, RideRequestStatus.CANCELLED), statuses, "one wins, the other lost its place")
                val taken = listOf(r1, r2).sumOf { r -> r.seatsTotal - rideRow(r.rideId)[Rides.seatsAvailable] }
                assertEquals(1, taken, "exactly one seat was taken in total")
            }
        }
    }

    @Test
    fun acceptanceRacingExpiryNeverAcceptsAnExpiredRequest() {
        repeat(4) {
            withScheduling(quick()) {
                val deadline = System.currentTimeMillis() + 600
                insertAsk("soon", Place.A, Place.B, deadline, 10)
                while (System.currentTimeMillis() < deadline - 150) Thread.sleep(10)
                val answers = coroutineScope {
                    (0 until 4).map { async(Dispatchers.Default) { accept(DRIVER_B, "soon").status } }.awaitAll()
                }
                val row = transaction { RideRequests.selectAll().where { RideRequests.requestId eq "soon" }.first() }
                when (row[RideRequests.status]) {
                    RideRequestStatus.ACCEPTED -> assertTrue(row[RideRequests.respondedAt]!! <= deadline, "accepted only before its deadline")
                    else -> assertEquals(RideRequestStatus.EXPIRED, row[RideRequests.status], "$answers")
                }
                assertTrue(answers.count { it == HttpStatusCode.OK } <= 1, "$answers")
            }
        }
    }
}
