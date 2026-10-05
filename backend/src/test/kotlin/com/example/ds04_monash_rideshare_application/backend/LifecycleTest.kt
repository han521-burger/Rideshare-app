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
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Covers Lifecycle.kt: stale requests/rides expire on their own, nobody can be in two trips at
// once, "drive this passenger instead" cancels the driver's own ask, and undo puts it all back.
class LifecycleTest {
    private val passenger = "demo-passenger-002"
    private val driver = "demo-driver-002"
    private val json = Json { ignoreUnknownKeys = true }
    private val minuteMs = 60_000L
    private val dayMs = 24 * 60 * minuteMs
    // Well clear of every seeded ride (Database.kt seeds up to ~2 days ahead).
    private val tripTime = System.currentTimeMillis() + 5 * dayMs

    private fun withApp(test: suspend HttpClient.() -> Unit) {
        val file = Files.createTempFile("rideshare-lifecycle-test-", ".db").toFile().apply { deleteOnExit() }
        testApplication {
            application { module("jdbc:sqlite:${file.absolutePath.replace('\\', '/')}") }
            startApplication()
            client.test()
        }
    }

    private suspend fun HttpClient.ask(caller: String, departure: Long = tripTime): RideRequestDto {
        val response = post("/requests") {
            header(HttpHeaders.Authorization, "Bearer $caller")
            contentType(ContentType.Application.Json)
            setBody(
                """{"originLat":-37.9,"originLng":145.1,"destinationLat":-37.9105,"destinationLng":145.1362,
                    |"isAsap":false,"desiredDepartureTime":$departure,"seatsRequested":1}""".trimMargin()
            )
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.decodeFromString(response.bodyAsText())
    }

    private suspend fun HttpClient.accept(caller: String, requestId: String, cancelMine: Boolean = false): HttpResponse =
        put("/requests/$requestId") {
            header(HttpHeaders.Authorization, "Bearer $caller")
            contentType(ContentType.Application.Json)
            setBody("""{"status":"ACCEPTED","cancelMyConflictingRequests":$cancelMine}""")
        }

    private fun statusOf(requestId: String): String = transaction {
        RideRequests.selectAll().where { RideRequests.requestId eq requestId }.first()[RideRequests.status]
    }

    @Test
    fun `stale requests and empty rides expire`() = withApp {
        val now = System.currentTimeMillis()
        transaction {
            RideRequests.insert {
                it[requestId] = "req-past"; it[rideId] = null; it[passengerId] = passenger
                it[seatsRequested] = 1; it[status] = RideRequestStatus.PENDING
                it[originLat] = -37.9; it[originLng] = 145.1; it[destinationLat] = -37.91; it[destinationLng] = 145.13
                it[isAsap] = false; it[desiredDepartureTime] = now - minuteMs; it[createdAt] = now - dayMs
            }
            RideRequests.insert {
                it[requestId] = "req-old-asap"; it[rideId] = null; it[passengerId] = passenger
                it[seatsRequested] = 1; it[status] = RideRequestStatus.PENDING
                it[originLat] = -37.9; it[originLng] = 145.1; it[destinationLat] = -37.91; it[destinationLng] = 145.13
                it[isAsap] = true; it[createdAt] = now - 31 * minuteMs
            }
            RideRequests.insert {
                it[requestId] = "req-fresh-asap"; it[rideId] = null; it[passengerId] = passenger
                it[seatsRequested] = 1; it[status] = RideRequestStatus.PENDING
                it[originLat] = -37.9; it[originLng] = 145.1; it[destinationLat] = -37.91; it[destinationLng] = 145.13
                it[isAsap] = true; it[createdAt] = now - 5 * minuteMs
            }
        }
        // A seeded open ride with nobody accepted, moved into the past.
        val pastRideId = "ride-open-1"
        transaction { Rides.update({ Rides.rideId eq pastRideId }) { it[departureTime] = now - minuteMs } }

        // Any API call triggers the tidy-up.
        get("/requests/mine") { header(HttpHeaders.Authorization, "Bearer $passenger") }

        assertEquals(RideRequestStatus.EXPIRED, statusOf("req-past"))
        assertEquals(RideRequestStatus.EXPIRED, statusOf("req-old-asap"))
        assertEquals(RideRequestStatus.PENDING, statusOf("req-fresh-asap"))
        val rideStatus = transaction { Rides.selectAll().where { Rides.rideId eq pastRideId }.first()[Rides.status] }
        assertEquals(RideStatus.EXPIRED, rideStatus)

        val response = accept(driver, "req-past")
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertTrue(response.bodyAsText().contains("expired"))
    }

    @Test
    fun `driver with own ask at the same time must confirm, and undo restores everything`() = withApp {
        val passengerAsk = ask(passenger)
        val driversOwnAsk = ask(driver)

        val refused = accept(driver, passengerAsk.requestId)
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals(CONFLICT_OWN_PENDING_REQUEST, json.decodeFromString<ErrorResponse>(refused.bodyAsText()).code)
        assertEquals(RideRequestStatus.PENDING, statusOf(passengerAsk.requestId))

        val accepted = accept(driver, passengerAsk.requestId, cancelMine = true)
        assertEquals(HttpStatusCode.OK, accepted.status, accepted.bodyAsText())
        val acceptedDto = json.decodeFromString<RideRequestDto>(accepted.bodyAsText())
        assertEquals(RideRequestStatus.CANCELLED, statusOf(driversOwnAsk.requestId))

        val undo = post("/requests/${passengerAsk.requestId}/undo-accept") {
            header(HttpHeaders.Authorization, "Bearer $driver")
        }
        assertEquals(HttpStatusCode.OK, undo.status, undo.bodyAsText())
        val undone = json.decodeFromString<RideRequestDto>(undo.bodyAsText())
        assertEquals(RideRequestStatus.PENDING, undone.status)
        assertNull(undone.rideId)
        assertEquals(RideRequestStatus.PENDING, statusOf(driversOwnAsk.requestId))
        val rideLeft = transaction { Rides.selectAll().where { Rides.rideId eq acceptedDto.rideId!! }.count() }
        assertEquals(0L, rideLeft)
    }

    @Test
    fun `a booked passenger can't drive or be booked again at the same time`() = withApp {
        // Test Student has a registered vehicle, so offering a ride is otherwise allowed.
        val passenger = "fake-user-001"
        val first = ask(passenger)
        assertEquals(HttpStatusCode.OK, accept(driver, first.requestId).status)

        // Passenger now tries to offer a ride at the same time.
        val offer = post("/rides") {
            header(HttpHeaders.Authorization, "Bearer $passenger")
            contentType(ContentType.Application.Json)
            setBody(
                """{"originLat":-37.9,"originLng":145.1,"destinationLat":-37.9105,"destinationLng":145.1362,
                    |"distanceMeters":4000.0,"estimatedDurationSeconds":600,"departureTime":${tripTime + 10 * minuteMs},
                    |"seatsTotal":1,"repeatDaysOfWeek":[],"repeatWeeks":1}""".trimMargin()
            )
        }
        assertEquals(HttpStatusCode.Conflict, offer.status, offer.bodyAsText())

        // ...or asks for another ride at the same time.
        val again = post("/requests") {
            header(HttpHeaders.Authorization, "Bearer $passenger")
            contentType(ContentType.Application.Json)
            setBody(
                """{"originLat":-37.9,"originLng":145.1,"destinationLat":-37.9105,"destinationLng":145.1362,
                    |"isAsap":false,"desiredDepartureTime":${tripTime + 15 * minuteMs},"seatsRequested":1}""".trimMargin()
            )
        }
        assertEquals(HttpStatusCode.Conflict, again.status, again.bodyAsText())

        // A different day is fine.
        ask(passenger, tripTime + dayMs)
    }
}
