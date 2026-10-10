package com.example.ds04_monash_rideshare_application.backend

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Simultaneous requests must produce a consistent outcome because the passenger's scheduling state
// is locked for the whole check-and-write (Concurrency.kt) - not because a busy database happened
// to fail one attempt and Exposed happened to retry it. Retries are switched off in
// Database.kt, so a lock failure here would surface as a 5xx and fail these tests.
class SchedulingConcurrencyTest {
    private val t = futureDeparture()
    private val callers = 8

    private fun statuses(results: List<HttpStatusCode>) = results.groupingBy { it }.eachCount()

    @Test
    fun databaseIsConfiguredToWaitForTheWriteLockNotToRetry() = withScheduling {
        val busyTimeout = transaction { exec("PRAGMA busy_timeout") { it.next(); it.getInt(1) } }
        assertEquals(SchedulingConfig.DB_BUSY_TIMEOUT_MILLIS, busyTimeout)
        assertEquals(0, TransactionManager.manager.defaultRepetitionAttempts)
    }

    @Test
    fun simultaneousIdenticalPostsMakeExactlyOneRequest() = withScheduling {
        val responses = coroutineScope {
            (1..callers).map { async(Dispatchers.Default) { postOpen(openRequestBody(at = t)) } }.awaitAll()
        }
        val counts = statuses(responses.map { it.status })
        assertEquals(1, counts[HttpStatusCode.OK], "statuses: $counts")
        assertEquals(callers - 1, counts[HttpStatusCode.Conflict], "statuses: $counts")
        assertTrue(responses.none { it.status.value >= 500 }, "statuses: $counts")

        val winnerId = responses.single { it.status == HttpStatusCode.OK }.asRequest().requestId
        responses.filter { it.status == HttpStatusCode.Conflict }.forEach {
            val error = it.asError()
            assertEquals(CONFLICT_DUPLICATE_OPEN_REQUEST, error.code)
            assertEquals(winnerId, error.existingRequestId)
        }
        assertEquals(1, requestRows())
        assertEquals(1, driverFeedOpenAsks().size)
    }

    @Test
    fun simultaneousPostsWithTheSameKeyAllGetTheSameAnswerAndOneRequest() = withScheduling {
        val responses = coroutineScope {
            (1..callers).map { async(Dispatchers.Default) { postOpen(openRequestBody(at = t, clientRequestId = "shared-key-0001")) } }.awaitAll()
        }
        assertTrue(responses.all { it.status == HttpStatusCode.OK }, statuses(responses.map { it.status }).toString())
        assertEquals(1, responses.map { it.asRequest().requestId }.toSet().size)
        assertEquals(1, requestRows())
    }

    @Test
    fun simultaneousIncompatibleRequestsLeaveOnlyAConsistentSchedule() = withScheduling {
        // Different departures, 3 minutes apart (all within 21 minutes, so every pair overlaps under the
        // current time rule): none is a duplicate of another, but a passenger cannot hold any two of them
        // (the physical-feasibility rule replaces this one later). Whichever lands first wins - the rest
        // must see it and be refused, never pass against a stale snapshot.
        val responses = coroutineScope {
            (0 until callers).map { i ->
                async(Dispatchers.Default) { postOpen(openRequestBody(at = t + i * 3 * MINUTE)) }
            }.awaitAll()
        }
        val counts = statuses(responses.map { it.status })
        assertEquals(1, counts[HttpStatusCode.OK], "statuses: $counts")
        assertEquals(callers - 1, counts[HttpStatusCode.Conflict], "statuses: $counts")
        assertEquals(1, liveOpenRows())
        // Refusals are schedule conflicts, not duplicates.
        responses.filter { it.status == HttpStatusCode.Conflict }.forEach {
            assertTrue(it.asError().code != CONFLICT_DUPLICATE_OPEN_REQUEST)
        }
    }

    @Test
    fun differentPassengersDoNotBlockEachOther() = withScheduling {
        val responses = coroutineScope {
            listOf(PASSENGER, OTHER_PASSENGER).map { who ->
                async(Dispatchers.Default) { postOpen(openRequestBody(at = t), who) }
            }.awaitAll()
        }
        assertTrue(responses.all { it.status == HttpStatusCode.OK })
        assertEquals(2, driverFeedOpenAsks(DRIVER_A, PASSENGER).size + driverFeedOpenAsks(DRIVER_A, OTHER_PASSENGER).size)
    }

    @Test
    fun simultaneousSeatRequestsForTheSameRideMakeExactlyOne() = withScheduling {
        val ride = offerRide(DRIVER_A, t)
        val responses = coroutineScope {
            (1..callers).map { async(Dispatchers.Default) { postSeat(ride.rideId) } }.awaitAll()
        }
        val counts = statuses(responses.map { it.status })
        assertEquals(1, counts[HttpStatusCode.OK], "statuses: $counts")
        assertEquals(callers - 1, counts[HttpStatusCode.Conflict], "statuses: $counts")
        assertEquals(1, requestRows())
    }

    @Test
    fun twoDriversAcceptingTheSameOpenRequestAtOnceOnlyOneWins() = withScheduling {
        val ask = postOpen(openRequestBody(at = t)).asRequest()
        val results = coroutineScope {
            listOf(DRIVER_A, DRIVER_B).map { driver ->
                async(Dispatchers.Default) { respond(driver, ask.requestId, RideRequestStatus.ACCEPTED) }
            }.awaitAll()
        }
        val counts = statuses(results.map { it.status })
        assertEquals(1, counts[HttpStatusCode.OK], "statuses: $counts ${results.map { it.bodyAsText() }}")
        assertEquals(1, counts[HttpStatusCode.Conflict], "statuses: $counts")
        assertEquals(RideRequestStatus.ACCEPTED, requestById(ask.requestId)!!.status)
    }

    @Test
    fun acceptingAndCancellingTheSameRequestAtOnceEndsInOneConsistentState() = withScheduling {
        val ask = postOpen(openRequestBody(at = t)).asRequest()
        val results = coroutineScope {
            listOf(
                async(Dispatchers.Default) { respond(DRIVER_A, ask.requestId, RideRequestStatus.ACCEPTED) },
                async(Dispatchers.Default) { respond(PASSENGER, ask.requestId, RideRequestStatus.CANCELLED) }
            ).awaitAll()
        }
        assertTrue(results.none { it.status.value >= 500 }, results.map { it.status }.toString())
        val final = requestById(ask.requestId)!!
        // Whichever order they ran in, the request ends in one coherent state and no seat is leaked:
        // an accepted-then-cancelled request has its seat given back to the ride.
        if (final.status == RideRequestStatus.CANCELLED && final.rideId != null) {
            val (total, available) = rideSeats(final.rideId!!)
            assertEquals(total, available)
        }
    }
}
