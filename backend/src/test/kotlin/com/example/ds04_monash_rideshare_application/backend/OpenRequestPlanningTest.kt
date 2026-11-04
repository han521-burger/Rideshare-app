package com.example.ds04_monash_rideshare_application.backend

import com.example.ds04_monash_rideshare_application.backend.routes.haversine
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.decodeFromString
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// What an open request records about its OWN trip when it is created: canonical endpoints, the planned
// duration (from the travel-time matrix) and the planned arrival. Later scheduling checks build on these; nothing here
// looks at the passenger's other journeys.
class OpenRequestPlanningTest {
    private val t = futureDeparture()
    private val hunt = "huntingdale-station"
    private val clay = "monash-clayton"
    private fun expected(from: String, to: String) = SyntheticTravelTimes.seconds(from, to)

    private suspend fun HttpClient.mine(caller: String = PASSENGER): List<RideRequestDto> {
        val response = get("/requests/mine") { header(HttpHeaders.Authorization, "Bearer $caller") }
        return schedulingJson.decodeFromString(response.bodyAsText())
    }

    // --- what is stored ---

    @Test
    fun anOpenRequestStoresCanonicalIdsAndItsPlannedInterval() = withScheduling {
        val created = postOpen(openRequestBody(at = t)).asRequest()
        val seconds = expected(hunt, clay)

        assertEquals(seconds, created.plannedDurationSeconds)
        assertEquals(t + seconds * 1000, created.plannedArrivalTime)

        val row = transaction { RideRequests.selectAll().where { RideRequests.requestId eq created.requestId }.first() }
        assertEquals(hunt, row[RideRequests.originPointId])
        assertEquals(clay, row[RideRequests.destinationPointId])
        assertEquals(seconds, row[RideRequests.plannedDurationSeconds])
        assertEquals(t + seconds * 1000, row[RideRequests.plannedArrivalTime])
    }

    @Test
    fun theSameRequestReadsBackConsistently() = withScheduling {
        val created = postOpen(openRequestBody(at = t)).asRequest()
        val read = mine().single { it.requestId == created.requestId }
        assertEquals(created.plannedDurationSeconds, read.plannedDurationSeconds)
        assertEquals(created.plannedArrivalTime, read.plannedArrivalTime)
        assertEquals(created.desiredDepartureTime, read.desiredDepartureTime)
        // And the driver sees the same plan.
        val driverSide = driverFeedAll(DRIVER_A).single { it.requestId == created.requestId }
        assertEquals(created.plannedArrivalTime, driverSide.plannedArrivalTime)
    }

    @Test
    fun theStoredLocationIsTheApprovedPointNotTheRawInput() = withScheduling {
        val noisy = openRequestBody(
            from = Pts.huntingdale.first + 0.00003 to Pts.huntingdale.second - 0.00002, to = Pts.clayton, at = t
        )
        val created = postOpen(noisy).asRequest()
        val approved = approvedPointById(hunt)!!
        assertEquals(approved.lat, created.originLat)
        assertEquals(approved.lng, created.originLng)
    }

    @Test
    fun theDurationIsDirectionalAndComesFromTheMatrixNotFromDistance() = withScheduling {
        val out = postOpen(openRequestBody(from = Pts.huntingdale, to = Pts.clayton, at = t)).asRequest()
        val back = postOpen(openRequestBody(from = Pts.clayton, to = Pts.huntingdale, at = t + DAY)).asRequest()
        assertEquals(expected(hunt, clay), out.plannedDurationSeconds)
        assertEquals(expected(clay, hunt), back.plannedDurationSeconds)
        assertNotEquals(out.plannedDurationSeconds, back.plannedDurationSeconds, "the two directions should be independent values")
    }

    @Test
    fun noStraightLineSpeedFallbackIsUsedForANewRequest() = withScheduling {
        // The old estimate: straight-line distance at 40 km/h. The matrix value must be used instead, even
        // when the two disagree (they do for this pair with the synthetic matrix).
        val glenWaverley = approvedPointById("glen-waverley-station")!!
        val clayton = approvedPointById(clay)!!
        val oldEstimate = (haversine(glenWaverley.lat, glenWaverley.lng, clayton.lat, clayton.lng) / (40_000.0 / 3600.0)).toLong()
        val created = postOpen(openRequestBody(from = Pts.glenWaverley, to = Pts.clayton, at = t)).asRequest()
        assertEquals(expected("glen-waverley-station", clay), created.plannedDurationSeconds)
        assertNotEquals(oldEstimate, created.plannedDurationSeconds)
    }

    // --- ASAP: server time ---

    @Test
    fun asapDepartsAtServerTimeAndArrivesAfterThePlannedDuration() = withScheduling {
        val before = System.currentTimeMillis()
        val created = postOpen(openRequestBody(asap = true)).asRequest()
        val after = System.currentTimeMillis()
        assertNull(created.desiredDepartureTime)
        assertTrue(created.createdAt in before..after, "createdAt ${created.createdAt} should be server time in $before..$after")
        assertEquals(created.createdAt + expected(hunt, clay) * 1000, created.plannedArrivalTime)
    }

    @Test
    fun aDepartureTimeSentWithAnAsapRequestIsIgnored() = withScheduling {
        // A phone with a wrong clock must not decide when an ASAP trip departs.
        val bogus = 1_000_000_000_000L // September 2001
        val body = openRequestBody(asap = true).replace(""""desiredDepartureTime":null""", """"desiredDepartureTime":$bogus""")
        assertTrue(body.contains("$bogus"))
        val created = postOpen(body).asRequest()
        assertNull(created.desiredDepartureTime)
        assertEquals(created.createdAt + expected(hunt, clay) * 1000, created.plannedArrivalTime)
        assertTrue(created.plannedArrivalTime!! > System.currentTimeMillis() - 60_000)
    }

    // --- a pair with no travel time ---

    @Test
    fun aMissingMatrixEntryRefusesTheRequestRecoverablyAndStoresNothing() {
        val onlyOneDirection = FakeTravelTimeProvider(mapOf((hunt to clay) to 600L))
        withScheduling(onlyOneDirection) {
            // The pair that has an entry works...
            assertEquals(HttpStatusCode.OK, postOpen(openRequestBody(from = Pts.huntingdale, to = Pts.clayton, at = t)).status)
            // ...the reverse has none, and is NOT inferred from it.
            val refused = postOpen(openRequestBody(from = Pts.clayton, to = Pts.huntingdale, at = t + DAY))
            assertEquals(HttpStatusCode.ServiceUnavailable, refused.status)
            val error = refused.asError()
            assertEquals(CODE_TRAVEL_TIME_UNAVAILABLE, error.code)
            assertEquals(1, requestRows(), "the refused request must not leave a row behind")
            assertEquals(1, mine().count { it.originLat != null }, "only the request that could be planned exists")
        }
    }

    @Test
    fun anUnpopulatedMatrixRefusesEveryOpenRequestWithoutGuessing() {
        val empty = MatrixTravelTimeProvider(TravelMatrix(emptyMap(), TRAVEL_MATRIX_UNPOPULATED))
        withScheduling(empty) {
            for (asap in listOf(false, true)) {
                val refused = postOpen(openRequestBody(at = if (asap) null else t, asap = asap))
                assertEquals(HttpStatusCode.ServiceUnavailable, refused.status)
                assertEquals(CODE_TRAVEL_TIME_UNAVAILABLE, refused.asError().code)
            }
            assertEquals(0, requestRows())
        }
    }

    @Test
    fun aDuplicateIsStillReportedAsADuplicateWhenTheMatrixLosesThatPair() {
        val provider = MutableTravelTimes(mapOf((hunt to clay) to 600L))
        withScheduling(provider) {
            val first = postOpen(openRequestBody(at = t)).asRequest()
            provider.remove(hunt, clay)
            val again = postOpen(openRequestBody(at = t))
            assertEquals(HttpStatusCode.Conflict, again.status)
            assertEquals(first.requestId, again.asError().existingRequestId)
        }
    }

    @Test
    fun aRetryOfASavedRequestStillReplaysWhenTheMatrixLosesThatPair() {
        val provider = MutableTravelTimes(mapOf((hunt to clay) to 600L))
        withScheduling(provider) {
            val body = openRequestBody(at = t, clientRequestId = "plan-key-0001")
            val first = postOpen(body).asRequest()
            provider.remove(hunt, clay)
            assertEquals(first.requestId, postOpen(body).asRequest().requestId)
        }
    }

    // --- endpoint enforcement over HTTP ---

    private val nowhere = -37.9205 to 145.1362

    @Test
    fun unapprovedAndMalformedRoutesAreRefusedWithAClearCodeAndNothingIsStored() = withScheduling {
        data class Case(val name: String, val from: Pair<Double, Double>, val to: Pair<Double, Double>, val code: String)
        val hub2 = Pts.glenWaverley
        listOf(
            Case("approved -> unapproved", Pts.clayton, nowhere, CODE_UNAPPROVED_LOCATION),
            Case("unapproved -> approved", nowhere, Pts.clayton, CODE_UNAPPROVED_LOCATION),
            Case("unapproved -> unapproved", nowhere, nowhere.first - 0.02 to nowhere.second, CODE_UNAPPROVED_LOCATION),
            Case("hub -> hub", Pts.huntingdale, hub2, CODE_CAMPUS_ENDPOINT_REQUIRED),
            Case("same place", Pts.clayton, Pts.clayton, CODE_SAME_ENDPOINT),
        ).forEach { case ->
            val response = postOpen(openRequestBody(from = case.from, to = case.to, at = t))
            assertEquals(HttpStatusCode.BadRequest, response.status, case.name)
            assertEquals(case.code, response.asError().code, case.name)
        }
        assertEquals(0, requestRows())
    }

    @Test
    fun postedRidesFollowTheSameEndpointRule() = withScheduling {
        suspend fun offer(from: Pair<Double, Double>, to: Pair<Double, Double>) = post("/rides") {
            header(HttpHeaders.Authorization, "Bearer $DRIVER_A")
            contentType(ContentType.Application.Json)
            setBody("""{"originLat":${from.first},"originLng":${from.second},"destinationLat":${to.first},"destinationLng":${to.second},
                "distanceMeters":4000.0,"estimatedDurationSeconds":600,"departureTime":$t,"seatsTotal":3,"repeatDaysOfWeek":[],"repeatWeeks":1}""")
        }
        assertEquals(HttpStatusCode.OK, offer(Pts.huntingdale, Pts.clayton).status)
        assertEquals(CODE_UNAPPROVED_LOCATION, offer(Pts.clayton, nowhere).also { assertEquals(HttpStatusCode.BadRequest, it.status) }.asError().code)
        assertEquals(CODE_CAMPUS_ENDPOINT_REQUIRED, offer(Pts.huntingdale, Pts.glenWaverley).asError().code)
    }

    // --- repeating series ---

    @Test
    fun everyOccurrenceGetsTheSameDurationAndItsOwnArrival() = withScheduling {
        postOpen(openRequestBody(at = t, repeatDays = listOf(1, 2, 3, 4, 5, 6, 7), repeatWeeks = 1)).asRequest()
        val series = mine().filter { it.plannedArrivalTime != null }
        assertEquals(7, series.size)
        series.forEach {
            assertEquals(expected(hunt, clay), it.plannedDurationSeconds)
            assertEquals(it.desiredDepartureTime!! + expected(hunt, clay) * 1000, it.plannedArrivalTime)
        }
        assertEquals(7, series.map { it.plannedArrivalTime }.toSet().size)
    }

    @Test
    fun repeatDaysAreExpandedInMelbourneTimeWhateverZoneTheServerRunsIn() {
        val zone = SchedulingConfig.SERVICE_ZONE
        // Start two days before the next daylight-saving change so the week crosses it.
        val transition = zone.rules.nextTransition(Instant.now().plus(3, ChronoUnit.DAYS))
        val startDay: LocalDate = transition.instant.atZone(zone).toLocalDate().minusDays(2)
        val start = startDay.atTime(LocalTime.of(8, 30)).atZone(zone).toInstant().toEpochMilli()

        fun departuresWhenServerRunsIn(serverZone: String): List<Long> {
            val saved = TimeZone.getDefault()
            TimeZone.setDefault(TimeZone.getTimeZone(serverZone))
            try {
                var result = emptyList<Long>()
                withScheduling {
                    postOpen(openRequestBody(at = start, repeatDays = listOf(1, 2, 3, 4, 5, 6, 7), repeatWeeks = 1)).asRequest()
                    result = mine().mapNotNull { it.desiredDepartureTime }.sorted()
                }
                return result
            } finally {
                TimeZone.setDefault(saved)
            }
        }

        val inUtc = departuresWhenServerRunsIn("UTC")
        val inNewYork = departuresWhenServerRunsIn("America/New_York")
        assertEquals(7, inUtc.size)
        assertEquals(inUtc, inNewYork, "the series must not depend on the server's own time zone")
        // 08:30 in Melbourne on every day of the week, across the daylight-saving change.
        inUtc.forEach { assertEquals(LocalTime.of(8, 30), Instant.ofEpochMilli(it).atZone(zone).toLocalTime()) }
    }

    // --- seat requests, accepted requests, legacy rows ---

    @Test
    fun seatRequestsCarryNoPlanningFieldsBecauseTheRideIsThePlan() = withScheduling {
        val ride = offerRide(DRIVER_A, t)
        val seat = createdRequests(postSeat(ride.rideId).bodyAsText()).single()
        assertNull(seat.plannedDurationSeconds)
        assertNull(seat.plannedArrivalTime)
        assertNull(transaction { RideRequests.selectAll().where { RideRequests.requestId eq seat.requestId }.first()[RideRequests.originPointId] })
    }

    @Test
    fun aRideCreatedByAcceptingARequestKeepsThePlannedDuration() = withScheduling {
        val ask = postOpen(openRequestBody(at = t)).asRequest()
        val accepted = respond(DRIVER_A, ask.requestId, RideRequestStatus.ACCEPTED).asRequest()
        val ride = transaction { Rides.selectAll().where { Rides.rideId eq accepted.rideId!! }.first() }
        assertEquals(ask.plannedDurationSeconds, ride[Rides.estimatedDurationSeconds])
        assertEquals(t, ride[Rides.departureTime])
    }

    private fun legacyAsk(id: String, at: Long, createdAt: Long = System.currentTimeMillis()) =
        insertOpenAsk(id, desiredDeparture = at, createdAt = createdAt)

    @Test
    fun legacyRowsWithoutPlanningFieldsStillReadBack() = withScheduling {
        legacyAsk("legacy-1", t + 3 * DAY)
        val read = mine().single { it.requestId == "legacy-1" }
        assertNull(read.plannedDurationSeconds)
        assertNull(read.plannedArrivalTime)
        assertEquals(RideRequestStatus.PENDING, read.status)
        assertEquals(1, driverFeedAll(DRIVER_A).count { it.requestId == "legacy-1" })
    }

    @Test
    fun aLegacyRowNeverLeaksIntoOrBlocksAFreshPlannedRequest() = withScheduling {
        legacyAsk("legacy-1", t + 3 * DAY)
        val fresh = postOpen(openRequestBody(at = t)).asRequest()
        assertEquals(expected(hunt, clay), fresh.plannedDurationSeconds)
        assertEquals(t + expected(hunt, clay) * 1000, fresh.plannedArrivalTime)
        assertNull(mine().single { it.requestId == "legacy-1" }.plannedArrivalTime)
    }

    @Test
    fun acceptingALegacyRequestWhoseEndpointsAreApprovedGivesTheRideMatrixPlanningValues() = withScheduling {
        legacyAsk("legacy-1", t)   // approved endpoints, but no planning fields stored
        val accepted = respond(DRIVER_A, "legacy-1", RideRequestStatus.ACCEPTED).asRequest()
        val ride = transaction { Rides.selectAll().where { Rides.rideId eq accepted.rideId!! }.first() }
        val seconds = expected(hunt, clay)
        // The new RIDE is a new row, so it is planned from the matrix like any other - not by the straight-line estimate.
        assertEquals(hunt, ride[Rides.originPointId]); assertEquals(clay, ride[Rides.destinationPointId])
        assertEquals(seconds, ride[Rides.plannedDurationSeconds])
        assertEquals(t + seconds * 1000, ride[Rides.plannedArrivalTime])
        assertEquals(seconds, ride[Rides.estimatedDurationSeconds], "there is no MapKit figure for a server-made ride")
    }

    @Test
    fun acceptingALegacyRequestThatCannotBePlacedOnTheMapKeepsTheOldEstimateAndNoPlanningFields() = withScheduling {
        // An endpoint that is not an approved point (e.g. a retired hub): the only case the straight-line estimate survives.
        val retired = -37.9 to 145.1
        insertOpenAsk("legacy-2", desiredDeparture = t, createdAt = System.currentTimeMillis(), from = retired, to = Pts.clayton)
        val accepted = respond(DRIVER_A, "legacy-2", RideRequestStatus.ACCEPTED).asRequest()
        val ride = transaction { Rides.selectAll().where { Rides.rideId eq accepted.rideId!! }.first() }
        val estimate = (haversine(ride[Rides.originLat], ride[Rides.originLng], ride[Rides.destinationLat], ride[Rides.destinationLng]) / (40_000.0 / 3600.0)).toLong()
        assertEquals(estimate, ride[Rides.estimatedDurationSeconds])
        assertNull(ride[Rides.plannedDurationSeconds]); assertNull(ride[Rides.plannedArrivalTime]); assertNull(ride[Rides.originPointId])
    }

    // --- migration from a database made before any of this existed ---

    @Test
    fun aDatabaseFromBeforeTheNewColumnsMigratesInPlaceAndItsRowsStayReadable() {
        val file = Files.createTempFile("rideshare-legacy-", ".db").toFile().apply { deleteOnExit() }
        val url = "jdbc:sqlite:${file.absolutePath.replace('\\', '/')}"
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { st ->
                // ride_requests exactly as it was before journey keys, point ids and planning fields.
                st.execute(
                    """CREATE TABLE ride_requests (request_id VARCHAR(64) NOT NULL PRIMARY KEY, ride_id VARCHAR(64), passenger_id VARCHAR(64) NOT NULL,
                        seats_requested INT NOT NULL, status VARCHAR(32) NOT NULL DEFAULT 'PENDING', note VARCHAR(500), origin_lat DOUBLE PRECISION,
                        origin_lng DOUBLE PRECISION, destination_lat DOUBLE PRECISION, destination_lng DOUBLE PRECISION, is_asap BOOLEAN NOT NULL DEFAULT 0,
                        desired_departure_time BIGINT, repeat_days_of_week VARCHAR(32), repeat_weeks INT, created_at BIGINT NOT NULL,
                        responded_at BIGINT, driver_decision_at BIGINT)"""
                )
                st.execute(
                    """INSERT INTO ride_requests (request_id, passenger_id, seats_requested, status, origin_lat, origin_lng, destination_lat,
                        destination_lng, is_asap, desired_departure_time, created_at)
                       VALUES ('very-old-1', 'demo-passenger-002', 1, 'PENDING', -37.9, 145.1, -37.91, 145.13, 0, ${t + 4 * DAY}, 1700000000000)"""
                )
            }
        }
        testApplication {
            application { module(url, testTravelTimes()) }
            startApplication()
            val old = client.mine().single { it.requestId == "very-old-1" }
            assertNull(old.plannedDurationSeconds)
            assertNull(old.plannedArrivalTime)
            assertEquals(RideRequestStatus.PENDING, old.status)
            // The new columns now exist, and a new request can be made on the migrated database.
            val columns = DriverManager.getConnection(url).use { c ->
                c.createStatement().use { st -> st.executeQuery("PRAGMA table_info(ride_requests)").use { rs -> generateSequence { if (rs.next()) rs.getString("name") else null }.toList() } }
            }
            listOf("origin_point_id", "destination_point_id", "journey_key", "planned_duration_seconds", "planned_arrival_time").forEach {
                assertTrue(it in columns, "column $it should have been added; have $columns")
            }
            assertEquals(HttpStatusCode.OK, client.postOpen(openRequestBody(at = t)).status)
        }
    }
}

// A travel-time provider a test can change while the app is running.
class MutableTravelTimes(initial: Map<Pair<String, String>, Long>) : TravelTimeProvider {
    private val seconds = initial.toMutableMap()
    fun remove(from: String, to: String) { seconds.remove(from to to) }
    override fun travelSeconds(fromPointId: String, toPointId: String): Long? = if (fromPointId == toPointId) 0L else seconds[fromPointId to toPointId]
}
