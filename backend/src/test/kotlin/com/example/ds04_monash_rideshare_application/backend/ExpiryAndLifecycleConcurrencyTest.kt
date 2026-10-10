package com.example.ds04_monash_rideshare_application.backend

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Expiry and ride lifecycle changes go through the same scheduling lock as everything else, so they
// race with create / accept / cancel / complete the same way those race each other: one after the
// other, never interleaved, and always ending in a coherent state.
class ExpiryAndLifecycleConcurrencyTest {
    private val future = futureDeparture()

    private suspend fun HttpClient.getMine(caller: String = PASSENGER): HttpResponse =
        get("/requests/mine") { header(HttpHeaders.Authorization, "Bearer $caller") }

    private suspend fun HttpClient.updateRide(caller: String, rideId: String, status: String): HttpResponse =
        put("/rides/$rideId") {
            header(HttpHeaders.Authorization, "Bearer $caller")
            contentType(ContentType.Application.Json)
            setBody("""{"status":"$status"}""")
        }

    private fun now() = System.currentTimeMillis()

    // --- the approved expiry policy, unchanged ---

    @Test
    fun aScheduledPendingRequestExpiresAtItsDeparture() = withScheduling {
        val departure = now() + 2 * DAY
        insertOpenAsk("sched-1", desiredDeparture = departure, createdAt = now())
        expireStaleRecords(now = departure)             // exactly at departure: still waiting
        assertEquals(RideRequestStatus.PENDING, requestStatus("sched-1"))
        expireStaleRecords(now = departure + 1)         // just after: expired
        assertEquals(RideRequestStatus.EXPIRED, requestStatus("sched-1"))
    }

    @Test
    fun anAsapPendingRequestExpiresThirtyMinutesAfterItWasMade() = withScheduling {
        val created = now()
        insertOpenAsk("asap-1", desiredDeparture = null, createdAt = created, asap = true)
        expireStaleRecords(now = created + 29 * MINUTE)
        assertEquals(RideRequestStatus.PENDING, requestStatus("asap-1"))
        expireStaleRecords(now = created + 30 * MINUTE)
        assertEquals(RideRequestStatus.PENDING, requestStatus("asap-1"))
        expireStaleRecords(now = created + 30 * MINUTE + 1)
        assertEquals(RideRequestStatus.EXPIRED, requestStatus("asap-1"))
    }

    @Test
    fun expiryOnlyTouchesWhatIsStale() = withScheduling {
        insertOpenAsk("stale", desiredDeparture = now() - 5 * MINUTE, createdAt = now() - DAY)
        insertOpenAsk("fresh", desiredDeparture = future, createdAt = now())
        expireStaleRecords()
        assertEquals(RideRequestStatus.EXPIRED, requestStatus("stale"))
        assertEquals(RideRequestStatus.PENDING, requestStatus("fresh"))
    }

    @Test
    fun runningExpiryAgainChangesNothingFurther() = withScheduling {
        insertOpenAsk("stale", desiredDeparture = now() - 5 * MINUTE, createdAt = now() - DAY)
        expireStaleRecords(); expireStaleRecords(); expireStaleRecords()
        assertEquals(RideRequestStatus.EXPIRED, requestStatus("stale"))
    }

    // --- create racing expiry ---

    @Test
    fun creatingARequestWhileStaleOnesExpireLosesNothing() = withScheduling {
        insertOpenAsk("old-1", desiredDeparture = now() - 10 * MINUTE, createdAt = now() - DAY)
        insertOpenAsk("old-2", desiredDeparture = null, createdAt = now() - 3 * 60 * MINUTE, asap = true)
        val responses = coroutineScope {
            val create = async(Dispatchers.Default) { postOpen(openRequestBody(at = future)) }
            val reads = (1..10).map { async(Dispatchers.Default) { getMine() } }
            listOf(create) .plus(reads).awaitAll()
        }
        assertTrue(responses.none { it.status.value >= 500 }, responses.map { it.status }.toString())
        assertEquals(HttpStatusCode.OK, responses.first().status)
        val created = responses.first().asRequest()
        assertEquals(RideRequestStatus.PENDING, requestStatus(created.requestId))
        assertEquals(RideRequestStatus.EXPIRED, requestStatus("old-1"))
        assertEquals(RideRequestStatus.EXPIRED, requestStatus("old-2"))
    }

    @Test
    fun manyCreatesRacingExpiryStillMakeExactlyOneRequest() = withScheduling {
        insertOpenAsk("old-1", desiredDeparture = now() - 10 * MINUTE, createdAt = now() - DAY)
        val responses = coroutineScope {
            (1..8).map { async(Dispatchers.Default) { postOpen(openRequestBody(at = future)) } }.awaitAll()
        }
        assertTrue(responses.none { it.status.value >= 500 })
        assertEquals(1, responses.count { it.status == HttpStatusCode.OK })
        assertEquals(RideRequestStatus.EXPIRED, requestStatus("old-1"))
        assertEquals(1, liveOpenRows())
    }

    // --- accept racing expiry ---

    @Test
    fun acceptingAnAlreadyStaleRequestNeverSucceedsWhateverTheTiming() = withScheduling {
        insertOpenAsk("stale-ask", desiredDeparture = now() - 2 * MINUTE, createdAt = now() - DAY)
        val rideRowsBefore = transaction { Rides.selectAll().count() }
        val responses = coroutineScope {
            val accepts = listOf(DRIVER_A, DRIVER_B).map { d -> async(Dispatchers.Default) { respond(d, "stale-ask", RideRequestStatus.ACCEPTED) } }
            val reads = (1..6).map { async(Dispatchers.Default) { getMine() } }
            (accepts + reads).awaitAll()
        }
        assertTrue(responses.none { it.status.value >= 500 }, responses.map { it.status }.toString())
        // Both accepts refused (expired), request expired, no ride was created for it.
        assertTrue(responses.take(2).all { it.status == HttpStatusCode.Conflict }, responses.take(2).map { it.status }.toString())
        assertEquals(RideRequestStatus.EXPIRED, requestStatus("stale-ask"))
        assertEquals(rideRowsBefore, transaction { Rides.selectAll().count() })
    }

    @Test
    fun acceptingAFreshRequestWhileOthersExpireStillWorks() = withScheduling {
        val fresh = postOpen(openRequestBody(at = future)).asRequest()
        insertOpenAsk("stale-other", passengerId = OTHER_PASSENGER, desiredDeparture = now() - 5 * MINUTE, createdAt = now() - DAY)
        val responses = coroutineScope {
            val accept = async(Dispatchers.Default) { respond(DRIVER_A, fresh.requestId, RideRequestStatus.ACCEPTED) }
            val reads = (1..6).map { async(Dispatchers.Default) { getMine(OTHER_PASSENGER) } }
            (listOf(accept) + reads).awaitAll()
        }
        assertEquals(HttpStatusCode.OK, responses.first().status)
        assertEquals(RideRequestStatus.ACCEPTED, requestStatus(fresh.requestId))
        assertEquals(RideRequestStatus.EXPIRED, requestStatus("stale-other"))
    }

    // --- cancellation / completion racing expiry ---

    private fun pastRideWithAcceptedPassenger(rideId: String = "past-ride", reqId: String = "past-req") {
        // Left 3 hours ago, 10 minute trip: long over, so lazy expiry will complete it.
        insertRideRow(rideId, DRIVER_B, departure = now() - 3 * 60 * MINUTE)
        insertAttachedRequest(reqId, PASSENGER, rideId, RideRequestStatus.ACCEPTED, now() - DAY)
    }

    @Test
    fun expiryCompletesAFinishedRideAndItsPassengersTogether() = withScheduling {
        pastRideWithAcceptedPassenger()
        expireStaleRecords()
        assertEquals(RideStatus.COMPLETED, rideStatus("past-ride"))
        assertEquals(RideRequestStatus.COMPLETED, requestStatus("past-req"))
    }

    @Test
    fun cancellingAFinishedRideRacingExpiryEndsCoherent() = withScheduling {
        pastRideWithAcceptedPassenger()
        val responses = coroutineScope {
            val cancel = async(Dispatchers.Default) { updateRide(DRIVER_B, "past-ride", RideStatus.CANCELLED) }
            val reads = (1..6).map { async(Dispatchers.Default) { getMine() } }
            (listOf(cancel) + reads).awaitAll()
        }
        assertTrue(responses.none { it.status.value >= 500 }, responses.map { it.status }.toString())
        // Either it was cancelled first or expiry completed it first - never a mix of the two.
        val ride = rideStatus("past-ride"); val request = requestStatus("past-req")
        assertTrue(
            (ride == RideStatus.COMPLETED && request == RideRequestStatus.COMPLETED) ||
                (ride == RideStatus.CANCELLED && request == RideRequestStatus.CANCELLED),
            "ride=$ride request=$request"
        )
    }

    @Test
    fun completingAFinishedRideRacingExpiryEndsCompletedOnce() = withScheduling {
        pastRideWithAcceptedPassenger()
        val responses = coroutineScope {
            val complete = async(Dispatchers.Default) { updateRide(DRIVER_B, "past-ride", RideStatus.COMPLETED) }
            val reads = (1..6).map { async(Dispatchers.Default) { getMine() } }
            (listOf(complete) + reads).awaitAll()
        }
        assertTrue(responses.none { it.status.value >= 500 }, responses.map { it.status }.toString())
        assertEquals(RideStatus.COMPLETED, rideStatus("past-ride"))
        assertEquals(RideRequestStatus.COMPLETED, requestStatus("past-req"))
    }

    // --- ride lifecycle under the lock ---

    @Test
    fun cancellingARideRacingSeatRequestsLeavesNoLiveRequestOnIt() = withScheduling {
        repeat(5) { round ->
            val ride = offerRide(DRIVER_A, future + round * DAY)
            val outcomes = coroutineScope {
                val cancel = async(Dispatchers.Default) { updateRide(DRIVER_A, ride.rideId, RideStatus.CANCELLED) }
                val seats = listOf(PASSENGER, OTHER_PASSENGER).map { who -> async(Dispatchers.Default) { postSeat(ride.rideId, caller = who) } }
                (listOf(cancel) + seats).awaitAll()
            }
            assertTrue(outcomes.none { it.status.value >= 500 }, outcomes.map { it.status }.toString())
            assertEquals(RideStatus.CANCELLED, rideStatus(ride.rideId))
            val stillLive = transaction {
                RideRequests.selectAll().where { RideRequests.rideId eq ride.rideId }
                    .count { it[RideRequests.status] in LIVE_REQUEST_STATUSES }
            }
            assertEquals(0, stillLive, "round $round: live requests left on a cancelled ride")
        }
    }

    @Test
    fun aPassengerCancellingWhileTheDriverCancelsTheRideReturnsNoSeatTwice() = withScheduling {
        val ride = offerRide(DRIVER_A, future)
        val ask = postOpen(openRequestBody(at = future)).asRequest()
        assertEquals(HttpStatusCode.OK, respond(DRIVER_A, ask.requestId, RideRequestStatus.ACCEPTED, targetRideId = ride.rideId).status)
        val (total, availableAfterAccept) = rideSeats(ride.rideId)
        assertEquals(total - 1, availableAfterAccept)

        val responses = coroutineScope {
            listOf(
                async(Dispatchers.Default) { respond(PASSENGER, ask.requestId, RideRequestStatus.CANCELLED) },
                async(Dispatchers.Default) { updateRide(DRIVER_A, ride.rideId, RideStatus.CANCELLED) }
            ).awaitAll()
        }
        assertTrue(responses.none { it.status.value >= 500 }, responses.map { it.status }.toString())
        assertEquals(RideRequestStatus.CANCELLED, requestStatus(ask.requestId))
        assertEquals(RideStatus.CANCELLED, rideStatus(ride.rideId))
        val (_, available) = rideSeats(ride.rideId)
        assertTrue(available <= total, "seats $available of $total: a seat was returned twice")
    }

    // --- lock discovery ---

    @Test
    fun aPersonWhoAppearsWhileLockingIsLockedBeforeTheWorkRuns() = withScheduling {
        val calls = AtomicInteger()
        var lockedWhenWorkRan: Set<String>? = null
        // First look finds only A; by the second look (under A's lock) B has appeared too.
        val result = schedulingTransactionFor(
            usersOf = { people: Set<String> -> people },
            discover = { if (calls.incrementAndGet() == 1) setOf("fake-user-001") else setOf("fake-user-001", PASSENGER) },
            block = { people -> lockedWhenWorkRan = people; "done" }
        )
        assertEquals("done", result)
        assertEquals(setOf("fake-user-001", PASSENGER), lockedWhenWorkRan)
        assertTrue(calls.get() >= 3, "discovery ran ${calls.get()} times")
    }

    @Test
    fun lockDiscoveryGivesUpRatherThanActOnAnUnlockedPerson() = withScheduling {
        val calls = AtomicInteger()
        var ran = false
        val owners = listOf("fake-user-001", PASSENGER, DRIVER_A, DRIVER_B)
        // Every look finds one more person than the last: the set never settles.
        assertFailsWith<ConflictException> {
            schedulingTransactionFor(
                usersOf = { people: Set<String> -> people },
                discover = { owners.take(1 + calls.getAndIncrement() % owners.size).toSet().let { it + "extra-${calls.get()}" } },
                block = { ran = true }
            )
        }
        assertEquals(false, ran)
    }
}
