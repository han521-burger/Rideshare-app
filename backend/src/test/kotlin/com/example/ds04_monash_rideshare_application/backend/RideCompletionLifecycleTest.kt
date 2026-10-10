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

class RideCompletionLifecycleTest {
    private val rideId = "ride-open-2"
    private val driver = "demo-driver-002"
    private val json = Json { ignoreUnknownKeys = true }
    private val oldTimestamp = 1_000L

    private fun withRide(
        status: String = RideStatus.CONFIRMED,
        states: List<String> = listOf(RideRequestStatus.ACCEPTED),
        test: suspend HttpClient.() -> Unit
    ) {
        val file = Files.createTempFile("rideshare-completion-", ".db").toFile().apply { deleteOnExit() }
        testApplication {
            application { module("jdbc:sqlite:${file.absolutePath.replace('\\', '/')}", testTravelTimes()) }
            startApplication()
            transaction {
                Rides.update({ Rides.rideId eq rideId }) {
                    it[Rides.status] = status
                    it[seatsTotal] = 4
                    it[seatsAvailable] = 4 - states.count { state ->
                        state == RideRequestStatus.ACCEPTED || state == RideRequestStatus.COMPLETED
                    }
                }
                states.forEachIndexed { index, state ->
                    val user = "completion-passenger-$index"
                    Users.insert {
                        it[userId] = user
                        it[externalSubjectId] = user
                        it[emailAccount] = "$user@example.test"
                        it[displayName] = "Completion fixture $index"
                        it[accountStatus] = "ACTIVE"
                        it[createdAt] = oldTimestamp
                    }
                    RideRequests.insert {
                        it[requestId] = "completion-$index"
                        it[RideRequests.rideId] = this@RideCompletionLifecycleTest.rideId
                        it[passengerId] = user
                        it[seatsRequested] = 1
                        it[RideRequests.status] = state
                        it[createdAt] = oldTimestamp
                        it[respondedAt] = if (state == RideRequestStatus.PENDING) null else oldTimestamp
                        it[note] = "Preserve historical fields"
                    }
                }
            }
            // Completing needs the ride to have started (RIDE_NOT_STARTED, Stage 9). The fixture ride leaves tomorrow, and its
            // pending requests must not be expired by the real clock, so the SERVER clock is pinned to just after departure.
            serverClockAfterDeparture(rideId).use { client.test() }
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

    private suspend fun HttpClient.updateRide(
        body: String = """{"status":"COMPLETED"}""",
        caller: String = driver
    ) = put("/rides/$rideId") {
        header(HttpHeaders.Authorization, "Bearer $caller")
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpClient.complete() {
        val beforeRide = ride()
        val before = requests()
        // Stage 9: the server clock is pinned (withRide), so the completion time is exact: the ride records it as completedAt
        // and the requests it completes or cancels carry it as respondedAt.
        val completionTime = ServerClock.now()
        val response = updateRide()
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(RideStatus.COMPLETED, json.decodeFromString<RideDto>(response.bodyAsText()).status)
        assertEquals(beforeRide.copy(status = RideStatus.COMPLETED, completedAt = completionTime), ride())
        val after = requests()
        assertEquals(before.keys, after.keys)
        before.forEach { (id, request) ->
            val target = if (request.rideId != rideId) request.status else when (request.status) {
                RideRequestStatus.PENDING -> RideRequestStatus.CANCELLED
                RideRequestStatus.ACCEPTED -> RideRequestStatus.COMPLETED
                else -> request.status
            }
            val actual = after.getValue(id)
            if (target != request.status) {
                val responded = assertNotNull(actual.respondedAt)
                assertEquals(completionTime, responded)
                assertEquals(request.copy(status = target, respondedAt = responded), actual)
            } else assertEquals(request, actual)
        }
    }

    @Test
    fun `accepted request completes with ride and is visible to passenger`() = withRide {
        complete()
        val response = get("/requests/mine") {
            header(HttpHeaders.Authorization, "Bearer completion-passenger-0")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val request = json.decodeFromString<List<RideRequestDto>>(response.bodyAsText()).single()
        assertEquals(RideRequestStatus.COMPLETED, request.status)
        assertEquals(RideStatus.COMPLETED, request.ride?.status)
    }

    @Test
    fun `multiple accepted requests complete together`() = withRide(
        states = listOf(RideRequestStatus.ACCEPTED, RideRequestStatus.ACCEPTED)
    ) { complete() }

    @Test
    fun `completion cancels pending requests while completing accepted ones`() = withRide(
        states = listOf(RideRequestStatus.ACCEPTED, RideRequestStatus.PENDING)
    ) { complete() }

    @Test
    fun `mixed completion preserves all terminal request history`() = withRide(
        states = listOf(RideRequestStatus.PENDING, RideRequestStatus.ACCEPTED, RideRequestStatus.REJECTED,
            RideRequestStatus.CANCELLED, RideRequestStatus.COMPLETED)
    ) { complete() }

    @Test
    fun `open ride can complete and closes pending requests`() = withRide(
        status = RideStatus.OPEN, states = listOf(RideRequestStatus.PENDING)
    ) { complete() }

    @Test
    fun `completed ride cannot be cancelled`() = withRide(
        status = RideStatus.COMPLETED, states = listOf(RideRequestStatus.COMPLETED)
    ) {
        val beforeRide = ride()
        val before = requests()
        assertEquals(HttpStatusCode.Conflict, updateRide("""{"status":"CANCELLED"}""").status)
        assertEquals(beforeRide, ride())
        assertEquals(before, requests())
    }

    @Test
    fun `cancelled ride cannot be completed`() = withRide(
        status = RideStatus.CANCELLED, states = listOf(RideRequestStatus.CANCELLED)
    ) {
        val beforeRide = ride()
        val before = requests()
        assertEquals(HttpStatusCode.Conflict, updateRide().status)
        assertEquals(beforeRide, ride())
        assertEquals(before, requests())
    }

    @Test
    fun `unrelated users cannot complete rides`() = withRide {
        val beforeRide = ride()
        val before = requests()
        for (caller in listOf("fake-user-001", "demo-driver-001", "completion-passenger-0")) {
            assertEquals(HttpStatusCode.Forbidden, updateRide(caller = caller).status)
            assertEquals(beforeRide, ride())
            assertEquals(before, requests())
        }
    }

    @Test
    fun `completion propagation failure rolls back every update`() = withRide(
        states = listOf(RideRequestStatus.ACCEPTED, RideRequestStatus.PENDING)
    ) {
        val beforeRide = ride()
        val before = requests()
        transaction {
            exec("""CREATE TRIGGER fail_completion BEFORE UPDATE OF status ON ride_requests
                WHEN NEW.request_id = 'completion-1' AND NEW.status = 'CANCELLED'
                BEGIN SELECT RAISE(ABORT, 'test completion failure'); END""")
        }
        assertEquals(HttpStatusCode.InternalServerError, updateRide().status)
        assertEquals(beforeRide, ride())
        assertEquals(before, requests())
    }

    private fun unrelatedRequests(recurring: Boolean) = transaction {
        if (recurring) {
            Rides.update({ Rides.rideId eq rideId }) { it[recurringGroupId] = "completion-group" }
            Rides.update({ Rides.rideId eq "ride-open-1" }) { it[recurringGroupId] = "completion-group" }
        }
        for ((id, parent) in listOf("unrelated" to "ride-open-1", "standalone" to null)) {
            RideRequests.insert {
                it[requestId] = id
                it[RideRequests.rideId] = parent
                it[passengerId] = "fake-user-001"
                it[seatsRequested] = 1
                it[status] = RideRequestStatus.PENDING
                it[createdAt] = oldTimestamp
            }
        }
    }

    @Test
    fun `other rides and standalone requests remain unchanged`() = withRide {
        unrelatedRequests(recurring = false)
        val otherRide = ride("ride-open-1")
        complete()
        assertEquals(otherRide, ride("ride-open-1"))
    }

    @Test
    fun `recurring sibling remains unchanged`() = withRide {
        unrelatedRequests(recurring = true)
        val sibling = ride("ride-open-1")
        complete()
        assertEquals(sibling, ride("ride-open-1"))
    }

    @Test
    fun `repeated completion preserves request metadata and capacity`() = withRide {
        complete()
        val completedRide = ride()
        val completedRequests = requests()
        assertEquals(HttpStatusCode.OK, updateRide().status)
        assertEquals(completedRide, ride())
        assertEquals(completedRequests, requests())
    }

    @Test
    fun `same terminal retries perform no writes even with additional update fields`() {
        for (terminal in listOf(RideStatus.COMPLETED, RideStatus.CANCELLED)) {
            withRide(status = terminal, states = listOf(terminal)) {
                val beforeRide = ride()
                val before = requests()
                transaction {
                    exec("CREATE TRIGGER block_ride_write BEFORE UPDATE ON rides BEGIN SELECT RAISE(ABORT, 'unexpected write'); END")
                    exec("CREATE TRIGGER block_request_write BEFORE UPDATE ON ride_requests BEGIN SELECT RAISE(ABORT, 'unexpected write'); END")
                }
                val response = updateRide("""{"status":"$terminal","seatsTotal":8,"notes":"stale edit","driverEnRoute":true}""")
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals(beforeRide, ride())
                assertEquals(before, requests())
            }
        }
    }

    @Test
    fun `terminal rides reject edits without a status`() {
        for (terminal in listOf(RideStatus.COMPLETED, RideStatus.CANCELLED)) {
            withRide(status = terminal, states = listOf(terminal)) {
                val beforeRide = ride()
                val before = requests()
                assertEquals(HttpStatusCode.Conflict, updateRide("""{"notes":"changed","seatsTotal":8}""").status)
                assertEquals(beforeRide, ride())
                assertEquals(before, requests())
            }
        }
    }

    @Test
    fun `ride transition matrix protects terminal states and confirmed rides`() {
        val statuses = listOf(RideStatus.OPEN, RideStatus.CONFIRMED, RideStatus.CANCELLED, RideStatus.COMPLETED)
        val failures = mutableListOf<String>()
        for (from in statuses) for (to in statuses) {
            withRide(status = from, states = emptyList()) {
                val beforeRide = ride()
                val allowed = from == to || from == RideStatus.OPEN ||
                    (from == RideStatus.CONFIRMED && to != RideStatus.OPEN)
                val expected = if (allowed) HttpStatusCode.OK else HttpStatusCode.Conflict
                val actual = updateRide("""{"status":"$to"}""").status
                if (actual != expected) failures += "$from -> $to: expected $expected, got $actual"
                // Only a real transition INTO COMPLETED is stamped (completedAt); COMPLETED -> COMPLETED is the no-write retry.
                val stamped = if (allowed && to == RideStatus.COMPLETED && from != RideStatus.COMPLETED) ServerClock.now() else null
                if (actual == expected) assertEquals(beforeRide.copy(status = if (allowed) to else from, completedAt = stamped), ride())
            }
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun `unknown ride status is rejected without mutation`() = withRide {
        val beforeRide = ride()
        val before = requests()
        assertEquals(HttpStatusCode.BadRequest, updateRide("""{"status":"UNKNOWN"}""").status)
        assertEquals(beforeRide, ride())
        assertEquals(before, requests())
    }

    @Test
    fun `active ride cancellation continues to cancel all active bookings`() = withRide(
        states = listOf(RideRequestStatus.PENDING, RideRequestStatus.ACCEPTED)
    ) {
        val beforeRide = ride()
        assertEquals(HttpStatusCode.OK, updateRide("""{"status":"CANCELLED"}""").status)
        assertEquals(beforeRide.copy(status = RideStatus.CANCELLED), ride())
        assertTrue(requests().values.filter { it.rideId == rideId }.all { it.status == RideRequestStatus.CANCELLED })
    }

    @Test
    fun `active ride metadata edits remain available`() = withRide {
        val beforeRide = ride()
        val before = requests()
        assertEquals(HttpStatusCode.OK, updateRide("""{"notes":"new pickup note"}""").status)
        assertEquals(beforeRide.copy(notes = "new pickup note"), ride())
        assertEquals(before, requests())
    }
}
