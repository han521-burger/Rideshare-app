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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RequestCancellationTest {
    private val passenger = "fake-user-001"
    private val secondPassenger = "demo-passenger-002"
    private val driver = "demo-driver-002"
    private val otherDriver = "demo-driver-001"
    private val rideId = "ride-open-2"
    private val json = Json { ignoreUnknownKeys = true }

    private fun withRide(capacity: Int = 2, test: suspend HttpClient.() -> Unit) {
        val file = Files.createTempFile("rideshare-cancellation-test-", ".db").toFile().apply {
            deleteOnExit()
        }
        testApplication {
            application { module("jdbc:sqlite:${file.absolutePath.replace('\\', '/')}", testTravelTimes()) }
            startApplication()
            // Adjust only this isolated fixture before any lifecycle operations.
            transaction {
                Rides.update({ Rides.rideId eq rideId }) {
                    it[seatsTotal] = capacity
                    it[seatsAvailable] = capacity
                }
            }
            client.test()
        }
    }

    private suspend fun HttpClient.create(seats: Int = 1, caller: String = passenger): RideRequestDto {
        val response = post("/rides/$rideId/requests") {
            header(HttpHeaders.Authorization, "Bearer $caller")
            contentType(ContentType.Application.Json)
            setBody("""{"seatsRequested":$seats,"applyToRecurringGroup":false}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return createdRequests(response.bodyAsText()).single().also {
            assertEquals(RideRequestStatus.PENDING, it.status)
            assertEquals(seats, it.seatsRequested)
            assertEquals(rideId, it.rideId)
        }
    }

    private suspend fun HttpClient.transition(
        request: RideRequestDto,
        target: String,
        caller: String = passenger,
        expected: HttpStatusCode = HttpStatusCode.OK
    ): RideRequestDto? {
        val response = put("/requests/${request.requestId}") {
            header(HttpHeaders.Authorization, "Bearer $caller")
            contentType(ContentType.Application.Json)
            setBody("""{"status":"$target"}""")
        }
        assertEquals(expected, response.status)
        if (expected != HttpStatusCode.OK) {
            assertTrue(json.decodeFromString<ErrorResponse>(response.bodyAsText()).error.isNotBlank())
            return null
        }
        return json.decodeFromString<RideRequestDto>(response.bodyAsText()).also {
            assertEquals(request.requestId, it.requestId)
            assertEquals(request.rideId, it.rideId)
            assertEquals(target, it.status)
            assertNotNull(it.respondedAt)
        }
    }

    private suspend fun HttpClient.capacity(expected: Int, status: String? = null): RideDto {
        val response = get("/rides/$rideId") {
            header(HttpHeaders.Authorization, "Bearer $passenger")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return json.decodeFromString<RideDto>(response.bodyAsText()).also {
            assertEquals(expected, it.seatsAvailable)
            assertTrue(it.seatsAvailable in 0..it.seatsTotal)
            if (status != null) assertEquals(status, it.status)
        }
    }

    private suspend fun HttpClient.persisted(request: RideRequestDto, caller: String = passenger): RideRequestDto {
        val response = get("/requests/mine") {
            header(HttpHeaders.Authorization, "Bearer $caller")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return json.decodeFromString<List<RideRequestDto>>(response.bodyAsText())
            .single { it.requestId == request.requestId }
            // GET enriches the parent ride; PUT returns only the request fields.
            // Compare persisted request state independently of that live ride projection.
            .also { assertEquals(rideId, it.ride?.rideId) }
            .copy(ride = null)
    }

    @Test
    fun `pending cancellation leaves capacity unchanged`() = withRide {
        val request = create()
        capacity(2, RideStatus.OPEN)
        transition(request, RideRequestStatus.CANCELLED)
        capacity(2, RideStatus.OPEN)
        assertEquals(RideRequestStatus.CANCELLED, persisted(request).status)
    }

    @Test
    fun `final accepted cancellation restores capacity and reopens ride`() = withRide(3) {
        val request = create()
        capacity(3, RideStatus.OPEN)
        transition(request, RideRequestStatus.ACCEPTED, driver)
        capacity(2, RideStatus.CONFIRMED)
        transition(request, RideRequestStatus.CANCELLED)
        assertEquals(RideRequestStatus.CANCELLED, persisted(request).status)
        assertEquals(0L, transaction {
            RideRequests.selectAll().where {
                (RideRequests.rideId eq rideId) and (RideRequests.status eq RideRequestStatus.ACCEPTED)
            }.count()
        })
        capacity(3, RideStatus.OPEN)
    }

    @Test
    fun `multi-seat accepted cancellation restores requested quantity`() = withRide(4) {
        val request = create(2)
        capacity(4)
        transition(request, RideRequestStatus.ACCEPTED, driver)
        capacity(2)
        transition(request, RideRequestStatus.CANCELLED)
        capacity(4, RideStatus.OPEN)
    }

    @Test
    fun `repeated cancellation conflicts without restoring again`() = withRide {
        val request = create()
        transition(request, RideRequestStatus.ACCEPTED, driver)
        capacity(1)
        val cancelled = transition(request, RideRequestStatus.CANCELLED)
        capacity(2, RideStatus.OPEN)
        for (caller in listOf(passenger, driver)) {
            transition(request, RideRequestStatus.CANCELLED, caller, HttpStatusCode.Conflict)
            capacity(2, RideStatus.OPEN)
            assertEquals(cancelled, persisted(request))
        }
    }

    @Test
    fun `rejected request cannot be cancelled or change capacity`() = withRide {
        val request = create()
        val rejected = transition(request, RideRequestStatus.REJECTED, driver)
        capacity(2)
        transition(request, RideRequestStatus.CANCELLED, expected = HttpStatusCode.Conflict)
        capacity(2)
        assertEquals(rejected, persisted(request))
    }

    @Test
    fun `completed request cannot be cancelled or change capacity`() = withRide {
        val request = create()
        transition(request, RideRequestStatus.ACCEPTED, driver)
        capacity(1)
        // Stage 9.1: a seat is completed by its RIDE completing (a request cannot complete itself while the ride is live).
        val ride = serverClockAfterDeparture(rideId).use {
            put("/rides/$rideId") {
                header(HttpHeaders.Authorization, "Bearer $driver"); contentType(ContentType.Application.Json); setBody("""{"status":"COMPLETED"}""")
            }
        }
        assertEquals(HttpStatusCode.OK, ride.status)
        val completed = persisted(request)
        assertEquals(RideRequestStatus.COMPLETED, completed.status)
        transition(request, RideRequestStatus.CANCELLED, expected = HttpStatusCode.Conflict)
        capacity(1, RideStatus.COMPLETED)
        assertEquals(completed, persisted(request))
    }

    @Test
    fun `owning driver can cancel accepted request exactly once`() = withRide {
        val request = create()
        transition(request, RideRequestStatus.ACCEPTED, driver)
        capacity(1)
        val cancelled = transition(request, RideRequestStatus.CANCELLED, driver)
        capacity(2, RideStatus.OPEN)
        transition(request, RideRequestStatus.CANCELLED, driver, HttpStatusCode.Conflict)
        capacity(2, RideStatus.OPEN)
        assertEquals(cancelled, persisted(request))
    }

    @Test
    fun `multiple passengers restore only their own seats`() = withRide(4) {
        val first = create()
        capacity(4)
        transition(first, RideRequestStatus.ACCEPTED, driver)
        capacity(3)
        val second = create(2, secondPassenger)
        capacity(3)
        val acceptedSecond = transition(second, RideRequestStatus.ACCEPTED, driver)
        capacity(1)
        transition(first, RideRequestStatus.CANCELLED)
        capacity(2, RideStatus.CONFIRMED)
        assertEquals(acceptedSecond, persisted(second, secondPassenger))
        // Below total capacity, even a capped duplicate restoration would be wrong.
        transition(first, RideRequestStatus.CANCELLED, expected = HttpStatusCode.Conflict)
        capacity(2)
        assertEquals(acceptedSecond, persisted(second, secondPassenger))
        transition(second, RideRequestStatus.CANCELLED, secondPassenger)
        capacity(4, RideStatus.OPEN)
        assertEquals(RideRequestStatus.CANCELLED, persisted(first).status)
        assertEquals(RideRequestStatus.CANCELLED, persisted(second, secondPassenger).status)
    }

    @Test
    fun `unrelated passenger and driver cannot cancel pending or accepted requests`() = withRide {
        val request = create()
        for (caller in listOf(secondPassenger, otherDriver)) {
            transition(request, RideRequestStatus.CANCELLED, caller, HttpStatusCode.Forbidden)
            capacity(2)
            assertEquals(request, persisted(request))
        }
        val accepted = transition(request, RideRequestStatus.ACCEPTED, driver)
        for (caller in listOf(secondPassenger, otherDriver)) {
            transition(request, RideRequestStatus.CANCELLED, caller, HttpStatusCode.Forbidden)
            capacity(1, RideStatus.CONFIRMED)
            assertEquals(accepted, persisted(request))
        }
    }

    @Test
    fun `duplicate acceptance consumes seats only once before cancellation`() = withRide(3) {
        val request = create(2)
        capacity(3)
        transition(request, RideRequestStatus.ACCEPTED, driver)
        capacity(1)
        transition(request, RideRequestStatus.ACCEPTED, driver, HttpStatusCode.Conflict)
        capacity(1)
        transition(request, RideRequestStatus.CANCELLED)
        capacity(3, RideStatus.OPEN)
    }

    @Test
    fun `pending passenger does not prevent final accepted cancellation reopening`() = withRide(3) {
        val accepted = create()
        transition(accepted, RideRequestStatus.ACCEPTED, driver)
        val pending = create(caller = secondPassenger)
        transition(accepted, RideRequestStatus.CANCELLED)
        assertEquals(pending, persisted(pending, secondPassenger))
        capacity(3, RideStatus.OPEN)
    }

    @Test
    fun `historical request states do not count as accepted passengers`() = withRide(4) {
        val active = create()
        transition(active, RideRequestStatus.ACCEPTED, driver)
        val rejected = create(caller = secondPassenger)
        val rejectedState = transition(rejected, RideRequestStatus.REJECTED, driver)
        val cancelled = create(caller = secondPassenger)
        val cancelledState = transition(cancelled, RideRequestStatus.CANCELLED, secondPassenger)
        val completed = create(caller = secondPassenger)
        transition(completed, RideRequestStatus.ACCEPTED, driver)
        // Stage 9.1: one seat can no longer be completed through the API while the ride is live (RIDE_NOT_COMPLETED), and
        // completing the ride would complete `active` too. This test is about how a COMPLETED row is counted for capacity,
        // so the row is written directly.
        setStatus(completed.requestId, RideRequestStatus.COMPLETED)
        val completedState = persisted(completed, secondPassenger)
        capacity(2, RideStatus.CONFIRMED)
        transition(active, RideRequestStatus.CANCELLED)
        // Completed bookings keep their consumed seats; only the cancelled seat is restored.
        capacity(3, RideStatus.OPEN)
        assertEquals(rejectedState, persisted(rejected, secondPassenger))
        assertEquals(cancelledState, persisted(cancelled, secondPassenger))
        assertEquals(completedState, persisted(completed, secondPassenger))
    }

    @Test
    fun `pending cancellation leaves confirmed ride and accepted passenger unchanged`() = withRide(3) {
        val active = create()
        val accepted = transition(active, RideRequestStatus.ACCEPTED, driver)
        val pending = create(caller = secondPassenger)
        transition(pending, RideRequestStatus.CANCELLED, secondPassenger)
        capacity(2, RideStatus.CONFIRMED)
        assertEquals(accepted, persisted(active))
        assertEquals(RideRequestStatus.CANCELLED, persisted(pending, secondPassenger).status)
    }

    @Test
    fun `reopening failure rolls back request capacity and ride status`() = withRide(3) {
        val request = create()
        val accepted = transition(request, RideRequestStatus.ACCEPTED, driver)
        val beforeRide = capacity(2, RideStatus.CONFIRMED)
        transaction {
            exec("""CREATE TRIGGER fail_reopening BEFORE UPDATE OF status ON rides
                WHEN NEW.ride_id = '$rideId' AND NEW.status = 'OPEN'
                BEGIN SELECT RAISE(ABORT, 'test reopening failure'); END""")
        }
        transition(request, RideRequestStatus.CANCELLED, expected = HttpStatusCode.InternalServerError)
        assertEquals(accepted, persisted(request))
        assertEquals(beforeRide, capacity(2, RideStatus.CONFIRMED))
    }

    @Test
    fun `terminal ride cancellation attempts never reopen or restore capacity`() = withRide(3) {
        for (terminal in listOf(RideStatus.CANCELLED, RideStatus.COMPLETED)) {
            // Reset only this isolated fixture between the two terminal lifecycle scenarios.
            transaction {
                Rides.update({ Rides.rideId eq rideId }) { it[status] = RideStatus.OPEN }
            }
            val request = create()
            transition(request, RideRequestStatus.ACCEPTED, driver)
            val response = serverClockAfterDeparture(rideId).use {
                put("/rides/$rideId") {
                    header(HttpHeaders.Authorization, "Bearer $driver")
                    contentType(ContentType.Application.Json)
                    setBody("""{"status":"$terminal"}""")
                }
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val before = persisted(request)
            val beforeRide = capacity(if (terminal == RideStatus.CANCELLED) 2 else 1, terminal)
            transition(request, RideRequestStatus.CANCELLED, expected = HttpStatusCode.Conflict)
            assertEquals(before, persisted(request))
            assertEquals(beforeRide, capacity(beforeRide.seatsAvailable, terminal))
        }
    }

    @Test
    fun `reopened ride accepts another passenger normally`() = withRide(3) {
        val first = create()
        transition(first, RideRequestStatus.ACCEPTED, driver)
        transition(first, RideRequestStatus.CANCELLED)
        capacity(3, RideStatus.OPEN)
        val next = create(caller = secondPassenger)
        transition(next, RideRequestStatus.ACCEPTED, driver)
        capacity(2, RideStatus.CONFIRMED)
        assertEquals(RideRequestStatus.CANCELLED, persisted(first).status)
        assertEquals(RideRequestStatus.ACCEPTED, persisted(next, secondPassenger).status)
    }
}
