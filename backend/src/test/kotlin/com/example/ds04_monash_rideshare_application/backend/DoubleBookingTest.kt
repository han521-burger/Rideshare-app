package com.example.ds04_monash_rideshare_application.backend

import io.ktor.client.HttpClient
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
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// A passenger can't end up on the wrong day's ride, on the same ride twice, or booked by two
// drivers for the same time.
class DoubleBookingTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val passenger = "demo-passenger-002"
    private val driver = "demo-driver-002"
    private val dayMs = 24 * 60 * 60 * 1000L
    private val tripTime = System.currentTimeMillis() + 5 * dayMs

    private fun withApp(test: suspend HttpClient.() -> Unit) {
        val file = Files.createTempFile("rideshare-double-booking-", ".db").toFile().apply { deleteOnExit() }
        testApplication {
            application { module("jdbc:sqlite:${file.absolutePath.replace('\\', '/')}") }
            startApplication()
            client.test()
        }
    }

    private suspend fun HttpClient.ask(at: Long): RideRequestDto {
        val response = post("/requests") {
            header(HttpHeaders.Authorization, "Bearer $passenger")
            contentType(ContentType.Application.Json)
            setBody("""{"originLat":-37.9112,"originLng":145.1029,"destinationLat":-37.9105,"destinationLng":145.1362,
                "isAsap":false,"desiredDepartureTime":$at,"seatsRequested":1}""")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.decodeFromString(response.bodyAsText())
    }

    private suspend fun HttpClient.offer(at: Long): RideDto {
        val response = post("/rides") {
            header(HttpHeaders.Authorization, "Bearer $driver")
            contentType(ContentType.Application.Json)
            setBody("""{"originLat":-37.9112,"originLng":145.1029,"destinationLat":-37.9105,"destinationLng":145.1362,
                "distanceMeters":4000.0,"estimatedDurationSeconds":600,"departureTime":$at,"seatsTotal":3,
                "repeatDaysOfWeek":[],"repeatWeeks":1}""")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.decodeFromString(response.bodyAsText())
    }

    private suspend fun HttpClient.accept(caller: String, requestId: String, targetRideId: String? = null): HttpResponse =
        put("/requests/$requestId") {
            header(HttpHeaders.Authorization, "Bearer $caller")
            contentType(ContentType.Application.Json)
            setBody(if (targetRideId == null) """{"status":"ACCEPTED"}""" else """{"status":"ACCEPTED","targetRideId":"$targetRideId"}""")
        }

    // A pending ask written straight to the database, the way an old duplicate would already exist.
    private fun sneakyDuplicateAsk(id: String, at: Long) = transaction {
        RideRequests.insert {
            it[requestId] = id; it[rideId] = null; it[passengerId] = passenger; it[seatsRequested] = 1
            it[status] = RideRequestStatus.PENDING; it[originLat] = -37.9112; it[originLng] = 145.1029
            it[destinationLat] = -37.9105; it[destinationLng] = 145.1362; it[isAsap] = false
            it[desiredDepartureTime] = at; it[createdAt] = System.currentTimeMillis()
        }
    }

    private fun status(id: String) = transaction {
        RideRequests.selectAll().where { RideRequests.requestId eq id }.first()[RideRequests.status]
    }

    @Test
    fun `an open ask can't be attached to the driver's ride on another day`() = withApp {
        val ride = offer(tripTime)
        val nextWeek = ask(tripTime + 7 * dayMs)
        val refused = accept(driver, nextWeek.requestId, targetRideId = ride.rideId)
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        assertTrue(refused.bodyAsText().contains("Pick a ride near their time"))
        assertEquals(RideRequestStatus.PENDING, status(nextWeek.requestId))

        // Within 45 minutes of the asked-for time is fine.
        val close = ask(tripTime + 20 * 60_000L)
        assertEquals(HttpStatusCode.OK, accept(driver, close.requestId, targetRideId = ride.rideId).status)
    }

    @Test
    fun `a passenger already on a ride can't be added to it again`() = withApp {
        val ride = offer(tripTime)
        val first = ask(tripTime)
        assertEquals(HttpStatusCode.OK, accept(driver, first.requestId, targetRideId = ride.rideId).status)

        sneakyDuplicateAsk("dup-same-ride", tripTime)
        assertEquals(HttpStatusCode.Conflict, accept(driver, "dup-same-ride", targetRideId = ride.rideId).status)
        assertEquals(RideRequestStatus.PENDING, status("dup-same-ride"))
    }

    @Test
    fun `a second driver can't accept the same passenger for the same time`() = withApp {
        val first = ask(tripTime)
        assertEquals(HttpStatusCode.OK, accept(driver, first.requestId).status)

        sneakyDuplicateAsk("dup-other-driver", tripTime + 10 * 60_000L)
        val refused = accept("demo-driver-001", "dup-other-driver")
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertTrue(refused.bodyAsText().contains("already has another trip"))
    }

    @Test
    fun `a passenger can't post the same open request twice for the same time`() = withApp {
        ask(tripTime)
        val again = post("/requests") {
            header(HttpHeaders.Authorization, "Bearer $passenger")
            contentType(ContentType.Application.Json)
            setBody("""{"originLat":-37.9112,"originLng":145.1029,"destinationLat":-37.9105,"destinationLng":145.1362,
                "isAsap":false,"desiredDepartureTime":${tripTime + 15 * 60_000L},"seatsRequested":1}""")
        }
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertTrue(again.bodyAsText().contains("already have a ride request"))

        // Another day is fine, and so is a seat request on a posted ride at the same time.
        ask(tripTime + dayMs)
        val ride = offer(tripTime)
        val seat = post("/rides/${ride.rideId}/requests") {
            header(HttpHeaders.Authorization, "Bearer $passenger")
            contentType(ContentType.Application.Json)
            setBody("""{"seatsRequested":1,"applyToRecurringGroup":false}""")
        }
        assertEquals(HttpStatusCode.OK, seat.status, seat.bodyAsText())
    }
}
