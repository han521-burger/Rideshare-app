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
import io.ktor.server.testing.testApplication
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RideCancellationPropagationTest {
    private val rideId = "ride-open-2"
    private val driver = "demo-driver-002"
    private val json = Json { ignoreUnknownKeys = true }
    private val oldTimestamp = 1_000L

    private fun withRide(vararg states: Pair<String, Int>, test: suspend HttpClient.() -> Unit) {
        val file = Files.createTempFile("rideshare-whole-cancellation-", ".db").toFile().apply { deleteOnExit() }
        testApplication {
            application { module("jdbc:sqlite:${file.absolutePath.replace('\\', '/')}") }
            startApplication()
            transaction {
                val consumed = states.filter { it.first in listOf(RideRequestStatus.ACCEPTED, RideRequestStatus.COMPLETED) }
                    .sumOf { it.second }
                Rides.update({ Rides.rideId eq rideId }) {
                    it[seatsTotal] = 4
                    it[seatsAvailable] = 4 - consumed
                    it[status] = if (consumed > 0) RideStatus.CONFIRMED else RideStatus.OPEN
                }
                states.forEachIndexed { index, (status, seats) ->
                    val user = "whole-passenger-$index"
                    Users.insert {
                        it[userId] = user
                        it[externalSubjectId] = user
                        it[emailAccount] = "$user@example.test"
                        it[displayName] = "Fixture passenger $index"
                        it[accountStatus] = "ACTIVE"
                        it[createdAt] = oldTimestamp
                    }
                    RideRequests.insert {
                        it[requestId] = "whole-$index"
                        it[RideRequests.rideId] = this@RideCancellationPropagationTest.rideId
                        it[passengerId] = user
                        it[seatsRequested] = seats
                        it[RideRequests.status] = status
                        it[createdAt] = oldTimestamp
                        it[respondedAt] = if (status == RideRequestStatus.PENDING) null else oldTimestamp
                        it[note] = "Preserve request metadata"
                    }
                }
            }
            client.test()
        }
    }

    private fun requests(): Map<String, RideRequestDto> = transaction {
        RideRequests.selectAll().map { it.toRideRequestDto() }.associateBy { it.requestId }
    }

    private suspend fun HttpClient.ride(id: String = rideId): RideDto {
        val response = get("/rides/$id") { header(HttpHeaders.Authorization, "Bearer $driver") }
        assertEquals(HttpStatusCode.OK, response.status)
        return json.decodeFromString<RideDto>(response.bodyAsText()).also {
            assertTrue(it.seatsAvailable in 0..it.seatsTotal)
        }
    }

    private suspend fun HttpClient.cancel(caller: String = driver, expected: HttpStatusCode = HttpStatusCode.OK) {
        val response = put("/rides/$rideId") {
            header(HttpHeaders.Authorization, "Bearer $caller")
            contentType(ContentType.Application.Json)
            setBody("""{"status":"CANCELLED"}""")
        }
        assertEquals(expected, response.status)
        if (expected == HttpStatusCode.OK) {
            assertEquals(RideStatus.CANCELLED, json.decodeFromString<RideDto>(response.bodyAsText()).status)
        }
    }

    private fun assertPropagation(before: Map<String, RideRequestDto>, earliest: Long) {
        val after = requests()
        assertEquals(before.keys, after.keys)
        val latest = System.currentTimeMillis()
        before.forEach { (id, request) ->
            val actual = after.getValue(id)
            if (request.rideId == rideId && request.status in listOf(RideRequestStatus.PENDING, RideRequestStatus.ACCEPTED)) {
                assertEquals(RideRequestStatus.CANCELLED, actual.status, id)
                val responded = assertNotNull(actual.respondedAt)
                assertTrue(responded in earliest..latest)
                assertEquals(request.copy(status = RideRequestStatus.CANCELLED, respondedAt = responded), actual)
            } else {
                assertEquals(request, actual, "Unrelated or terminal request $id must be unchanged")
            }
        }
    }

    private suspend fun HttpClient.verifyCancellation() {
        val beforeRide = ride()
        val before = requests()
        val earliest = System.currentTimeMillis()
        cancel()
        assertEquals(beforeRide.copy(status = RideStatus.CANCELLED), ride())
        assertPropagation(before, earliest)
    }

    @Test
    fun `open ride cancellation propagates to pending request`() = withRide(RideRequestStatus.PENDING to 1) {
        assertEquals(RideStatus.OPEN, ride().status)
        verifyCancellation()
    }

    @Test
    fun `confirmed ride cancellation propagates to accepted passenger API response`() = withRide(RideRequestStatus.ACCEPTED to 1) {
        assertEquals(RideStatus.CONFIRMED, ride().status)
        verifyCancellation()
        val response = get("/requests/mine") {
            header(HttpHeaders.Authorization, "Bearer whole-passenger-0")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val request = json.decodeFromString<List<RideRequestDto>>(response.bodyAsText()).single()
        assertEquals(RideRequestStatus.CANCELLED, request.status)
        assertEquals(RideStatus.CANCELLED, request.ride?.status)
    }

    @Test
    fun `mixed active and historical requests preserve terminal metadata`() = withRide(
        RideRequestStatus.PENDING to 1, RideRequestStatus.ACCEPTED to 1,
        RideRequestStatus.REJECTED to 1, RideRequestStatus.CANCELLED to 1, RideRequestStatus.COMPLETED to 1
    ) { verifyCancellation() }

    @Test
    fun `multiple accepted passengers cancel without restoring capacity`() = withRide(
        RideRequestStatus.ACCEPTED to 1, RideRequestStatus.ACCEPTED to 2
    ) {
        assertEquals(1, ride().seatsAvailable)
        verifyCancellation()
        assertEquals(1, ride().seatsAvailable)
    }

    @Test
    fun `unauthorized passenger and driver cannot cancel or propagate`() = withRide(
        RideRequestStatus.PENDING to 1, RideRequestStatus.ACCEPTED to 1
    ) {
        val beforeRide = ride()
        val before = requests()
        for (caller in listOf("fake-user-001", "demo-driver-001", "whole-passenger-0")) {
            cancel(caller, HttpStatusCode.Forbidden)
            assertEquals(beforeRide, ride())
            assertEquals(before, requests())
        }
    }

    @Test
    fun `repeated whole cancellation is idempotent and later individual cancellation conflicts`() = withRide(
        RideRequestStatus.ACCEPTED to 2
    ) {
        verifyCancellation()
        val cancelled = requests()
        val cancelledRide = ride()
        // Existing ride PUT permits repeating the same status with HTTP 200.
        cancel()
        assertEquals(cancelled, requests())
        assertEquals(cancelledRide, ride())
        val response = put("/requests/whole-0") {
            header(HttpHeaders.Authorization, "Bearer whole-passenger-0")
            contentType(ContentType.Application.Json)
            setBody("""{"status":"CANCELLED"}""")
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals(cancelled, requests())
        assertEquals(cancelledRide, ride())
    }

    @Test
    fun `completed ride rejects cancellation and preserves completed request history`() = withRide(
        RideRequestStatus.COMPLETED to 1
    ) {
        transaction { Rides.update({ Rides.rideId eq rideId }) { it[status] = RideStatus.COMPLETED } }
        val beforeRide = ride()
        val before = requests()
        cancel(expected = HttpStatusCode.Conflict)
        assertEquals(beforeRide, ride())
        assertEquals(before, requests())
    }

    @Test
    fun `unattached standalone request is not cancelled`() = withRide(RideRequestStatus.PENDING to 1) {
        transaction {
            RideRequests.insert {
                it[requestId] = "standalone"
                it[passengerId] = "fake-user-001"
                it[seatsRequested] = 1
                it[status] = RideRequestStatus.PENDING
                it[originLat] = -37.8136
                it[originLng] = 144.9631
                it[destinationLat] = -37.8988
                it[destinationLng] = 145.0906
                it[isAsap] = true
                it[createdAt] = oldTimestamp
            }
        }
        verifyCancellation()
        assertEquals(RideRequestStatus.PENDING, requests().getValue("standalone").status)
    }

    @Test
    fun `recurring sibling ride and its request are untouched`() = withRide(RideRequestStatus.PENDING to 1) {
        transaction {
            Rides.update({ Rides.rideId eq rideId }) { it[recurringGroupId] = "whole-group" }
            Rides.update({ Rides.rideId eq "ride-open-1" }) { it[recurringGroupId] = "whole-group" }
            RideRequests.insert {
                it[requestId] = "sibling-request"
                it[RideRequests.rideId] = "ride-open-1"
                it[passengerId] = "fake-user-001"
                it[seatsRequested] = 1
                it[status] = RideRequestStatus.PENDING
                it[createdAt] = oldTimestamp
            }
        }
        val sibling = ride("ride-open-1")
        verifyCancellation()
        assertEquals(sibling, ride("ride-open-1"))
    }

    @Test
    fun `propagation failure rolls back ride and request updates`() = withRide(
        RideRequestStatus.PENDING to 1, RideRequestStatus.ACCEPTED to 1
    ) {
        val beforeRide = ride()
        val before = requests()
        transaction {
            exec("""CREATE TRIGGER fail_whole_cancellation BEFORE UPDATE OF status ON ride_requests
                WHEN NEW.request_id = 'whole-1' AND NEW.status = 'CANCELLED'
                BEGIN SELECT RAISE(ABORT, 'test propagation failure'); END""")
        }
        cancel(expected = HttpStatusCode.InternalServerError)
        assertEquals(beforeRide, ride())
        assertEquals(before, requests())
    }

    @Test
    fun `ordinary ride update does not propagate requests`() = withRide(RideRequestStatus.ACCEPTED to 1) {
        val before = requests()
        val beforeRide = ride()
        val response = put("/rides/$rideId") {
            header(HttpHeaders.Authorization, "Bearer $driver")
            contentType(ContentType.Application.Json)
            setBody("""{"notes":"Updated pickup note"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(before, requests())
        assertEquals(beforeRide.copy(notes = "Updated pickup note"), ride())
    }
}
