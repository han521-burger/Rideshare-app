package com.example.ds04_monash_rideshare_application.backend

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.decodeFromString
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// STAGE 10: the whole scheduling system working as ONE system, not stage by stage. Everything goes through the real routes (a few
// rows are written directly only where the point is a state that the routes cannot reach, such as "this ride is already full of
// accepted passengers"), and every scenario ends by auditing the stored rows (InvariantAudit.kt).
//
// The concepts that must stay distinct, and where each is exercised:
//   duplicate request ........ repeatingBatch..., independentJourneys...
//   idempotent retry ......... repeatingBatch...
//   journey-intent alternative fullPassengerLifecycle, alternativeRace...
//   independent journey ...... independentJourneys...
//   physical conflict ........ fullPassengerLifecycle, independentJourneys...
//   CONFLICTED lifecycle ..... fullPassengerLifecycle, privacy...
//   actual completion history  fullPassengerLifecycle, completionAuthority...
//   accepted schedule warning  acceptedJourney..., driverLifecycle
//
// Matrix (completionMatrix): Clayton B <-> Caulfield D 30 min each way, Huntingdale A -> Clayton 10, Clayton -> Huntingdale 5,
// everything else 20; plus the 10 minute transfer buffer.
class Stage10IntegrationTest {
    // Noon (service zone) ten days ahead, so the search's "same day" gate cannot straddle midnight.
    private val base = LocalDate.now(SchedulingConfig.SERVICE_ZONE).plusDays(10).atTime(12, 0).atZone(SchedulingConfig.SERVICE_ZONE).toInstant().toEpochMilli()
    private fun at(minutes: Long) = base + minutes * MINUTE
    private val matrix = completionMatrix()
    private val third = OTHER_PASSENGER // a seeded account with a vehicle: the third driver
    private val search = "s10-search-0001"

    private suspend fun HttpClient.seat(rideId: String, caller: String = PASSENGER, intent: String? = null): String {
        val response = postSeat(rideId, caller = caller, journeyIntentId = intent)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return createdRequests(response.bodyAsText()).single().requestId
    }

    private suspend fun HttpClient.accept(driver: String, requestId: String, targetRideId: String? = null) = respond(driver, requestId, "ACCEPTED", targetRideId)

    private suspend fun HttpClient.undo(driver: String, requestId: String): HttpResponse =
        post("/requests/$requestId/undo-accept") { header(HttpHeaders.Authorization, "Bearer $driver") }

    private suspend fun HttpClient.matchRides(caller: String, from: String, to: String, departure: Long): MatchSearchResponse {
        val f = coords(from); val t = coords(to)
        val response = get("/rides/match?originLat=${f.first}&originLng=${f.second}&destinationLat=${t.first}&destinationLng=${t.second}&departureTime=$departure&seatsNeeded=1") {
            header(HttpHeaders.Authorization, "Bearer $caller")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return schedulingJson.decodeFromString(response.bodyAsText())
    }

    private suspend fun <T> race(vararg actions: suspend () -> T): List<T> =
        coroutineScope { actions.map { action -> async(Dispatchers.Default) { action() } }.awaitAll() }

    private suspend fun HttpClient.statusesOf(caller: String): Map<String, String> = myRequests(caller).associate { it.requestId to it.status }

    // ================================================================================================ 1. the whole passenger journey
    @Test
    fun fullPassengerLifecycle() = withScheduling(matrix) {
        // 1-2. Three drivers post Huntingdale -> Clayton rides 5 minutes apart; the passenger searches and finds all three.
        val r1 = rideBetween(DRIVER_A, at(0)); val r2 = rideBetween(DRIVER_B, at(5)); val r3 = rideBetween(third, at(10))
        val found = matchRides(PASSENGER, Place.A, Place.B, at(0)).rides.map { it.rideId }
        assertTrue(listOf(r1, r2, r3).all { it.rideId in found }, "search returned $found")

        // 3. All three are options for ONE journey (one search = one journeyIntentId): three PENDING requests, one slot.
        val alt1 = seat(r1.rideId, intent = search); val alt2 = seat(r2.rideId, intent = search); val alt3 = seat(r3.rideId, intent = search)
        // ... plus a different, later journey (Clayton -> Caulfield, 2 hours on). It fits after the latest alternative.
        val laterAnswer = openBetween(Place.B, Place.D, at(120))
        assertEquals(HttpStatusCode.OK, laterAnswer.status, laterAnswer.bodyAsText())
        val later = createdRequests(laterAnswer.bodyAsText()).single().requestId
        val live = statusesOf(PASSENGER)
        assertEquals(listOf(RideRequestStatus.PENDING), listOf(alt1, alt2, alt3, later).map { live.getValue(it) }.distinct())

        // 4-5. DRIVER_A accepts one alternative: it is ACCEPTED, the other two options for the same journey are CANCELLED (not
        // conflicted), and the independent later journey is untouched because it is still physically possible.
        assertEquals(HttpStatusCode.OK, accept(DRIVER_A, alt1).status)
        assertEquals(RideRequestStatus.ACCEPTED, requestStatus(alt1))
        assertEquals(RideRequestStatus.CANCELLED, requestStatus(alt2))
        assertEquals(RideRequestStatus.CANCELLED, requestStatus(alt3))
        assertEquals(RideRequestStatus.PENDING, requestStatus(later))
        assertNull(conflictOf(alt2)); assertNull(conflictOf(later))
        assertEquals(mapOf(alt1 to "ACCEPTED", alt2 to "CANCELLED", alt3 to "CANCELLED", later to "PENDING"), statusesOf(PASSENGER).filterKeys { it in setOf(alt1, alt2, alt3, later) })
        // The lost alternatives are history to their drivers, not something to act on; the later ask is still on offer.
        assertTrue(driverFeedAll(DRIVER_B).none { it.requestId == alt2 && it.status == RideRequestStatus.PENDING })
        assertEquals(RideRequestStatus.PENDING, driverFeedAll(DRIVER_B).single { it.requestId == later }.status)
        assertFeedsOfferOnlyWhatIsPending(listOf(DRIVER_A, DRIVER_B, third))

        // 6-7. The accepted ride finishes 105 minutes later than planned (planned arrival 10; really 115).
        val completed = completeRideAt(DRIVER_A, r1.rideId, at(115))
        assertEquals(at(115), completed.completedAt)
        assertEquals(RideStatus.COMPLETED, rideStatus(r1.rideId))
        assertEquals(at(115), rideRow(r1.rideId)[Rides.completedAt], "the actual time, not the planned 10")
        assertEquals(RideRequestStatus.COMPLETED, requestStatus(alt1))
        // 8-10. 115 + 0 + 10 = 125 > 120: the later journey is no longer possible. CONFLICTED - not cancelled, not deleted.
        assertEquals(RideRequestStatus.CONFLICTED, requestStatus(later))
        val reason = assertNotNull(conflictOf(later))
        assertEquals(CONFLICT_TOO_SOON_AFTER_PREVIOUS, reason.type)
        assertEquals(at(125), reason.earliestFeasibleDeparture)
        assertEquals(at(115), reason.conflictingArrivalTime)

        // 11. The passenger sees it, with the reason, in their own history - and nobody else is shown the reason or offered it.
        val mine = myRequests(PASSENGER).associateBy { it.requestId }
        assertEquals(RideRequestStatus.CONFLICTED, mine.getValue(later).status)
        assertEquals(at(125), mine.getValue(later).conflict?.earliestFeasibleDeparture)
        assertEquals(RideRequestStatus.COMPLETED, mine.getValue(alt1).status)
        assertEquals(RideRequestStatus.CANCELLED, mine.getValue(alt2).status)
        assertTrue(driverFeedAll(DRIVER_B).none { it.requestId == later }, "a conflicted request is offered to no one")
        assertTrue(get("/users/me/trip-history") { header(HttpHeaders.Authorization, "Bearer $PASSENGER") }.bodyAsText().contains(r1.rideId))
        assertEquals(0, warningsOf(PASSENGER).size, "a conflict is not a warning")

        // 12. A new request is judged against where and when the passenger ACTUALLY finished: Clayton at 115.
        openBetween(Place.B, Place.D, at(124)).scheduleConflict(CONFLICT_TOO_SOON_AFTER_PREVIOUS).let {
            assertEquals(at(125), it.earliestFeasibleDeparture); assertEquals(at(115), it.conflictingArrivalTime)
        }
        assertEquals(HttpStatusCode.OK, openBetween(Place.B, Place.D, at(125)).status)
        auditInvariants(InvariantScope(capacityRides = listOf(r1.rideId, r2.rideId, r3.rideId), manuallyCompleted = listOf(r1.rideId)))
    }

    // ================================================================================================ 2. accepted journey: warn, never change
    @Test
    fun acceptedJourneyAfterALateRideKeepsItsStatusAndTheWarningIsPrivate() = withScheduling(matrix) {
        val rideA = rideBetween(DRIVER_A, at(0), Place.B, Place.D)               // Clayton -> Caulfield, planned arrival 30
        val seatA = seat(rideA.rideId); assertEquals(HttpStatusCode.OK, accept(DRIVER_A, seatA).status)
        val rideB = rideBetween(DRIVER_B, at(45), Place.D, Place.B)              // leaves Caulfield 45: 30 + 0 + 10 = 40 <= 45
        val seatB = seat(rideB.rideId); assertEquals(HttpStatusCode.OK, accept(DRIVER_B, seatB).status)
        suspend fun funnel() = schedulingJson.decodeFromString<AnalyticsDto>(
            get("/users/me/analytics") { header(HttpHeaders.Authorization, "Bearer $PASSENGER") }.bodyAsText()
        ).passengerFunnel
        val before = funnel()
        assertEquals(2, before.accepted)

        completeRideAt(DRIVER_A, rideA.rideId, at(50))                           // really 50: 50 + 10 = 60 > 45

        // Ride B's request is exactly what it was.
        assertEquals(RideRequestStatus.ACCEPTED, requestStatus(seatB))
        assertEquals(RideStatus.CONFIRMED, rideStatus(rideB.rideId))
        assertNull(conflictOf(seatB))
        // Exactly one private warning, for the passenger, about ride B.
        val warnings = warningsOf(PASSENGER)
        assertEquals(1, warnings.size); assertEquals(seatB, warnings.single().subjectId)
        assertEquals(at(60), warnings.single().details.earliestFeasibleDeparture)
        // Ride B's driver learns nothing about ride A: not from the warning endpoint, not from any payload they can read.
        assertTrue(warningsOf(DRIVER_B).isEmpty())
        val driverBViews = listOf(
            get("/requests") { header(HttpHeaders.Authorization, "Bearer $DRIVER_B") }.bodyAsText(),
            get("/rides/${rideB.rideId}") { header(HttpHeaders.Authorization, "Bearer $DRIVER_B") }.bodyAsText(),
            get("/users/me/analytics") { header(HttpHeaders.Authorization, "Bearer $DRIVER_B") }.bodyAsText()
        )
        for (body in driverBViews) for (secret in listOf(rideA.rideId, seatA, "previousCompletedAt", "earliestFeasibleDeparture", "PREVIOUS_RIDE_FINISHED_LATE")) {
            assertFalse(body.contains(secret), "driver B can see '$secret' in: ${body.take(300)}")
        }
        // The funnel still counts ride B's request as ACCEPTED, once; the completed one moved to completed.
        val after = funnel()
        assertEquals(1, after.accepted); assertEquals(before.completed + 1, after.completed)
        assertEquals(before.conflicted, after.conflicted); assertEquals(before.cancelled, after.cancelled)
        auditInvariants(InvariantScope(capacityRides = listOf(rideA.rideId, rideB.rideId), manuallyCompleted = listOf(rideA.rideId)))
    }

    // ================================================================================================ 3. one person across many rides as a driver
    @Test
    fun driverLifecycle() = withScheduling(matrix) {
        val x = rideBetween(DRIVER_A, at(0), Place.B, Place.D)                  // Clayton -> Caulfield, 0..30
        // Incompatible posting (overlaps ride X) is refused; a compatible one is not.
        postRide(DRIVER_A, at(10), Place.B, Place.A).scheduleConflict()
        // The driver also asks for a trip as a passenger, later: Caulfield -> Clayton at 120.
        val ownAsk = createdRequests(openBetween(Place.D, Place.B, at(120), caller = DRIVER_A).bodyAsText().also { assertTrue(it.contains("\"created\":1"), it) }).single().requestId
        // Compatible posting: Huntingdale -> Clayton at 60 (30 + 20 Caulfield -> Huntingdale + 10 = 60 <= 60) - with a passenger.
        val y = rideBetween(DRIVER_A, at(60), Place.A, Place.B)
        val yseat = seat(y.rideId, caller = OTHER_PASSENGER); assertEquals(HttpStatusCode.OK, accept(DRIVER_A, yseat).status)
        // ... and the exact boundary of the incompatible one: one minute earlier is refused.
        postRide(DRIVER_A, at(59), Place.A, Place.B).scheduleConflict()

        // The driver brings ride X in 85 minutes late: really 115. That is where, and when, the driver now is.
        completeRideAt(DRIVER_A, x.rideId, at(115))
        assertEquals(at(115), rideRow(x.rideId)[Rides.completedAt])
        // Their pending trip as a passenger (leaving Caulfield at 120: 115 + 10 = 125) is no longer possible.
        assertEquals(RideRequestStatus.CONFLICTED, requestStatus(ownAsk))
        assertEquals(at(125), conflictOf(ownAsk)?.earliestFeasibleDeparture)
        // Their committed ride Y (Huntingdale at 60) can no longer be reached - but it is still theirs, still live, still has its passenger.
        assertEquals(RideStatus.CONFIRMED, rideStatus(y.rideId))
        assertEquals(RideRequestStatus.ACCEPTED, requestStatus(yseat))
        val warning = warningsOf(DRIVER_A).single()
        assertEquals(WARNING_SUBJECT_RIDE, warning.subjectKind); assertEquals(y.rideId, warning.subjectId)
        // Y's passenger is not affected and is told nothing.
        assertTrue(warningsOf(OTHER_PASSENGER).isEmpty())
        assertNull(conflictOf(yseat))
        // The actual completion is the driver's anchor for NEW rides. (DRIVER_A is not used for this: ride Y, still committed,
        // sits between ride X and anything new, so Y - not X - is the "previous journey" there; that is the documented rule, and
        // the warning above is how the clash is surfaced.) An idle driver shows it cleanly: ride W, planned to reach Caulfield at
        // 30, really finishes at 40. A ride from Clayton then needs 40 + 30 + 10 = 80.
        val w = rideBetween(DRIVER_B, at(2000), Place.B, Place.D)
        completeRideAt(DRIVER_B, w.rideId, at(2040))
        postRide(DRIVER_B, at(2079), Place.B, Place.A).scheduleConflict(CONFLICT_TOO_SOON_AFTER_PREVIOUS).let {
            assertEquals(at(2080), it.earliestFeasibleDeparture)
        }
        assertEquals(at(2080), rideBetween(DRIVER_B, at(2080), Place.B, Place.A).departureTime)
        auditInvariants(InvariantScope(capacityRides = listOf(x.rideId, y.rideId), manuallyCompleted = listOf(x.rideId, w.rideId)))
    }

    // ================================================================================================ 4. same request, two drivers
    @Test
    fun sameRequestTwoDriversExactlyOneWinsRepeatedlyAndTheAuditHolds() {
        repeat(10) { round ->
            withScheduling(matrix) {
                val ask = createdRequests(openBetween(Place.A, Place.B, at(0)).bodyAsText()).single().requestId
                // Both driver clients see the same actionable request before anyone acts.
                assertEquals(RideRequestStatus.PENDING, driverFeedAll(DRIVER_A).single { it.requestId == ask }.status)
                assertEquals(RideRequestStatus.PENDING, driverFeedAll(DRIVER_B).single { it.requestId == ask }.status)
                val answers = race({ accept(DRIVER_A, ask) }, { accept(DRIVER_B, ask) })
                val winnerIndex = answers.indices.single { answers[it].status == HttpStatusCode.OK }
                val winner = listOf(DRIVER_A, DRIVER_B)[winnerIndex]; val loser = listOf(DRIVER_A, DRIVER_B)[1 - winnerIndex]
                val lost = answers[1 - winnerIndex]
                assertEquals(HttpStatusCode.Conflict, lost.status, "round $round")
                assertEquals(CODE_REQUEST_ALREADY_ACCEPTED, lost.asError().code)
                // One request, accepted once; one ride, the winner's, with one seat taken; nothing for the loser.
                assertEquals(RideRequestStatus.ACCEPTED, requestStatus(ask))
                val ride = requestRow(ask)[RideRequests.rideId]!!
                assertEquals(winner, rideRow(ride)[Rides.driverId])
                assertEquals(1, transaction { RideRequests.selectAll().where { RideRequests.passengerId eq PASSENGER and (RideRequests.originPointId eq Place.A) and (RideRequests.desiredDepartureTime eq at(0)) }.count() }.toInt())
                // The loser's NEXT refresh no longer offers it, and a stale Accept (the loser's client has not refreshed yet) gets the contract.
                assertTrue(driverFeedAll(loser).none { it.requestId == ask }, "the loser still sees it")
                assertTrue(driverFeedAll(third).none { it.requestId == ask })
                val stale = accept(loser, ask)
                assertEquals(CODE_REQUEST_ALREADY_ACCEPTED, stale.asError().code)
                assertFalse(stale.bodyAsText().contains(winner), "the loser is not told who won")
                assertFeedsOfferOnlyWhatIsPending(listOf(DRIVER_A, DRIVER_B, third))
                auditInvariants(InvariantScope(capacityRides = listOf(ride)))
            }
        }
    }

    // ================================================================================================ 5. alternatives race, then Undo
    @Test
    fun alternativesRaceLeavesOneAcceptedAndUndoRestoresTheOthers() {
        repeat(6) { round ->
            withScheduling(matrix) {
                val rides = listOf(rideBetween(DRIVER_A, at(0)), rideBetween(DRIVER_B, at(5)), rideBetween(third, at(10)))
                val drivers = listOf(DRIVER_A, DRIVER_B, third)
                val ids = rides.map { seat(it.rideId, intent = search) }
                // Two drivers accept two different alternatives at the same moment.
                val answers = race({ accept(drivers[0], ids[0]) }, { accept(drivers[1], ids[1]) })
                assertEquals(1, answers.count { it.status == HttpStatusCode.OK }, "round $round: ${answers.map { it.status }}")
                answers.filter { it.status != HttpStatusCode.OK }.forEach { assertEquals(CODE_REQUEST_UNAVAILABLE, it.asError().code) }
                val statuses = ids.map { requestStatus(it) }
                assertEquals(1, statuses.count { it == RideRequestStatus.ACCEPTED }, "$statuses")
                assertEquals(2, statuses.count { it == RideRequestStatus.CANCELLED }, "$statuses")
                assertEquals(0, statuses.count { it == RideRequestStatus.PENDING || it == RideRequestStatus.CONFLICTED })
                assertEquals(1, rides.sumOf { it.seatsTotal - rideRow(it.rideId)[Rides.seatsAvailable] }, "one physical commitment")
                for (driver in drivers) assertTrue(driverFeedAll(driver).none { it.requestId in ids && it.status == RideRequestStatus.PENDING })
                auditInvariants(InvariantScope(capacityRides = rides.map { it.rideId }))

                // Undo by the winner puts everything back exactly as the search left it: three options, no commitment.
                val winnerIndex = ids.indexOfFirst { requestStatus(it) == RideRequestStatus.ACCEPTED }
                assertEquals(HttpStatusCode.OK, undo(drivers[winnerIndex], ids[winnerIndex]).status)
                assertEquals(listOf(RideRequestStatus.PENDING), ids.map { requestStatus(it) }.distinct())
                assertEquals(0, rides.sumOf { it.seatsTotal - rideRow(it.rideId)[Rides.seatsAvailable] })
                auditInvariants(InvariantScope(capacityRides = rides.map { it.rideId }))
                // ... and the passenger can still have exactly one of them.
                assertEquals(HttpStatusCode.OK, accept(drivers[2], ids[2]).status)
                assertEquals(listOf(RideRequestStatus.CANCELLED, RideRequestStatus.CANCELLED, RideRequestStatus.ACCEPTED), ids.map { requestStatus(it) })
                auditInvariants(InvariantScope(capacityRides = rides.map { it.rideId }))
            }
        }
    }

    // ================================================================================================ 6. independent journeys stay independent
    @Test
    fun independentJourneysAreJudgedByPhysicsAloneNeverAsDuplicatesOrAlternatives() = withScheduling(matrix) {
        // Same route, different times that are physically possible: two journeys. 25 minutes apart is enough here (10 minute
        // ride + 5 back + 10 buffer) - the old fixed +-30 minute rule would have refused it.
        val first = openBetween(Place.A, Place.B, at(2000)); assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        val second = openBetween(Place.A, Place.B, at(2025)); assertEquals(HttpStatusCode.OK, second.status, second.bodyAsText())
        // Different route: a third journey.
        assertEquals(HttpStatusCode.OK, openBetween(Place.C, Place.D, at(2200)).status)
        // Too close to be possible is a PHYSICAL conflict - not a duplicate, and not a generic overlap.
        val tooClose = openBetween(Place.A, Place.B, at(2005))
        assertEquals(CODE_SCHEDULE_INFEASIBLE, tooClose.asError().code)
        // The very same journey again (within a minute) is the duplicate - a different rule with a different code.
        val duplicate = openBetween(Place.A, Place.B, at(2000) + 30_000)
        assertEquals(CONFLICT_DUPLICATE_OPEN_REQUEST, duplicate.asError().code)

        // Seat requests: the same route on different days with DIFFERENT intents, and with NO intent, are all independent.
        val d1 = rideBetween(DRIVER_A, at(3000)); val d2 = rideBetween(DRIVER_B, at(3000 + 1440)); val d3 = rideBetween(third, at(3000 + 2 * 1440))
        val s1 = seat(d1.rideId, intent = "s10-intent-aaaa"); val s2 = seat(d2.rideId, intent = "s10-intent-bbbb"); val s3 = seat(d3.rideId, intent = null)
        assertEquals(HttpStatusCode.OK, accept(DRIVER_A, s1).status)
        assertEquals(RideRequestStatus.ACCEPTED, requestStatus(s1))
        assertEquals(RideRequestStatus.PENDING, requestStatus(s2), "a different intent is a different journey")
        assertEquals(RideRequestStatus.PENDING, requestStatus(s3), "no intent is a journey of its own")
        // The same intent on the SAME route, two days apart, is still refused as a mistake, not merged (JourneyIntentTest covers it
        // in depth) - here we only check it does not change the independent ones.
        assertEquals(HttpStatusCode.OK, accept(DRIVER_B, s2).status)
        assertEquals(RideRequestStatus.ACCEPTED, requestStatus(s2))
        assertEquals(RideRequestStatus.PENDING, requestStatus(s3))
        auditInvariants(InvariantScope(capacityRides = listOf(d1.rideId, d2.rideId, d3.rideId)))
    }

    // ================================================================================================ 7. a repeating submission, end to end
    @Test
    fun repeatingBatchReportsEveryDateAndReplaysItsSnapshotWhateverHappensNext() = withScheduling(matrix) {
        val zone = SchedulingConfig.SERVICE_ZONE
        fun week(n: Long) = Instant.ofEpochMilli(at(0)).atZone(zone).plusWeeks(n).toInstant().toEpochMilli()
        val weekday = Instant.ofEpochMilli(at(0)).atZone(zone).dayOfWeek.value
        // State the series will run into: an existing live request for the 2nd date, and an accepted ride overlapping the 3rd.
        val existing = createdRequests(openBetween(Place.A, Place.B, week(1)).bodyAsText()).single().requestId
        insertPlannedRide("blocker", DRIVER_B, Place.C, Place.D, week(2) + 5 * MINUTE, 20)
        insertSeatRequest("blocker-seat", PASSENGER, "blocker")

        val key = "s10-series-key-0001"
        val body = openRequestBody(from = coords(Place.A), to = coords(Place.B), at = at(0), clientRequestId = key, repeatDays = listOf(weekday), repeatWeeks = 4)
        val first = postOpen(body)
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        val firstText = first.bodyAsText()
        val result = creationResult(firstText)
        // CREATED, DUPLICATE_OPEN_REQUEST, SCHEDULE_INFEASIBLE, CREATED - in date order, every date reported.
        assertEquals(listOf(at(0), week(1), week(2), week(3)), result.occurrences.map { it.departureTime })
        assertEquals(listOf(OUTCOME_CREATED, OUTCOME_REJECTED, OUTCOME_REJECTED, OUTCOME_CREATED), result.occurrences.map { it.outcome })
        assertEquals(listOf(null, CONFLICT_DUPLICATE_OPEN_REQUEST, CODE_SCHEDULE_INFEASIBLE, null), result.occurrences.map { it.code })
        assertEquals(existing, result.occurrences[1].existingRequestId)
        assertEquals(4, result.requested); assertEquals(2, result.created); assertEquals(2, result.rejected)
        // The rows created are exactly the ones reported.
        val createdIds = result.occurrences.mapNotNull { it.requestId }
        assertEquals(createdIds, result.requests.map { it.requestId })
        assertEquals(setOf(at(0), week(3)), createdIds.map { requestRow(it)[RideRequests.desiredDepartureTime]!! }.toSet())
        assertEquals(2L, transaction { RideRequests.selectAll().where { RideRequests.requestId inList createdIds }.count() })
        val stored = submissionResult(PASSENGER, key)
        val snapshots = snapshotSubmissions()

        // Live statuses change afterwards ...
        assertEquals(HttpStatusCode.OK, respond(PASSENGER, createdIds[0], "CANCELLED").status)
        // ... but a retry of the SAME submission replays the immutable original, byte for byte, and writes nothing.
        val replay = postOpen(body)
        assertEquals(HttpStatusCode.OK, replay.status)
        assertEquals(firstText, replay.bodyAsText())
        assertEquals(stored, submissionResult(PASSENGER, key))
        assertEquals(RideRequestStatus.CANCELLED, requestStatus(createdIds[0]))
        // A NEW key is a new submission, evaluated against the state NOW: the cancelled date can be made again; the live ones are duplicates.
        val fresh = creationResult(postOpen(body.replace(key, "s10-series-key-0002")).bodyAsText())
        assertEquals(listOf(OUTCOME_CREATED, OUTCOME_REJECTED, OUTCOME_REJECTED, OUTCOME_REJECTED), fresh.occurrences.map { it.outcome })
        assertEquals(listOf(null, CONFLICT_DUPLICATE_OPEN_REQUEST, CODE_SCHEDULE_INFEASIBLE, CONFLICT_DUPLICATE_OPEN_REQUEST), fresh.occurrences.map { it.code })
        auditInvariants(InvariantScope(submissionsBefore = snapshots))
    }

    // ================================================================================================ 8. completion authority, automatic vs manual
    @Test
    fun theRideOwnsCompletionManualHasATimeAutomaticDoesNot() = withScheduling(matrix) {
        // Ride M (driver completes) and ride U (the system completes) for two passengers.
        insertPlannedRide("ride-m", DRIVER_A, Place.B, Place.D, at(0), 30)
        insertSeatRequest("seat-m", PASSENGER, "ride-m")
        insertPlannedRide("ride-u", DRIVER_B, Place.B, Place.D, at(1000), 30)
        insertSeatRequest("seat-u", OTHER_PASSENGER, "ride-u")

        // An accepted seat cannot finish itself while its ride is live ...
        val early = respond(PASSENGER, "seat-m", "COMPLETED")
        assertEquals(HttpStatusCode.Conflict, early.status); assertEquals(CODE_RIDE_NOT_COMPLETED, early.asError().code)
        assertEquals(RideRequestStatus.ACCEPTED, requestStatus("seat-m"))
        // ... even for a row an older version left inconsistent: it stays a live commitment until the ride completes.
        insertPlannedRide("ride-l", DRIVER_A, Place.B, Place.D, at(500), 30)
        insertSeatRequest("seat-l", DRIVER_B, "ride-l", RideRequestStatus.COMPLETED)
        openBetween(Place.D, Place.B, at(500) + 35 * MINUTE, caller = DRIVER_B).scheduleConflict()

        // The driver completes M: server time, the seat completes with it, and history uses the ride's completedAt.
        completeRideAt(DRIVER_A, "ride-m", at(40))
        assertEquals(at(40), rideRow("ride-m")[Rides.completedAt])
        assertEquals(RideRequestStatus.COMPLETED, requestStatus("seat-m"))
        assertEquals(at(40), transaction { latestCompletedJourneyOf(PASSENGER, matrix) }?.arrival)
        // The legacy inconsistent ride/seat stays live until its ride really completes - then it is history, from the ride.
        completeRideAt(DRIVER_A, "ride-l", at(500) + 40 * MINUTE)
        assertEquals(at(500) + 40 * MINUTE, transaction { latestCompletedJourneyOf(DRIVER_B, matrix) }?.arrival)
        // The system completes U long after: no observed time, the planned arrival is the history.
        expireStaleRecords(at(1000) + 31 * MINUTE + 30 * MINUTE)
        assertEquals(RideStatus.COMPLETED, rideStatus("ride-u"))
        assertEquals(RideRequestStatus.COMPLETED, requestStatus("seat-u"))
        assertNull(rideRow("ride-u")[Rides.completedAt])
        assertEquals(at(1000) + 30 * MINUTE, transaction { latestCompletedJourneyOf(OTHER_PASSENGER, matrix) }?.arrival)
        auditInvariants(InvariantScope(manuallyCompleted = listOf("ride-m", "ride-l"), autoCompleted = listOf("ride-u")))
    }

    // ================================================================================================ 9. canonical points, through every creation API
    @Test
    fun canonicalPointRulesHoldOnEveryCreationRoute() = withScheduling(matrix) {
        val hubA = coords(Place.A); val hubC = coords(Place.C); val campusB = coords(Place.B); val nowhere = 0.0 to 0.0
        suspend fun ask(from: Pair<Double, Double>, to: Pair<Double, Double>, time: Long = at(0)) = postOpen(openRequestBody(from = from, to = to, at = time))
        suspend fun offer(from: Pair<Double, Double>, to: Pair<Double, Double>) = post("/rides") {
            header(HttpHeaders.Authorization, "Bearer $DRIVER_A"); contentType(ContentType.Application.Json)
            setBody("""{"originLat":${from.first},"originLng":${from.second},"destinationLat":${to.first},"destinationLng":${to.second},
                "distanceMeters":4000.0,"estimatedDurationSeconds":600,"departureTime":${at(0)},"seatsTotal":3,"repeatDaysOfWeek":[],"repeatWeeks":1}""")
        }
        suspend fun refusals(label: String, call: suspend (Pair<Double, Double>, Pair<Double, Double>) -> HttpResponse) {
            assertEquals(CODE_UNAPPROVED_LOCATION, call(nowhere, campusB).asError().code, "$label: unapproved pickup")
            assertEquals(CODE_UNAPPROVED_LOCATION, call(hubA, nowhere).asError().code, "$label: unapproved drop-off")
            assertEquals(CODE_SAME_ENDPOINT, call(campusB, campusB).asError().code, "$label: same place")
            assertEquals(CODE_CAMPUS_ENDPOINT_REQUIRED, call(hubA, hubC).asError().code, "$label: hub to hub")
        }
        refusals("request") { f, t -> ask(f, t) }
        refusals("ride") { f, t -> offer(f, t) }
        assertEquals(0L, transaction { RideRequests.selectAll().where { RideRequests.passengerId eq PASSENGER and (RideRequests.desiredDepartureTime eq at(0)) }.count() }, "a refused request leaves no row")
        // Approved routes are accepted (hub -> campus, campus -> hub) and stored as canonical points.
        assertEquals(HttpStatusCode.OK, ask(hubA, campusB, at(0)).status)
        assertEquals(HttpStatusCode.OK, ask(campusB, hubC, at(100)).status)
        val ride = rideBetween(DRIVER_A, at(600), Place.A, Place.B)
        assertEquals(Place.A, ride.originPointId); assertEquals(Place.B, ride.destinationPointId)
        // Acceptance needs the EXACT canonical route: a different end or the reversed route is refused, the right one accepted.
        val wanted = createdRequests(openBetween(Place.A, Place.B, at(600), caller = PASSENGER).bodyAsText()).single().requestId
        val reversed = rideBetween(DRIVER_B, at(600), Place.B, Place.A)
        val elsewhere = rideBetween(third, at(600), Place.A, Place.D)
        assertEquals(HttpStatusCode.BadRequest, accept(DRIVER_B, wanted, reversed.rideId).status)
        assertEquals(HttpStatusCode.BadRequest, accept(third, wanted, elsewhere.rideId).status)
        assertEquals(RideRequestStatus.PENDING, requestStatus(wanted))
        assertEquals(HttpStatusCode.OK, accept(DRIVER_A, wanted, ride.rideId).status)
        auditInvariants(InvariantScope(capacityRides = listOf(ride.rideId, reversed.rideId, elsewhere.rideId)))
    }

    // ================================================================================================ 10. what a driver can learn
    @Test
    fun aConflictedSeatRequestShowsItsDriverNothingAboutThePassengersOtherTrips() = withScheduling(matrix) {
        val rideA = rideBetween(DRIVER_A, at(0), Place.B, Place.D)
        val seatA = seat(rideA.rideId); assertEquals(HttpStatusCode.OK, accept(DRIVER_A, seatA).status)
        val rideZ = rideBetween(DRIVER_B, at(45), Place.D, Place.B)
        val waiting = seat(rideZ.rideId)                                       // valid under the plan, PENDING with driver B
        completeRideAt(DRIVER_A, rideA.rideId, at(50))
        assertEquals(RideRequestStatus.CONFLICTED, requestStatus(waiting))

        // The passenger reads the reason ...
        assertEquals(at(60), myRequests(PASSENGER).single { it.requestId == waiting }.conflict?.earliestFeasibleDeparture)
        // ... the driver of the ride it was aimed at sees only that it ended, with no reason and nothing about ride A.
        val seen = driverFeedAll(DRIVER_B).single { it.requestId == waiting }
        assertEquals(RideRequestStatus.CONFLICTED, seen.status); assertNull(seen.conflict)
        val raw = get("/requests") { header(HttpHeaders.Authorization, "Bearer $DRIVER_B") }.bodyAsText()
        for (secret in listOf(rideA.rideId, seatA, "earliestFeasibleDeparture", "conflictingId", "TOO_SOON_AFTER_PREVIOUS")) assertFalse(raw.contains(secret), "'$secret' leaked: ${raw.take(300)}")
        // Pressing Accept on it is answered "unavailable" with no explanation.
        val accept = accept(DRIVER_B, waiting)
        assertEquals(CODE_REQUEST_UNAVAILABLE, accept.asError().code)
        for (secret in listOf(rideA.rideId, seatA, "clash", "schedule", "earliest")) assertFalse(accept.bodyAsText().contains(secret, ignoreCase = true), "'$secret' in ${accept.bodyAsText()}")
        // Shared ride payloads carry no private schedule data.
        for (viewer in listOf(DRIVER_B, third)) {
            val ride = get("/rides/${rideA.rideId}") { header(HttpHeaders.Authorization, "Bearer $viewer") }.bodyAsText()
            for (secret in listOf("conflict", "warning", seatA)) assertFalse(ride.contains(secret, ignoreCase = true), "'$secret' in ride payload for $viewer")
        }
        assertFeedsOfferOnlyWhatIsPending(listOf(DRIVER_A, DRIVER_B, third))
        auditInvariants(InvariantScope(capacityRides = listOf(rideA.rideId, rideZ.rideId), manuallyCompleted = listOf(rideA.rideId)))
    }
}
