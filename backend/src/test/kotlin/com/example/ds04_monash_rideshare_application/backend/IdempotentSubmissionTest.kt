package com.example.ds04_monash_rideshare_application.backend

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

// A client that never saw the answer to a create call must be able to send the SAME clientRequestId
// again and get the original result - never a second request, never a failure for a request that
// was in fact saved.
class IdempotentSubmissionTest {
    private val t = futureDeparture()
    private val key = "client-key-0001"

    @Test
    fun lostResponseRetryReturnsTheOriginalRequestAndAddsNothing() = withScheduling {
        // The first call is saved but its answer "never arrives" - the test simply discards it.
        val lost = postOpen(openRequestBody(at = t, clientRequestId = key))
        val originalId = lost.asRequest().requestId

        val retry = postOpen(openRequestBody(at = t, clientRequestId = key))
        assertEquals(HttpStatusCode.OK, retry.status)
        assertEquals(originalId, retry.asRequest().requestId)

        assertEquals(1, requestRows() - 0)
        assertEquals(listOf(originalId), driverFeedOpenAsks().map { it.requestId })
    }

    @Test
    fun retryingManyTimesStillLeavesOneRequest() = withScheduling {
        val ids = (1..5).map { postOpen(openRequestBody(at = t, clientRequestId = key)).asRequest().requestId }.toSet()
        assertEquals(1, ids.size)
        assertEquals(1, liveOpenRows())
    }

    @Test
    fun theRetryReturnsTheOriginalEvenAfterItHasBeenCancelled() = withScheduling {
        val original = postOpen(openRequestBody(at = t, clientRequestId = key)).asRequest()
        assertEquals(HttpStatusCode.OK, respond(PASSENGER, original.requestId, RideRequestStatus.CANCELLED).status)

        val retry = postOpen(openRequestBody(at = t, clientRequestId = key)).asRequest()
        assertEquals(original.requestId, retry.requestId)
        // A retry replays the ORIGINAL answer - the request as it was created - not the latest state. (Its being cancelled
        // now is a separate fact the app reads from My Requests.) Before this was specified, the replay showed CANCELLED.
        assertEquals(RideRequestStatus.PENDING, retry.status)
        assertEquals(original, retry)
        assertEquals(RideRequestStatus.CANCELLED, requestStatus(original.requestId), "and it really is cancelled now")
        assertEquals(1, requestRows())
    }

    @Test
    fun reusingAKeyForADifferentRequestIsRefusedAndStoresNothing() = withScheduling {
        postOpen(openRequestBody(at = t, clientRequestId = key)).asRequest()
        val different = postOpen(openRequestBody(at = t + 5 * DAY, clientRequestId = key))
        assertEquals(HttpStatusCode.Conflict, different.status)
        assertEquals(CONFLICT_IDEMPOTENCY_KEY_REUSED, different.asError().code)
        assertEquals(1, requestRows())
    }

    @Test
    fun aKeyIsScopedToThePassenger() = withScheduling {
        val mine = postOpen(openRequestBody(at = t, clientRequestId = key), PASSENGER).asRequest()
        val theirs = postOpen(openRequestBody(at = t, clientRequestId = key), OTHER_PASSENGER).asRequest()
        assertNotEquals(mine.requestId, theirs.requestId)
        assertEquals(PASSENGER, mine.passengerId)
        assertEquals(OTHER_PASSENGER, theirs.passengerId)
    }

    @Test
    fun aRefusedAttemptDoesNotUseUpItsKey() = withScheduling {
        val existing = postOpen(openRequestBody(at = t)).asRequest()
        // This attempt is a duplicate, so it is refused and records nothing...
        val refused = postOpen(openRequestBody(at = t, clientRequestId = key))
        assertEquals(CONFLICT_DUPLICATE_OPEN_REQUEST, refused.asError().code)
        // ...so once the way is clear, the same key can be used for the same request.
        respond(PASSENGER, existing.requestId, RideRequestStatus.CANCELLED)
        val retry = postOpen(openRequestBody(at = t, clientRequestId = key))
        assertEquals(HttpStatusCode.OK, retry.status)
        assertNotEquals(existing.requestId, retry.asRequest().requestId)
    }

    @Test
    fun aMalformedKeyIsABadRequest() = withScheduling {
        for (bad in listOf("short", "has spaces in it", "x".repeat(65), "semi;colon;key")) {
            val response = postOpen(openRequestBody(at = t, clientRequestId = bad))
            assertEquals(HttpStatusCode.BadRequest, response.status, bad)
        }
        assertEquals(0, requestRows())
    }

    @Test
    fun requestsWithoutAKeyStillWorkForOlderApps() = withScheduling {
        assertEquals(HttpStatusCode.OK, postOpen(openRequestBody(at = t)).status)
    }

    @Test
    fun aRepeatingSeriesIsCreatedOnceAndARetryReturnsTheSameFirstRequest() = withScheduling {
        val body = openRequestBody(at = t, clientRequestId = key, repeatDays = listOf(1, 2, 3, 4, 5, 6, 7), repeatWeeks = 2)
        val first = postOpen(body).asRequest()
        val rowsAfterFirst = requestRows()
        assertEquals(true, rowsAfterFirst > 1) // the series fanned out

        val retry = postOpen(body).asRequest()
        assertEquals(first.requestId, retry.requestId)
        assertEquals(rowsAfterFirst, requestRows())
    }

    @Test
    fun seatRequestsAreIdempotentToo() = withScheduling {
        val ride = offerRide(DRIVER_A, t)
        val first = postSeat(ride.rideId, clientRequestId = key)
        val firstIds = createdRequests(first.bodyAsText()).map { it.requestId }

        val retry = postSeat(ride.rideId, clientRequestId = key)
        assertEquals(HttpStatusCode.OK, retry.status)
        assertEquals(firstIds, createdRequests(retry.bodyAsText()).map { it.requestId })
        assertEquals(1, requestRows())
    }

    @Test
    fun aSeatKeyCannotBeReusedForAnOpenRequest() = withScheduling {
        val ride = offerRide(DRIVER_A, t)
        postSeat(ride.rideId, clientRequestId = key)
        val open = postOpen(openRequestBody(at = t + 4 * DAY, clientRequestId = key))
        assertEquals(CONFLICT_IDEMPOTENCY_KEY_REUSED, open.asError().code)
    }
}
