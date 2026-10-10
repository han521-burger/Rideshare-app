package com.example.ds04_monash_rideshare_application.backend

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
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
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// What makes seat requests ALTERNATIVES of each other: an explicit journeyIntentId plus the same route. Never route
// and time alone. (The timeline arithmetic itself is in PassengerFeasibilityTest / ScheduleTimelineTest.)
class JourneyIntentTest {
    private val base = futureDeparture(10)
    private fun at(minutes: Long) = base + minutes * MINUTE
    private val third = OTHER_PASSENGER // a seeded account with a vehicle, used as a third driver

    // A quick trip: A -> B 10 minutes, B -> A 5. With the 10-minute buffer a repeat is physically possible 25 min later.
    private fun quick() = ScenarioTravelTimes(20, Triple(Place.A, Place.B, 10L), Triple(Place.B, Place.A, 5L))

    private fun storedIntent(requestId: String): String? =
        transaction { RideRequests.selectAll().where { RideRequests.requestId eq requestId }.first()[RideRequests.journeyIntentId] }

    private fun statusOf(requestId: String): String =
        transaction { RideRequests.selectAll().where { RideRequests.requestId eq requestId }.first()[RideRequests.status] }

    private suspend fun HttpClient.seat(ride: RideDto, intent: String?, key: String? = null): List<RideRequestDto> {
        val response = postSeat(ride.rideId, clientRequestId = key, journeyIntentId = intent)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return createdRequests(response.bodyAsText())
    }

    // ---------------------------------------------------------------- 1 / 2
    @Test
    fun sameRouteAndSameIntentCollapseIntoOneAlternativeSlot() = withScheduling(quick()) {
        val rides = listOf(rideBetween(DRIVER_A, at(0)), rideBetween(DRIVER_B, at(5)), rideBetween(third, at(10)))
        val requests = rides.map { seat(it, "intent-search-01").single() }
        assertEquals(3, requests.map { it.requestId }.toSet().size)
        assertTrue(requests.all { it.journeyIntentId == "intent-search-01" }, "the response reports the intent")
        assertTrue(requests.all { storedIntent(it.requestId) == "intent-search-01" }, "and it is persisted")
        // One slot: from its latest arrival (10 + 10 = 20) the next pickup at B needs just the buffer: 30.
        openBetween(Place.B, Place.C, at(29)).scheduleConflict(CONFLICT_TOO_SOON_AFTER_PREVIOUS)
        assertEquals(HttpStatusCode.OK, openBetween(Place.B, Place.C, at(30)).status)
    }

    @Test
    fun sameRouteButDifferentIntentsAreTwoSeparateJourneys() = withScheduling(quick()) {
        val first = rideBetween(DRIVER_A, at(0)); val second = rideBetween(DRIVER_B, at(5))
        seat(first, "intent-search-01")
        val refused = postSeat(second.rideId, journeyIntentId = "intent-search-02").scheduleConflict(CONFLICT_TOO_SOON_AFTER_PREVIOUS)
        assertEquals(at(25), refused.earliestFeasibleDeparture)
        assertEquals(1L, requestRows())
    }

    // ---------------------------------------------------------------- 3 / 4
    @Test
    fun twentyFiveMinutesApartWithDifferentIntentsAreTwoRealJourneysAndBothAreAllowedWhenFeasible() = withScheduling(quick()) {
        val first = rideBetween(DRIVER_A, at(0)); val second = rideBetween(DRIVER_B, at(25))
        seat(first, "intent-search-01"); seat(second, "intent-search-02")
        assertEquals(2L, requestRows())
        // Physics still applies to a separate journey: one minute sooner would not be possible.
        val tooClose = rideBetween(third, at(24))
        postSeat(tooClose.rideId, journeyIntentId = "intent-search-03").scheduleConflict()
        assertEquals(2L, requestRows())
    }

    @Test
    fun twentyFiveMinutesApartWithTheSameIntentBehaveAsAlternatives() = withScheduling(quick()) {
        val first = rideBetween(DRIVER_A, at(0)); val tooCloseToBeASecondJourney = rideBetween(DRIVER_B, at(24))
        seat(first, "intent-search-01")
        // 24 minutes later is physically impossible as a second journey, but under one intent it is just another option.
        seat(tooCloseToBeASecondJourney, "intent-search-01")
        assertEquals(2L, requestRows())
    }

    // ---------------------------------------------------------------- 5
    @Test
    fun sameRouteWithinFortyFiveMinutesButNoIntentIsNotGrouped() = withScheduling(quick()) {
        val first = rideBetween(DRIVER_A, at(0)); val second = rideBetween(DRIVER_B, at(5)); val onThird = rideBetween(third, at(10))
        val a = seat(first, null).single()
        assertNull(a.journeyIntentId); assertNull(storedIntent(a.requestId))
        postSeat(second.rideId).scheduleConflict(CONFLICT_TOO_SOON_AFTER_PREVIOUS)
        // A declared intent on one side and none on the other is not a group either.
        postSeat(onThird.rideId, journeyIntentId = "intent-search-01").scheduleConflict(CONFLICT_TOO_SOON_AFTER_PREVIOUS)
        assertEquals(1L, requestRows())
    }

    // ---------------------------------------------------------------- 6
    @Test
    fun theSameIntentOnADifferentRouteIsRefusedAndNeverMergedIntoTheSlot() = withScheduling(quick()) {
        val onAB = rideBetween(DRIVER_A, at(0)); val onBA = rideBetween(DRIVER_B, at(300), Place.B, Place.A)
        seat(onAB, "intent-search-01")
        val refused = postSeat(onBA.rideId, journeyIntentId = "intent-search-01")
        assertEquals(HttpStatusCode.BadRequest, refused.status, refused.bodyAsText())
        assertTrue(refused.bodyAsText().contains("different route"))
        assertEquals(1L, requestRows(), "nothing was created")
        // The very same ride under a fresh intent is a normal, separate journey.
        seat(onBA, "intent-search-02")
        assertEquals(2L, requestRows())
    }

    // ---------------------------------------------------------------- 9 (duplicates and clientRequestId unchanged)
    @Test
    fun duplicateDetectionStillWorksWhenAnIntentIsSent() = withScheduling(quick()) {
        val ride = rideBetween(DRIVER_A, at(0))
        val first = seat(ride, "intent-search-01").single()
        val again = postSeat(ride.rideId, journeyIntentId = "intent-search-01")
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertEquals(CONFLICT_DUPLICATE_OPEN_REQUEST, again.asError().code)
        assertEquals(first.requestId, again.asError().existingRequestId)
        // ...and a different search asking for the same ride is the same duplicate.
        assertEquals(CONFLICT_DUPLICATE_OPEN_REQUEST, postSeat(ride.rideId, journeyIntentId = "intent-search-02").asError().code)
        assertEquals(1L, requestRows())
    }

    @Test
    fun aRetryWithTheSameClientRequestIdAndIntentReturnsTheOriginal() = withScheduling(quick()) {
        val ride = rideBetween(DRIVER_A, at(0))
        val first = seat(ride, "intent-search-01", key = "submission-key-01").single()
        val retry = seat(ride, "intent-search-01", key = "submission-key-01").single()
        assertEquals(first.requestId, retry.requestId)
        assertEquals(1L, requestRows())
    }

    @Test
    fun theSameClientRequestIdWithADifferentIntentIsADifferentRequestAndIsRefused() = withScheduling(quick()) {
        val ride = rideBetween(DRIVER_A, at(0))
        seat(ride, "intent-search-01", key = "submission-key-01")
        val reused = postSeat(ride.rideId, clientRequestId = "submission-key-01", journeyIntentId = "intent-search-02")
        assertEquals(HttpStatusCode.Conflict, reused.status)
        assertEquals(CONFLICT_IDEMPOTENCY_KEY_REUSED, reused.asError().code)
        assertEquals(1L, requestRows())
    }

    @Test
    fun anIntentThatIsNotAnIdIsRefusedAndIntentIsOptional() = withScheduling(quick()) {
        val ride = rideBetween(DRIVER_A, at(0))
        assertEquals(HttpStatusCode.BadRequest, postSeat(ride.rideId, journeyIntentId = "no spaces allowed!").status)
        assertEquals(HttpStatusCode.BadRequest, postSeat(ride.rideId, journeyIntentId = "short").status)
        assertEquals(0L, requestRows())
        seat(ride, null) // an older app sends none: fine
        assertEquals(1L, requestRows())
    }

    @Test
    fun anIntentAppliesToThePickedRideNotToTheOtherDaysOfARepeatingSeries() = withScheduling(quick()) {
        val series = post("/rides") {
            header(HttpHeaders.Authorization, "Bearer $DRIVER_A")
            contentType(ContentType.Application.Json)
            val f = coords(Place.A); val t = coords(Place.B)
            setBody("""{"originLat":${f.first},"originLng":${f.second},"destinationLat":${t.first},"destinationLng":${t.second},
                "distanceMeters":4000.0,"estimatedDurationSeconds":600,"departureTime":${at(0)},"seatsTotal":3,
                "repeatDaysOfWeek":[1,2,3,4,5,6,7],"repeatWeeks":1}""")
        }
        assertEquals(HttpStatusCode.OK, series.status, series.bodyAsText())
        val picked = schedulingJson.decodeFromString<RideDto>(series.bodyAsText())
        val response = post("/rides/${picked.rideId}/requests") {
            header(HttpHeaders.Authorization, "Bearer $PASSENGER")
            contentType(ContentType.Application.Json)
            setBody("""{"seatsRequested":1,"applyToRecurringGroup":true,"journeyIntentId":"intent-search-01"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val created = createdRequests(response.bodyAsText())
        assertTrue(created.size > 1, "the series fanned out")
        val forPicked = created.single { it.rideId == picked.rideId }
        assertEquals("intent-search-01", storedIntent(forPicked.requestId))
        assertTrue((created - forPicked).all { storedIntent(it.requestId) == null }, "other days are their own journeys")
    }

    // ---------------------------------------------------------------- 10 (concurrency)
    @Test
    fun simultaneousRequestsUnderDifferentIntentsLeaveAPhysicallyFeasibleTimeline() {
        repeat(4) {
            // A -> B 30, B -> A 5, buffer 10: two same-route journeys need departures at least 45 minutes apart.
            // 0 & 20 clash, 20 & 45 clash, 0 & 45 are fine - so exactly the surviving SET is what matters.
            withScheduling(ScenarioTravelTimes(20, Triple(Place.A, Place.B, 30L), Triple(Place.B, Place.A, 5L))) {
                val rides = listOf(rideBetween(DRIVER_A, at(0)), rideBetween(DRIVER_B, at(20)), rideBetween(third, at(45)))
                val answers = coroutineScope {
                    rides.mapIndexed { i, ride ->
                        async(Dispatchers.Default) { postSeat(ride.rideId, journeyIntentId = "intent-concurrent-$i").status }
                    }.awaitAll()
                }
                assertTrue(answers.all { it == HttpStatusCode.OK || it == HttpStatusCode.Conflict }, "$answers")
                // (Only this scenario's requests: the demo passenger also has older seeded ones.)
                val departures = transaction {
                    RideRequests.selectAll().where { RideRequests.passengerId eq PASSENGER }
                        .map { row -> row[RideRequests.rideId]?.let { id -> Rides.selectAll().where { Rides.rideId eq id }.first()[Rides.departureTime] } }
                }.filterNotNull().filter { it >= at(0) }.sorted()
                assertTrue(departures.isNotEmpty(), "at least one request survives")
                assertEquals(answers.count { it == HttpStatusCode.OK }, departures.size, "every 200 is a stored request and nothing else is")
                departures.zipWithNext { a, b -> assertTrue(b - a >= 45 * MINUTE, "survivors ${departures.map { (it - base) / MINUTE }} are physically impossible") }
            }
        }
    }

    @Test
    fun simultaneousRequestsUnderOneIntentAreAllAlternativesAndAllSucceed() = withScheduling(ScenarioTravelTimes(20, Triple(Place.A, Place.B, 30L), Triple(Place.B, Place.A, 5L))) {
        val rides = listOf(rideBetween(DRIVER_A, at(0)), rideBetween(DRIVER_B, at(20)), rideBetween(third, at(45)))
        val answers = coroutineScope {
            rides.map { ride -> async(Dispatchers.Default) { postSeat(ride.rideId, journeyIntentId = "intent-concurrent-x").status } }.awaitAll()
        }
        assertEquals(listOf(HttpStatusCode.OK, HttpStatusCode.OK, HttpStatusCode.OK), answers)
        assertEquals(3L, requestRows())
    }

    // ---------------------------------------------------------------- acceptance uses the explicit group
    @Test
    fun acceptingOneAlternativeCancelsTheOthersOfThatIntentAndUndoRestoresThem() = withScheduling(quick()) {
        // 100 minutes apart: far outside the old time-overlap check, so only the explicit intent can tie them together.
        val first = rideBetween(DRIVER_A, at(0)); val later = rideBetween(DRIVER_B, at(100)); val other = rideBetween(third, at(300))
        val a = seat(first, "intent-search-01").single()
        val b = seat(later, "intent-search-01").single()
        val unrelated = seat(other, "intent-search-02").single()   // another intended journey, far away in time

        assertEquals(HttpStatusCode.OK, respond(DRIVER_A, a.requestId, RideRequestStatus.ACCEPTED).status)
        assertEquals(RideRequestStatus.ACCEPTED, statusOf(a.requestId))
        assertEquals(RideRequestStatus.CANCELLED, statusOf(b.requestId), "the other option for the same intended journey is withdrawn")
        assertEquals(RideRequestStatus.PENDING, statusOf(unrelated.requestId), "a different journey is untouched")

        val undo = post("/requests/${a.requestId}/undo-accept") { header(HttpHeaders.Authorization, "Bearer $DRIVER_A") }
        assertEquals(HttpStatusCode.OK, undo.status, undo.bodyAsText())
        assertEquals(RideRequestStatus.PENDING, statusOf(a.requestId))
        assertEquals(RideRequestStatus.PENDING, statusOf(b.requestId), "Undo restores it")
        assertEquals(RideRequestStatus.PENDING, statusOf(unrelated.requestId))
    }

    @Test
    fun acceptingARequestWithNoIntentLeavesOtherPendingRequestsToTheOldTimeOverlapRule() = withScheduling(quick()) {
        val first = rideBetween(DRIVER_A, at(0)); val later = rideBetween(DRIVER_B, at(100))
        val a = seat(first, null).single(); val b = seat(later, null).single()
        assertEquals(HttpStatusCode.OK, respond(DRIVER_A, a.requestId, RideRequestStatus.ACCEPTED).status)
        assertEquals(RideRequestStatus.PENDING, statusOf(b.requestId), "no declared group, and not overlapping in time: untouched")
    }
}
