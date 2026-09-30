package com.example.ds04_monash_rideshare_application.backend

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Covers two fixes: (1) a passenger's "3 days next week" standalone ask is now exploded into one
// independent PENDING request per day at creation time, so a driver can accept some days and
// leave others for someone else, instead of one accept locking every day at once; ASAP requests
// never go through this (no scheduling concept applies to them). (2) accepting a request now holds
// a row lock on the target ride while checking seat capacity, so two drivers racing for the same
// last seat can't both win.
class StandaloneRequestSchedulingTest {
    private val passenger = "fake-user-001"
    private val secondPassenger = "demo-passenger-002"
    private val json = Json { ignoreUnknownKeys = true }
    private val dayMs = 24 * 60 * 60 * 1000L

    private fun withApp(test: suspend HttpClient.() -> Unit) {
        val file = Files.createTempFile("rideshare-scheduling-test-", ".db").toFile().apply { deleteOnExit() }
        testApplication {
            application { module("jdbc:sqlite:${file.absolutePath.replace('\\', '/')}") }
            startApplication()
            client.test()
        }
    }

    private suspend fun HttpClient.myRequests(caller: String): List<RideRequestDto> {
        val response = get("/requests/mine") { header(HttpHeaders.Authorization, "Bearer $caller") }
        assertEquals(HttpStatusCode.OK, response.status)
        return json.decodeFromString(response.bodyAsText())
    }

    @Test
    fun `a multi-day standalone ask becomes one independent request per day`() = withApp {
        val start = System.currentTimeMillis() + 2 * dayMs

        val response = post("/requests") {
            header(HttpHeaders.Authorization, "Bearer $passenger")
            contentType(ContentType.Application.Json)
            setBody(
                """{"originLat":-37.9,"originLng":145.1,"destinationLat":-37.91,"destinationLng":145.13,
                    |"isAsap":false,"desiredDepartureTime":$start,"seatsRequested":1,
                    |"repeatDaysOfWeek":[1,2,3,4,5,6,7],"repeatWeeks":1}""".trimMargin()
            )
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val primary = json.decodeFromString<RideRequestDto>(response.bodyAsText())
        assertEquals(RideRequestStatus.PENDING, primary.status)
        assertEquals(start, primary.desiredDepartureTime)

        // All 7 weekdays selected for a 1-week window: the original day plus the 6 days that
        // follow it before the window closes.
        val mine = myRequests(passenger).filter { it.rideId == null }
        assertEquals(7, mine.size)
        assertTrue(mine.all { it.status == RideRequestStatus.PENDING && !it.isAsap })
        val departures = mine.mapNotNull { it.desiredDepartureTime }.sorted()
        assertEquals(7, departures.distinct().size)
        assertEquals(start, departures.first())

        // Accepting one day only accepts that day - every other day stays PENDING for any driver
        // to pick up independently.
        val middle = mine.sortedBy { it.desiredDepartureTime }[3]
        val acceptResponse = put("/requests/${middle.requestId}") {
            header(HttpHeaders.Authorization, "Bearer demo-driver-001")
            contentType(ContentType.Application.Json)
            setBody("""{"status":"ACCEPTED"}""")
        }
        assertEquals(HttpStatusCode.OK, acceptResponse.status)
        assertEquals(RideRequestStatus.ACCEPTED, json.decodeFromString<RideRequestDto>(acceptResponse.bodyAsText()).status)

        val afterAccept = myRequests(passenger).filter { it.rideId == null || it.requestId == middle.requestId }
        assertEquals(1, afterAccept.count { it.requestId == middle.requestId && it.status == RideRequestStatus.ACCEPTED })
        assertEquals(6, afterAccept.count { it.requestId != middle.requestId && it.status == RideRequestStatus.PENDING })
    }

    @Test
    fun `ASAP never explodes into multiple days even if repeat fields are sent`() = withApp {
        val response = post("/requests") {
            header(HttpHeaders.Authorization, "Bearer $passenger")
            contentType(ContentType.Application.Json)
            setBody(
                """{"originLat":-37.9,"originLng":145.1,"destinationLat":-37.91,"destinationLng":145.13,
                    |"isAsap":true,"seatsRequested":1,
                    |"repeatDaysOfWeek":[1,2,3,4,5,6,7],"repeatWeeks":1}""".trimMargin()
            )
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val created = json.decodeFromString<RideRequestDto>(response.bodyAsText())
        assertTrue(created.isAsap)

        val mine = myRequests(passenger).filter { it.rideId == null }
        assertEquals(1, mine.size)
        assertEquals(created.requestId, mine.single().requestId)
    }

    @Test
    fun `two drivers racing for the last seat - only one acceptance wins`() = withApp {
        val rideId = "ride-open-1"
        transaction {
            Rides.update({ Rides.rideId eq rideId }) {
                it[seatsTotal] = 1
                it[seatsAvailable] = 1
            }
        }

        suspend fun request(caller: String): RideRequestDto {
            val response = post("/rides/$rideId/requests") {
                header(HttpHeaders.Authorization, "Bearer $caller")
                contentType(ContentType.Application.Json)
                setBody("""{"seatsRequested":1,"applyToRecurringGroup":false}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            return json.decodeFromString<List<RideRequestDto>>(response.bodyAsText()).single()
        }

        val first = request(passenger)
        val second = request(secondPassenger)

        suspend fun accept(request: RideRequestDto): HttpStatusCode {
            return put("/requests/${request.requestId}") {
                header(HttpHeaders.Authorization, "Bearer demo-driver-001")
                contentType(ContentType.Application.Json)
                setBody("""{"status":"ACCEPTED"}""")
            }.status
        }

        val results = coroutineScope {
            val d1 = async { accept(first) }
            val d2 = async { accept(second) }
            listOf(d1, d2).awaitAll()
        }

        assertEquals(1, results.count { it == HttpStatusCode.OK })
        assertEquals(1, results.count { it == HttpStatusCode.Conflict })

        val rideResponse = get("/rides/$rideId") { header(HttpHeaders.Authorization, "Bearer $passenger") }
        val ride = json.decodeFromString<RideDto>(rideResponse.bodyAsText())
        assertEquals(0, ride.seatsAvailable)
        assertEquals(RideStatus.CONFIRMED, ride.status)

        val acceptedCount = transaction {
            RideRequests.selectAll().where {
                (RideRequests.rideId eq rideId) and (RideRequests.status eq RideRequestStatus.ACCEPTED)
            }.count()
        }
        assertEquals(1L, acceptedCount)
    }
}
