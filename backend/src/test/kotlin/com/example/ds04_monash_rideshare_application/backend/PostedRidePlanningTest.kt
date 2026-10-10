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
import kotlinx.serialization.decodeFromString
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Posted rides plan from the backend matrix, not from MapKit; seat requests on them are judged by that planned
// interval; and several seat requests that declare the same journey intent are alternatives, not several trips.
class PostedRidePlanningTest {
    private val base = futureDeparture(10)
    private fun at(minutes: Long) = base + minutes * MINUTE
    private val third = "fake-user-001" // a seeded account with a vehicle, used as a third driver

    // A -> B 30 minutes, B -> A 5 minutes (so a MapKit-sized guess would clearly differ), everything else 20.
    private fun matrix(vararg pairs: Triple<String, String, Long>) =
        ScenarioTravelTimes(20, Triple(Place.A, Place.B, 30L), Triple(Place.B, Place.A, 5L), *pairs)

    private suspend fun HttpClient.rawRide(caller: String, departure: Long, from: String = Place.A, to: String = Place.B, mapKitSeconds: Long = 600): HttpResponse =
        post("/rides") {
            header(HttpHeaders.Authorization, "Bearer $caller")
            contentType(ContentType.Application.Json)
            val f = coords(from); val t = coords(to)
            setBody("""{"originLat":${f.first},"originLng":${f.second},"destinationLat":${t.first},"destinationLng":${t.second},
                "distanceMeters":4000.0,"estimatedDurationSeconds":$mapKitSeconds,"departureTime":$departure,"seatsTotal":3,
                "repeatDaysOfWeek":[],"repeatWeeks":1}""")
        }

    private suspend fun HttpClient.ride(caller: String, departure: Long, from: String = Place.A, to: String = Place.B, mapKitSeconds: Long = 600): RideDto {
        val response = rawRide(caller, departure, from, to, mapKitSeconds)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return schedulingJson.decodeFromString(response.bodyAsText())
    }

    private val search = "intent-search-0001" // one ride search = one journey intent

    private suspend fun HttpClient.seatIds(rideId: String, caller: String = PASSENGER, intent: String? = null): List<String>? {
        val response = postSeat(rideId, caller = caller, journeyIntentId = intent)
        return if (response.status == HttpStatusCode.OK) createdRequests(response.bodyAsText()).map { it.requestId } else null
    }

    // ------------------------------------------------------------ planning fields on a posted ride
    @Test
    fun aPostedRideStoresItsPlanningFieldsAndKeepsTheMapKitEstimateSeparate() = withScheduling(matrix()) {
        val ride = ride(DRIVER_A, at(0), mapKitSeconds = 600)
        assertEquals(Place.A, ride.originPointId); assertEquals(Place.B, ride.destinationPointId)
        assertEquals(30 * 60L, ride.plannedDurationSeconds, "scheduling duration is the matrix value")
        assertEquals(at(0) + 30 * MINUTE, ride.plannedArrivalTime)
        assertEquals(600L, ride.estimatedDurationSeconds, "the MapKit display estimate is stored untouched")
        // Reads back the same.
        val read = get("/rides/${ride.rideId}") { header(HttpHeaders.Authorization, "Bearer $DRIVER_A") }
        val dto = schedulingJson.decodeFromString<RideDto>(read.bodyAsText())
        assertEquals(ride.plannedArrivalTime, dto.plannedArrivalTime)
        assertEquals(600L, dto.estimatedDurationSeconds)
    }

    @Test
    fun aPostedRideIsRefusedRecoverablyWhenTheMatrixHasNoEntryAndNeverFallsBackToMapKit() {
        withScheduling(FakeTravelTimeProvider(mapOf((Place.B to Place.A) to 600L))) {
            val refused = rawRide(DRIVER_A, at(0), Place.A, Place.B, mapKitSeconds = 900)
            assertEquals(HttpStatusCode.ServiceUnavailable, refused.status)
            assertEquals(CODE_TRAVEL_TIME_UNAVAILABLE, refused.asError().code)
            assertEquals(0L, transaction { Rides.selectAll().where { Rides.driverId eq DRIVER_A and (Rides.departureTime eq at(0)) }.count() })
        }
    }

    @Test
    fun editingADepartureKeepsThePlannedArrivalInStep() = withScheduling(matrix()) {
        val ride = ride(DRIVER_A, at(0))
        val moved = put("/rides/${ride.rideId}") {
            header(HttpHeaders.Authorization, "Bearer $DRIVER_A")
            contentType(ContentType.Application.Json)
            setBody("""{"departureTime":${at(120)}}""")
        }
        assertEquals(HttpStatusCode.OK, moved.status, moved.bodyAsText())
        val dto = schedulingJson.decodeFromString<RideDto>(moved.bodyAsText())
        assertEquals(at(120), dto.departureTime)
        assertEquals(at(120) + 30 * MINUTE, dto.plannedArrivalTime)
        assertEquals(30 * 60L, dto.plannedDurationSeconds)
    }

    @Test
    fun anUnapprovedRouteIsStillRefusedBeforeAnyPlanning() = withScheduling(matrix()) {
        val refused = rawRide(DRIVER_A, at(0), Place.A, Place.E) // hub to hub: no campus end
        assertEquals(HttpStatusCode.BadRequest, refused.status)
    }

    // ------------------------------------------------------------ MapKit vs planned
    @Test
    fun scheduleFeasibilityUsesThePlannedIntervalNotMapKitsEstimate() = withScheduling(matrix()) {
        // MapKit says 10 minutes; the matrix says 30. Passenger requests the seat, then a trip starting at C.
        val ride = ride(DRIVER_A, at(0), mapKitSeconds = 600)
        assertNotNull(seatIds(ride.rideId))
        // Planned: arrive B at 30, B -> C is 20 min, + 10 buffer = 60. (A 10-minute MapKit trip would allow 40.)
        val conflict = openBetween(Place.C, Place.D, at(45)).scheduleConflict(CONFLICT_TOO_SOON_AFTER_PREVIOUS)
        assertEquals(at(60), conflict.earliestFeasibleDeparture)
        assertEquals(HttpStatusCode.OK, openBetween(Place.C, Place.D, at(60)).status)
    }

    @Test
    fun aHugeMapKitEstimateDoesNotBlockWhatThePlannedIntervalAllows() = withScheduling(matrix()) {
        val ride = ride(DRIVER_A, at(0), mapKitSeconds = 3 * 3600) // MapKit claims 3 hours
        assertNotNull(seatIds(ride.rideId))
        assertEquals(HttpStatusCode.OK, openBetween(Place.C, Place.D, at(60)).status)
    }

    // ------------------------------------------------------------ alternatives
    @Test
    fun severalSeatRequestsForTheSameIntendedJourneyAreAlternativesAndAllAllowed() = withScheduling(matrix()) {
        val r1 = ride(DRIVER_A, at(0)); val r2 = ride(DRIVER_B, at(5)); val r3 = ride(third, at(10))
        val ids = listOf(r1, r2, r3).map { assertNotNull(seatIds(it.rideId, intent = search), "alternative for ride at ${it.departureTime}") }
        assertEquals(3, ids.flatten().toSet().size)
        assertEquals(3L, requestRows())
        assertEquals(3, ids.size)
    }

    @Test
    fun aSetOfAlternativesCountsAsOneSlotWhenCheckingTheNextJourney() = withScheduling(matrix()) {
        val rides = listOf(ride(DRIVER_A, at(0)), ride(DRIVER_B, at(5)), ride(third, at(10)))
        rides.forEach { assertNotNull(seatIds(it.rideId, intent = search)) }
        // The slot ends at its LATEST arrival (10 + 30 = 40). From B the next pickup needs 40 + 0 + 10 = 50.
        openBetween(Place.B, Place.C, at(49)).scheduleConflict(CONFLICT_TOO_SOON_AFTER_PREVIOUS)
        assertEquals(HttpStatusCode.OK, openBetween(Place.B, Place.C, at(50)).status)
    }

    @Test
    fun aSetOfAlternativesCountsAsOneSlotWhenItIsTheJourneyAfter() = withScheduling(matrix()) {
        val rides = listOf(ride(DRIVER_A, at(300)), ride(DRIVER_B, at(305)))
        rides.forEach { assertNotNull(seatIds(it.rideId, intent = search)) }
        // The slot is checked at its EARLIEST departure (300). A trip C -> B (20 min) then has B -> A (5) + buffer (10):
        // leaving 265 arrives 285, +15 = 300 fits; leaving 266 arrives 286, +15 = 301 does not. (A different route, so
        // it cannot itself be mistaken for another alternative.)
        val conflict = openBetween(Place.C, Place.B, at(266)).scheduleConflict(CONFLICT_TOO_LATE_FOR_NEXT)
        assertEquals(at(265), conflict.latestFeasibleDeparture)
        assertEquals(at(300), conflict.conflictingDepartureTime, "judged against the earliest-leaving alternative")
        assertEquals(HttpStatusCode.OK, openBetween(Place.C, Place.B, at(265)).status)
    }

    @Test
    fun anOpenRequestIsAlwaysItsOwnJourneyEvenOnTheSameRouteAsASeatRequest() = withScheduling(matrix()) {
        val r1 = ride(DRIVER_A, at(0))
        assertNotNull(seatIds(r1.rideId, intent = search))
        // A standalone ask has no journey intent, so it is never an alternative of the seat request: it is a second
        // journey and must fit after the first (arrive 30 + B -> A 5 + buffer 10 = 45).
        openBetween(Place.A, Place.B, at(20)).scheduleConflict(CONFLICT_TOO_SOON_AFTER_PREVIOUS)
        assertEquals(HttpStatusCode.OK, openBetween(Place.A, Place.B, at(45)).status)
    }

    @Test
    fun aSeatRequestUnderAnotherIntentIsAnotherJourneyAndMustFit() = withScheduling(matrix(Triple(Place.B, Place.A, 30L))) {
        val r1 = ride(DRIVER_A, at(0)); val r2 = ride(DRIVER_B, at(60))
        assertNotNull(seatIds(r1.rideId, intent = search))
        val response = postSeat(r2.rideId, journeyIntentId = "intent-search-0002")
        val conflict = response.scheduleConflict(CONFLICT_TOO_SOON_AFTER_PREVIOUS)
        assertEquals("SEAT_REQUEST", conflict.conflictingKind)
        assertEquals(at(70), conflict.earliestFeasibleDeparture)
        assertEquals(1L, requestRows())
    }

    @Test
    fun aSeatRequestFarEnoughApartIsJustAnotherFeasibleJourney() = withScheduling(matrix()) {
        val r1 = ride(DRIVER_A, at(0)); val r2 = ride(DRIVER_B, at(300))
        assertNotNull(seatIds(r1.rideId)); assertNotNull(seatIds(r2.rideId))
    }

    @Test
    fun anAcceptedRideIsNeverTreatedAsOneOfTheAlternatives() = withScheduling(matrix()) {
        val r1 = ride(DRIVER_A, at(0)); val r2 = ride(DRIVER_B, at(5))
        val first = assertNotNull(seatIds(r1.rideId, intent = search)).single()
        assertEquals(HttpStatusCode.OK, respond(DRIVER_A, first, RideRequestStatus.ACCEPTED).status)
        // The passenger is now booked on r1; a seat on r2 five minutes later is a second trip they cannot make.
        postSeat(r2.rideId, journeyIntentId = search).scheduleConflict(CONFLICT_TOO_SOON_AFTER_PREVIOUS)
    }

    @Test
    fun aSeatRequestWhoseRepositioningIsMissingFromTheMatrixIsRefusedRecoverablyNotGuessed() {
        withScheduling(FakeTravelTimeProvider(mapOf((Place.A to Place.B) to 1800L))) {
            val r1 = ride(DRIVER_A, at(0))
            val r2 = ride(DRIVER_B, at(300))
            assertNotNull(seatIds(r1.rideId))
            // Judging r2 needs B -> A, which the matrix does not have.
            val refused = postSeat(r2.rideId)
            assertEquals(HttpStatusCode.ServiceUnavailable, refused.status)
            assertEquals(CODE_TRAVEL_TIME_UNAVAILABLE, refused.asError().code)
            assertEquals(1L, requestRows())
        }
    }

    // ------------------------------------------------------------ a driver's own timeline
    @Test
    fun aDriverCannotPostTwoRidesTheyCannotPhysicallyDrive() = withScheduling(matrix()) {
        ride(DRIVER_A, at(0))
        val refused = rawRide(DRIVER_A, at(40))
        val conflict = refused.scheduleConflict(CONFLICT_TOO_SOON_AFTER_PREVIOUS)
        assertEquals("RIDE", conflict.conflictingKind)
        assertEquals(at(45), conflict.earliestFeasibleDeparture, "arrive B at 30 + B -> A 5 + buffer 10")
        assertEquals(HttpStatusCode.OK, rawRide(DRIVER_A, at(45)).status)
    }

    @Test
    fun aPassengerCannotRequestARideWhileDrivingAnother() = withScheduling(matrix()) {
        ride(DRIVER_A, at(0))
        val conflict = openBetween(Place.A, Place.B, at(10), caller = DRIVER_A).scheduleConflict()
        assertEquals("RIDE", conflict.conflictingKind)
    }

    @Test
    fun aDriverCannotPostARideOverlappingTheirOwnPendingRequest() = withScheduling(matrix()) {
        openBetween(Place.A, Place.B, at(300), caller = DRIVER_A).asRequest()
        val conflict = rawRide(DRIVER_A, at(310)).scheduleConflict()
        assertEquals("OPEN_REQUEST", conflict.conflictingKind)
    }

    @Test
    fun repeatedRidesAreEachJudgedOnTheirOwnDayAndTheFirstMustFit() = withScheduling(matrix()) {
        ride(DRIVER_A, at(0))
        val series = post("/rides") {
            header(HttpHeaders.Authorization, "Bearer $DRIVER_A")
            contentType(ContentType.Application.Json)
            val f = coords(Place.A); val t = coords(Place.B)
            setBody("""{"originLat":${f.first},"originLng":${f.second},"destinationLat":${t.first},"destinationLng":${t.second},
                "distanceMeters":4000.0,"estimatedDurationSeconds":600,"departureTime":${at(300)},"seatsTotal":3,
                "repeatDaysOfWeek":[1,2,3,4,5,6,7],"repeatWeeks":1}""")
        }
        assertEquals(HttpStatusCode.OK, series.status, series.bodyAsText())
        // (Only the rides this test created: the driver also has an older seeded ride that predates planning fields.)
        val planned = transaction { Rides.selectAll().where { Rides.driverId eq DRIVER_A }.filter { it[Rides.departureTime] >= at(0) }.map { it[Rides.plannedArrivalTime] } }
        assertTrue(planned.all { it != null }, "every created occurrence has its own planned arrival")
        assertTrue(planned.size >= 7)
        // The first occurrence clashing with an existing ride refuses the whole call.
        assertEquals(HttpStatusCode.Conflict, rawRide(DRIVER_A, at(310)).status)
    }

    @Test
    fun theAcceptedRideCreatedFromAnOpenRequestReusesTheRequestsPlanningValues() = withScheduling(matrix()) {
        val ask = openBetween(Place.A, Place.B, at(0)).asRequest()
        val accepted = respond(DRIVER_A, ask.requestId, RideRequestStatus.ACCEPTED).asRequest()
        val ride = transaction { Rides.selectAll().where { Rides.rideId eq accepted.rideId!! }.first() }
        assertEquals(ask.plannedDurationSeconds, ride[Rides.plannedDurationSeconds])
        assertEquals(ask.plannedArrivalTime, ride[Rides.plannedArrivalTime])
        assertEquals(Place.A, ride[Rides.originPointId]); assertEquals(Place.B, ride[Rides.destinationPointId])
    }
}
