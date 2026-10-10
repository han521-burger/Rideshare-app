package com.example.ds04_monash_rideshare_application.backend

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The same journey sent twice must never become a second request or a second driver card, and the
// answer must be a specific, machine-readable duplicate result that names the existing request.
class DuplicateRequestTest {
    private val t = futureDeparture()

    @Test
    fun exactDuplicateIsRefusedWithTheExistingRequestIdAndNothingNewIsStored() = withScheduling {
        val first = postOpen(openRequestBody(at = t)).asRequest()

        val again = postOpen(openRequestBody(at = t))
        assertEquals(HttpStatusCode.Conflict, again.status)
        val error = again.asError()
        assertEquals(CONFLICT_DUPLICATE_OPEN_REQUEST, error.code)
        assertEquals(first.requestId, error.existingRequestId)

        assertEquals(1, liveOpenRows())
        assertEquals(1, driverFeedOpenAsks().size)
        assertEquals(first.requestId, driverFeedOpenAsks().single().requestId)
    }

    @Test
    fun theDuplicateErrorCarriesNoServerFormattedTimes() = withScheduling {
        postOpen(openRequestBody(at = t)).asRequest()
        val message = postOpen(openRequestBody(at = t)).asError().error
        // Android formats times in the viewer's zone; the backend must not embed its own.
        assertTrue(Regex("\\d{1,2}:\\d{2}").find(message) == null, message)
    }

    @Test
    fun departuresWithinOneMinuteAreTheSameJourney() = withScheduling {
        val first = postOpen(openRequestBody(at = t)).asRequest()
        for (offset in listOf(-MINUTE, MINUTE, 30_000L)) {
            val again = postOpen(openRequestBody(at = t + offset))
            assertEquals(HttpStatusCode.Conflict, again.status, "offset $offset")
            assertEquals(CONFLICT_DUPLICATE_OPEN_REQUEST, again.asError().code, "offset $offset")
        }
        assertEquals(1, liveOpenRows())
        assertEquals(listOf(first.requestId), driverFeedOpenAsks().map { it.requestId })
    }

    @Test
    fun adjacentMinutesBeyondTheToleranceAreNotCalledDuplicates() = withScheduling {
        postOpen(openRequestBody(at = t)).asRequest()
        // Two minutes later is not the same journey. (Whether it is physically possible is the
        // schedule rule, not the duplicate rule - the code must say so.)
        val later = postOpen(openRequestBody(at = t + 2 * MINUTE))
        if (later.status != HttpStatusCode.OK) assertNotEquals(CONFLICT_DUPLICATE_OPEN_REQUEST, later.asError().code)
    }

    @Test
    fun theSameRouteALongTimeLaterIsALegitimateNewRequest() = withScheduling {
        val first = postOpen(openRequestBody(at = t)).asRequest()
        val later = postOpen(openRequestBody(at = t + 3 * 60 * MINUTE)).asRequest()
        assertNotEquals(first.requestId, later.requestId)
        assertEquals(2, liveOpenRows())
        assertEquals(2, driverFeedOpenAsks().size)
    }

    @Test
    fun theReverseRouteAtTheSameTimeIsADifferentJourney() = withScheduling {
        postOpen(openRequestBody(from = Pts.huntingdale, to = Pts.clayton, at = t)).asRequest()
        val reverse = postOpen(openRequestBody(from = Pts.clayton, to = Pts.huntingdale, at = t))
        if (reverse.status != HttpStatusCode.OK) assertNotEquals(CONFLICT_DUPLICATE_OPEN_REQUEST, reverse.asError().code)
    }

    @Test
    fun coordinatesAreComparedAsApprovedPointsNotAsFloatingPointNumbers() = withScheduling {
        val first = postOpen(openRequestBody(at = t)).asRequest()
        // Same approved points, a few metres of float noise.
        val noisy = postOpen(openRequestBody(
            from = Pts.huntingdale.first + 0.00003 to Pts.huntingdale.second - 0.00002,
            to = Pts.clayton.first - 0.00002 to Pts.clayton.second + 0.00003,
            at = t
        ))
        assertEquals(HttpStatusCode.Conflict, noisy.status)
        assertEquals(first.requestId, noisy.asError().existingRequestId)
        // Resolved ids were stored with the request.
        val stored = transaction { RideRequests.selectAll().where { RideRequests.requestId eq first.requestId }.first() }
        assertEquals("huntingdale-station", stored[RideRequests.originPointId])
        assertEquals("monash-clayton", stored[RideRequests.destinationPointId])
    }

    @Test
    fun twoAsapRequestsAtOnceAreOneRequest() = withScheduling {
        val first = postOpen(openRequestBody(asap = true)).asRequest()
        val again = postOpen(openRequestBody(asap = true))
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertEquals(first.requestId, again.asError().existingRequestId)
        assertEquals(1, liveOpenRows())
    }

    @Test
    fun anAcceptedRequestStillCountsAsTheLiveRequestForThatJourney() = withScheduling {
        val first = postOpen(openRequestBody(at = t)).asRequest()
        assertEquals(HttpStatusCode.OK, respond(DRIVER_A, first.requestId, RideRequestStatus.ACCEPTED).status)
        val again = postOpen(openRequestBody(at = t))
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertEquals(first.requestId, again.asError().existingRequestId)
    }

    // --- terminal requests never make a new one a duplicate ---

    @Test
    fun aCancelledRequestDoesNotBlockAFreshOne() = withScheduling {
        val first = postOpen(openRequestBody(at = t)).asRequest()
        assertEquals(HttpStatusCode.OK, respond(PASSENGER, first.requestId, RideRequestStatus.CANCELLED).status)
        val second = postOpen(openRequestBody(at = t)).asRequest()
        assertNotEquals(first.requestId, second.requestId)
        assertEquals(listOf(second.requestId), driverFeedOpenAsks().map { it.requestId })
    }

    @Test
    fun expiredCompletedAndRejectedRequestsDoNotBlockAFreshOne() = withScheduling {
        for (terminal in listOf(RideRequestStatus.EXPIRED, RideRequestStatus.COMPLETED, RideRequestStatus.REJECTED)) {
            val old = postOpen(openRequestBody(at = t)).asRequest()
            setStatus(old.requestId, terminal)
            val fresh = postOpen(openRequestBody(at = t))
            assertEquals(HttpStatusCode.OK, fresh.status, "after $terminal: ${fresh.status}")
            // Clear the way for the next round.
            setStatus(fresh.asRequest().requestId, RideRequestStatus.CANCELLED)
        }
    }

    // --- seat requests on posted rides ---

    @Test
    fun askingForTheSamePostedRideTwiceIsADuplicate() = withScheduling {
        val ride = offerRide(DRIVER_A, t)
        val first = postSeat(ride.rideId).also { assertEquals(HttpStatusCode.OK, it.status) }
        val firstId = createdRequests(first.bodyAsText()).single().requestId

        val again = postSeat(ride.rideId)
        assertEquals(HttpStatusCode.Conflict, again.status)
        val error = again.asError()
        assertEquals(CONFLICT_DUPLICATE_OPEN_REQUEST, error.code)
        assertEquals(firstId, error.existingRequestId)
        assertEquals(1, requestRows() - openRows())
    }

    @Test
    fun alternativePostedRidesAtTheSameTimeAreNotDuplicatesOfEachOther() = withScheduling {
        val rideA = offerRide(DRIVER_A, t)
        val rideB = offerRide(DRIVER_B, t)
        // Alternatives: the same search (one declared journey intent) asked of two different rides.
        assertEquals(HttpStatusCode.OK, postSeat(rideA.rideId, journeyIntentId = "intent-dup-0001").status)
        assertEquals(HttpStatusCode.OK, postSeat(rideB.rideId, journeyIntentId = "intent-dup-0001").status)
        assertEquals(2, requestRows() - openRows())
    }

    @Test
    fun anOpenRequestAndASeatRequestAtTheSameTimeAreNotDuplicatesButAreTwoJourneys() = withScheduling {
        postOpen(openRequestBody(at = t)).asRequest()
        val ride = offerRide(DRIVER_A, t)
        // Not a DUPLICATE (different kinds of request)... but an open request is its own journey, so the same trip
        // twice at once is refused as a schedule conflict, with the open request named as the clash.
        val response = postSeat(ride.rideId, journeyIntentId = "intent-dup-0001")
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals(CODE_SCHEDULE_INFEASIBLE, response.asError().code)
    }

    // --- old rows, written before duplicates were refused ---

    private fun legacyPendingAsk(id: String, at: Long, createdAt: Long, passenger: String = PASSENGER) = transaction {
        RideRequests.insert {
            it[requestId] = id; it[rideId] = null; it[passengerId] = passenger; it[seatsRequested] = 1
            it[status] = RideRequestStatus.PENDING
            it[originLat] = Pts.huntingdale.first; it[originLng] = Pts.huntingdale.second
            it[destinationLat] = Pts.clayton.first; it[destinationLng] = Pts.clayton.second
            it[isAsap] = false; it[desiredDepartureTime] = at; it[RideRequests.createdAt] = createdAt
            // No point ids, no journey key: exactly what rows from before this change look like.
        }
    }

    @Test
    fun legacyDuplicatesCollapseToTheOldestInTheDriverFeedButStayInTheDatabase() = withScheduling {
        val now = System.currentTimeMillis()
        legacyPendingAsk("legacy-newer", t + 20_000L, now - 1_000)
        legacyPendingAsk("legacy-oldest", t, now - 60_000)
        legacyPendingAsk("legacy-other-day", t + DAY, now - 30_000)

        val feed = driverFeedOpenAsks().map { it.requestId }
        assertEquals(setOf("legacy-oldest", "legacy-other-day"), feed.toSet())
        assertEquals(2, feed.size)
        // Nothing was deleted or rewritten.
        assertEquals(3, requestRows())
        assertEquals(RideRequestStatus.PENDING, transaction {
            RideRequests.selectAll().where { RideRequests.requestId eq "legacy-newer" }.first()[RideRequests.status]
        })
    }

    @Test
    fun aNewRequestMatchingALegacyRowIsRefusedAsADuplicateOfIt() = withScheduling {
        legacyPendingAsk("legacy-1", t, System.currentTimeMillis() - 60_000)
        val again = postOpen(openRequestBody(at = t))
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertEquals("legacy-1", again.asError().existingRequestId)
        assertEquals(1, liveOpenRows())
    }

    @Test
    fun historyOfTerminalRequestsIsNeverHiddenFromTheDriver() = withScheduling {
        val ride = offerRide(DRIVER_A, t)
        val seat = postSeat(ride.rideId).also { assertEquals(HttpStatusCode.OK, it.status) }
        val id = createdRequests(seat.bodyAsText()).single().requestId
        assertEquals(HttpStatusCode.OK, respond(PASSENGER, id, RideRequestStatus.CANCELLED).status)
        val feed = driverFeedAll(DRIVER_A)
        assertTrue(feed.any { it.requestId == id && it.status == RideRequestStatus.CANCELLED })
    }

    // --- database backstop for an exact duplicate that somehow got past the transactional check ---

    private fun insertLive(id: String, key: String, status: String) = transaction {
        RideRequests.insert {
            it[requestId] = id; it[rideId] = null; it[passengerId] = PASSENGER; it[seatsRequested] = 1
            it[RideRequests.status] = status; it[createdAt] = System.currentTimeMillis(); it[journeyKey] = key
        }
    }

    @Test
    fun theDatabaseRefusesTwoLiveRowsForTheSameJourneyKey() = withScheduling {
        insertLive("k1", "x>y@1", RideRequestStatus.PENDING)
        assertFailsWith<ExposedSQLException> { insertLive("k2", "x>y@1", RideRequestStatus.PENDING) }
        assertFailsWith<ExposedSQLException> { insertLive("k3", "x>y@1", RideRequestStatus.ACCEPTED) }
    }

    @Test
    fun terminalRowsAndOtherJourneysNeverTripTheBackstop() = withScheduling {
        insertLive("k1", "x>y@1", RideRequestStatus.PENDING)
        insertLive("k2", "x>y@1", RideRequestStatus.CANCELLED)
        insertLive("k3", "x>y@1", RideRequestStatus.EXPIRED)
        insertLive("k4", "x>y@2", RideRequestStatus.PENDING)
        assertEquals(4, requestRows())
    }

    @Test
    fun rowsWithoutAJourneyKeyAreExemptFromTheBackstop() = withScheduling {
        legacyPendingAsk("legacy-a", t, System.currentTimeMillis())
        legacyPendingAsk("legacy-b", t, System.currentTimeMillis())
        assertEquals(2, requestRows())
        assertNull(transaction { RideRequests.selectAll().where { RideRequests.requestId eq "legacy-a" }.first()[RideRequests.journeyKey] })
    }

    // open (standalone-origin) rows for the test passenger
    private fun openRows(): Long = transaction { RideRequests.selectAll().where { RideRequests.originLat.isNotNull() and (RideRequests.passengerId eq PASSENGER) }.count() }
}
