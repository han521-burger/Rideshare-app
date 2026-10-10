package com.example.ds04_monash_rideshare_application.backend

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.builtins.ListSerializer
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// A request that repeats (or a seat request across a recurring group) is a BATCH of independent occurrences. Each one goes
// through the whole pipeline on its own, in order of departure; each reports its own outcome; the ones that fit are made and
// the others are reported, never silently dropped. The clientRequestId identifies the whole submission: a retry gets the
// original answer back verbatim.
class RepeatingRequestBatchTest {
    private val base = futureDeparture(10)
    private val zone = SchedulingConfig.SERVICE_ZONE
    private fun dayLater(days: Long, minutes: Long = 0) =
        Instant.ofEpochMilli(base).atZone(zone).plusDays(days).plusMinutes(minutes).toInstant().toEpochMilli()
    private val weekday = Instant.ofEpochMilli(base).atZone(zone).dayOfWeek.value
    private val everyDay = (1..7).toList()
    private val third = OTHER_PASSENGER // a seeded account with a vehicle, used as a third driver

    // Huntingdale -> Monash Clayton takes 10 minutes and back 5; with the 10-minute buffer a repeat needs 25 minutes.
    private val matrix = ScenarioTravelTimes(20, Triple(Place.A, Place.B, 10L), Triple(Place.B, Place.A, 5L))

    private class Answer(val status: HttpStatusCode, val body: String) {
        val result: CreateRequestsResponse get() = creationResult(body)
        val error: ErrorResponse get() = schedulingJson.decodeFromString(body)
    }

    private suspend fun HttpClient.series(
        at: Long = base, key: String? = null, days: List<Int> = listOf(weekday), weeks: Int? = 4, caller: String = PASSENGER
    ): Answer {
        val response = postOpen(
            openRequestBody(from = coords(Place.A), to = coords(Place.B), at = at, clientRequestId = key, repeatDays = days, repeatWeeks = weeks), caller
        )
        return Answer(response.status, response.bodyAsText())
    }

    private suspend fun HttpClient.single(at: Long, key: String? = null, caller: String = PASSENGER): Answer {
        val response = postOpen(openRequestBody(from = coords(Place.A), to = coords(Place.B), at = at, clientRequestId = key), caller)
        return Answer(response.status, response.bodyAsText())
    }

    private suspend fun HttpClient.groupSeat(rideId: String, key: String? = null, intent: String? = null, caller: String = PASSENGER): Answer {
        val response = post("/rides/$rideId/requests") {
            header(HttpHeaders.Authorization, "Bearer $caller")
            contentType(ContentType.Application.Json)
            val k = key?.let { ""","clientRequestId":"$it"""" } ?: ""
            val i = intent?.let { ""","journeyIntentId":"$it"""" } ?: ""
            setBody("""{"seatsRequested":1,"applyToRecurringGroup":true$k$i}""")
        }
        return Answer(response.status, response.bodyAsText())
    }

    private suspend fun HttpClient.cancel(requestId: String, caller: String = PASSENGER) {
        val response = put("/requests/$requestId") {
            header(HttpHeaders.Authorization, "Bearer $caller"); contentType(ContentType.Application.Json); setBody("""{"status":"CANCELLED"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    }

    // The passenger's live open requests made in a test (the demo data has some older ones).
    private fun liveOpen() = transaction {
        RideRequests.selectAll().where { RideRequests.passengerId eq PASSENGER }.toList()
    }.filter {
        it[RideRequests.rideId] == null && it[RideRequests.status] in LIVE_REQUEST_STATUSES && (it[RideRequests.desiredDepartureTime] ?: 0L) >= base - 60 * MINUTE
    }

    // Independent statement of the rule for the one route used here: the next trip leaves at least arrival + 5 minutes back + 10 minutes later.
    private fun assertTimelineIsFeasible() {
        val legs = liveOpen().map { it[RideRequests.desiredDepartureTime]!! to it[RideRequests.plannedArrivalTime]!! }.sortedBy { it.first }
        val back = matrix.travelSeconds(Place.B, Place.A)!! * 1000
        legs.zipWithNext { a, b -> assertTrue(a.second + back + 10 * MINUTE <= b.first, "${legs.size} live requests are physically impossible together") }
    }

    private fun assertNoJourneyIsDuplicated() {
        val minutes = liveOpen().map { it[RideRequests.desiredDepartureTime]!! / MINUTE }
        assertEquals(minutes.size, minutes.toSet().size, "the same departure minute was made twice")
    }

    // ================================================================ every date is reported
    @Test
    fun aSeriesWhereEverythingFitsMakesEveryDateAndSaysSo() = withScheduling(matrix) {
        val answer = series()
        assertEquals(HttpStatusCode.OK, answer.status, answer.body)
        val r = answer.result
        assertEquals(listOf(4, 4, 0), listOf(r.requested, r.created, r.rejected))
        assertEquals(listOf(0L, 7L, 14L, 21L).map { dayLater(it) }, r.occurrences.map { it.departureTime })
        assertTrue(r.occurrences.all { it.outcome == OUTCOME_CREATED && it.requestId != null && it.code == null })
        assertEquals(r.occurrences.map { it.requestId }, r.requests.map { it.requestId }, "requests are returned in the order of the dates")
        assertEquals(4, liveOpen().size)
    }

    @Test
    fun aOneOffRequestIsTheSameEnvelopeWithOneOfOne() = withScheduling(matrix) {
        val r = single(base).result
        assertEquals(listOf(1, 1, 0), listOf(r.requested, r.created, r.rejected))
        assertEquals(1, r.requests.size)
    }

    @Test
    fun oneDateThatDoesNotFitIsRejectedWithItsReasonAndTheOthersAreStillMade() = withScheduling(matrix) {
        val blocker = single(dayLater(14, 10)).result.requests.single()     // 10 minutes after week 3's slot: no room to get back
        val r = series().result
        assertEquals(listOf(4, 3, 1), listOf(r.requested, r.created, r.rejected))
        val week3 = r.occurrences[2]
        assertEquals(dayLater(14), week3.departureTime)
        assertEquals(OUTCOME_REJECTED, week3.outcome)
        assertEquals(CODE_SCHEDULE_INFEASIBLE, week3.code)
        assertNull(week3.requestId)
        assertEquals(blocker.requestId, week3.conflict?.conflictingId)
        assertNotNull(week3.message)
        assertEquals(listOf(OUTCOME_CREATED, OUTCOME_CREATED, OUTCOME_REJECTED, OUTCOME_CREATED), r.occurrences.map { it.outcome })
        assertTimelineIsFeasible()
    }

    @Test
    fun oneDateThatIsAlreadyRequestedIsRejectedAsADuplicateNamingTheExistingRequest() = withScheduling(matrix) {
        val existing = single(dayLater(7)).result.requests.single()
        val r = series().result
        assertEquals(listOf(4, 3, 1), listOf(r.requested, r.created, r.rejected))
        val week2 = r.occurrences[1]
        assertEquals(CONFLICT_DUPLICATE_OPEN_REQUEST, week2.code)
        assertEquals(existing.requestId, week2.existingRequestId)
        assertNull(week2.conflict)
        assertNoJourneyIsDuplicated()
    }

    @Test
    fun severalDifferentReasonsInOneSeriesAreEachReported() = withScheduling(matrix) {
        // Week 1 free, week 2 clashes with another trip, week 3 is already requested, week 4 free.
        val clash = single(dayLater(7, 10)).result.requests.single()
        val already = single(dayLater(14)).result.requests.single()
        val r = series().result
        assertEquals(listOf(OUTCOME_CREATED, OUTCOME_REJECTED, OUTCOME_REJECTED, OUTCOME_CREATED), r.occurrences.map { it.outcome })
        assertEquals(listOf(null, CODE_SCHEDULE_INFEASIBLE, CONFLICT_DUPLICATE_OPEN_REQUEST, null), r.occurrences.map { it.code })
        assertEquals(clash.requestId, r.occurrences[1].conflict?.conflictingId)
        assertEquals(already.requestId, r.occurrences[2].existingRequestId)
        assertEquals(listOf(4, 2, 2), listOf(r.requested, r.created, r.rejected))
        assertEquals(4, liveOpen().size, "only weeks 1 and 4 are new; the other two are the requests that were already there")
        assertTimelineIsFeasible()
    }

    @Test
    fun aSeriesWhereNothingCanBeMadeIsStillAnsweredWithEveryDate() = withScheduling(matrix) {
        series()                                                   // the whole series already exists
        val again = series()                                       // a new submission of the same thing
        assertEquals(HttpStatusCode.OK, again.status, "a processed batch is not an error, even when nothing was made")
        val r = again.result
        assertEquals(listOf(4, 0, 4), listOf(r.requested, r.created, r.rejected))
        assertTrue(r.requests.isEmpty())
        assertTrue(r.occurrences.all { it.outcome == OUTCOME_REJECTED && it.code == CONFLICT_DUPLICATE_OPEN_REQUEST && it.existingRequestId != null })
        assertEquals(4, liveOpen().size)
    }

    @Test
    fun aSeriesWhoseFirstDateDoesNotFitStillReportsEveryDate() = withScheduling(matrix) {
        single(base)                                               // a request in week 1's slot: the series' first date, 10 minutes later, clashes with it
        val clash = series(at = dayLater(0, 10), days = listOf(weekday), weeks = 2)
        assertEquals(HttpStatusCode.OK, clash.status, clash.body)
        val r = clash.result
        assertEquals(2, r.requested)
        assertEquals(OUTCOME_REJECTED, r.occurrences.first().outcome)
        assertEquals(CODE_SCHEDULE_INFEASIBLE, r.occurrences.first().code)
        assertEquals(OUTCOME_CREATED, r.occurrences.last().outcome, "the next week is unaffected by the first week's clash")
    }

    // ================================================================ chronological, and earlier dates count for later ones
    @Test
    fun datesAreProcessedInChronologicalOrderAndAnEarlierOneJustMadeCountsForALaterOne() = withScheduling(matrix) {
        val driver = DRIVER_A
        val first = postRideGroup(driver)
        val rides = groupRides(first.recurringGroupId!!)
        assertEquals(7, rides.size)
        // Move the second ride to 20 minutes after the first: on its own that is fine, but not after the first is requested.
        val moved = rides[1]
        assertEquals(HttpStatusCode.OK, put("/rides/${moved[Rides.rideId]}") {
            header(HttpHeaders.Authorization, "Bearer $driver"); contentType(ContentType.Application.Json)
            setBody("""{"departureTime":${dayLater(0, 20)}}""")
        }.status)

        val answer = groupSeat(first.rideId)
        val r = answer.result
        val times = r.occurrences.map { it.departureTime }
        assertEquals(times.sorted(), times, "processed - and reported - in order of departure")
        val laterOnSameDay = r.occurrences.single { it.departureTime == dayLater(0, 20) }
        assertEquals(OUTCOME_REJECTED, laterOnSameDay.outcome)
        assertEquals(CODE_SCHEDULE_INFEASIBLE, laterOnSameDay.code)
        assertEquals("SEAT_REQUEST", laterOnSameDay.conflict?.conflictingKind, "it clashed with the date made a moment earlier in this very submission")
        assertEquals(r.occurrences.first().requestId, laterOnSameDay.conflict?.conflictingId)
        assertEquals(6, r.created)

        // Control: on its own, in a fresh submission by someone with an empty schedule, that same ride is fine.
        val alone = post("/rides/${moved[Rides.rideId]}/requests") {
            header(HttpHeaders.Authorization, "Bearer $third"); contentType(ContentType.Application.Json)
            setBody("""{"seatsRequested":1,"applyToRecurringGroup":false}""")
        }
        assertEquals(HttpStatusCode.OK, alone.status, alone.bodyAsText())
    }

    // ================================================================ idempotency
    @Test
    fun aRetryOfTheSameSubmissionGetsTheOriginalAnswerEvenIfADateWouldNowFit() = withScheduling(matrix) {
        val blocker = single(dayLater(7, 10)).result.requests.single()    // week 2 will be rejected
        val first = series(key = "series-key-0001")
        assertEquals(listOf(OUTCOME_CREATED, OUTCOME_REJECTED, OUTCOME_CREATED, OUTCOME_CREATED), first.result.occurrences.map { it.outcome })
        val liveBefore = liveOpen().size

        cancel(blocker.requestId)                                         // week 2 would fit now
        val retry = series(key = "series-key-0001")

        assertEquals(HttpStatusCode.OK, retry.status)
        assertEquals(first.result, retry.result, "the very same response: every occurrence, rejected ones included, and the requests as first created")
        assertEquals(first.result.requests.map { it.requestId }, retry.result.requests.map { it.requestId })
        assertEquals(listOf(4, 3, 1), listOf(retry.result.requested, retry.result.created, retry.result.rejected))
        assertEquals(liveBefore - 1, liveOpen().size, "the retry created nothing (only the blocker's cancellation changed the count)")
    }

    @Test
    fun aNewSubmissionOfTheSameSeriesIsEvaluatedAgainstTheStateNow() = withScheduling(matrix) {
        val blocker = single(dayLater(7, 10)).result.requests.single()
        val first = series(key = "series-key-0001").result
        cancel(blocker.requestId)

        val fresh = series(key = "series-key-0002").result                // a deliberate new attempt, not a retry
        assertEquals(listOf(4, 1, 3), listOf(fresh.requested, fresh.created, fresh.rejected))
        assertEquals(OUTCOME_CREATED, fresh.occurrences[1].outcome, "week 2 fits now and is made")
        assertEquals(listOf(CONFLICT_DUPLICATE_OPEN_REQUEST), fresh.occurrences.filter { it.outcome == OUTCOME_REJECTED }.map { it.code }.distinct())
        assertEquals(first.occurrences[0].requestId, fresh.occurrences[0].existingRequestId, "the weeks made the first time are now 'already requested'")
        assertEquals(4, liveOpen().size)
    }

    @Test
    fun aBatchWhereNothingWasMadeIsRecordedToo() = withScheduling(matrix) {
        val original = series()                                           // all four exist
        val firstTry = series(key = "series-key-0003")
        assertEquals(0, firstTry.result.created)
        original.result.requests.forEach { cancel(it.requestId) }          // everything would fit now
        val retry = series(key = "series-key-0003")
        assertEquals(0, retry.result.created, "the first processing is authoritative for this key")
        assertEquals(firstTry.result, retry.result, "a zero-success batch replays identically too")
        assertEquals(0, liveOpen().size)
    }

    @Test
    fun theSameKeyForADifferentSeriesIsRefusedNotMerged() = withScheduling(matrix) {
        series(key = "series-key-0004", weeks = 4)
        val other = series(key = "series-key-0004", weeks = 3)
        assertEquals(HttpStatusCode.Conflict, other.status)
        assertEquals(CONFLICT_IDEMPOTENCY_KEY_REUSED, other.error.code)
    }

    @Test
    fun aSingleRequestThatWasRefusedIsStillEvaluatedAfreshOnRetry() = withScheduling(matrix) {
        val blocker = single(base).result.requests.single()
        val refused = single(base, key = "single-key-0001")
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals(CONFLICT_DUPLICATE_OPEN_REQUEST, refused.error.code)
        cancel(blocker.requestId)
        val retry = single(base, key = "single-key-0001")
        assertEquals(HttpStatusCode.OK, retry.status, "a refused single request records nothing, so its retry is judged again")
        assertEquals(1, retry.result.created)
    }

    // ================================================================ replay is a snapshot, not a refresh
    // A retry of a submission is an idempotent replay of THAT command's answer. It is not "read the latest state": a request
    // that has since been accepted or cancelled still replays exactly as it was created.
    private fun statusOf(requestId: String) = requestStatus(requestId)

    @Test
    fun aRetryReplaysTheOriginalSnapshotEvenAfterTheCreatedRequestsHaveChanged() = withScheduling(matrix) {
        val first = series(key = "snapshot-key-0001")
        val ids = first.result.requests.map { it.requestId }
        assertTrue(first.result.requests.all { it.status == RideRequestStatus.PENDING })

        // Afterwards: the first is accepted by a driver, the second is cancelled by the passenger.
        assertEquals(HttpStatusCode.OK, respond(DRIVER_A, ids[0], RideRequestStatus.ACCEPTED).status)
        cancel(ids[1])
        assertEquals(RideRequestStatus.ACCEPTED, statusOf(ids[0]))
        assertEquals(RideRequestStatus.CANCELLED, statusOf(ids[1]))

        val retry = series(key = "snapshot-key-0001")
        assertEquals(HttpStatusCode.OK, retry.status)
        assertEquals(first.result, retry.result, "the creation response is the original snapshot")
        assertEquals(List(4) { RideRequestStatus.PENDING }, retry.result.requests.map { it.status }, "not the accepted/cancelled state of now")
        assertEquals(ids, retry.result.requests.map { it.requestId })
        assertNull(retry.result.requests[0].rideId, "the original DTO: no ride attached yet")
        val everything = transaction { RideRequests.selectAll().where { RideRequests.passengerId eq PASSENGER }.toList() }
            .count { (it[RideRequests.desiredDepartureTime] ?: 0L) >= base - 60 * MINUTE }
        assertEquals(4, everything, "the retry made nothing: still the four original requests, whatever their status now")
    }

    @Test
    fun theSnapshotIsStoredWholeAsTheFirstResponseWasSent() = withScheduling(matrix) {
        val first = series(key = "snapshot-key-0002")
        val stored = transaction { RequestSubmissions.selectAll().where { RequestSubmissions.clientRequestId eq "snapshot-key-0002" }.first()[RequestSubmissions.result] }!!
        assertTrue(stored.trimStart().startsWith("{"), "a JSON object holding the whole response, not just a list of dates")
        assertEquals(first.result, schedulingJson.decodeFromString<CreateRequestsResponse>(stored))
        assertEquals(first.result, series(key = "snapshot-key-0002").result)
    }

    @Test
    fun aMixedBatchReplaysIdenticallyAfterEveryLiveStateHasChanged() = withScheduling(matrix) {
        // Week 1 free, week 2 clashes with another trip, week 3 is already requested, week 4 free.
        val clash = single(dayLater(7, 10)).result.requests.single()
        val already = single(dayLater(14)).result.requests.single()
        val first = series(key = "snapshot-key-0003")
        assertEquals(listOf(OUTCOME_CREATED, OUTCOME_REJECTED, OUTCOME_REJECTED, OUTCOME_CREATED), first.result.occurrences.map { it.outcome })
        val made = first.result.requests.map { it.requestId }

        // Everything about the live state moves on: the clash and the existing request go away, the new ones are accepted/cancelled.
        cancel(clash.requestId); cancel(already.requestId)
        assertEquals(HttpStatusCode.OK, respond(DRIVER_A, made[0], RideRequestStatus.ACCEPTED).status)
        cancel(made[1])

        val retry = series(key = "snapshot-key-0003")
        assertEquals(first.result, retry.result)
        assertEquals(listOf(CODE_SCHEDULE_INFEASIBLE, CONFLICT_DUPLICATE_OPEN_REQUEST), retry.result.occurrences.mapNotNull { it.code })
        assertEquals(already.requestId, retry.result.occurrences[2].existingRequestId, "the duplicate still names the request it duplicated then")
        assertEquals(clash.requestId, retry.result.occurrences[1].conflict?.conflictingId)
        assertTrue(retry.result.requests.all { it.status == RideRequestStatus.PENDING })
    }

    @Test
    fun aNewKeyAfterTheStateChangedIsJudgedAgainstTheStateNowAndAnswersWithItsOwnSnapshot() = withScheduling(matrix) {
        val first = series(key = "snapshot-key-0004")
        val ids = first.result.requests.map { it.requestId }
        assertEquals(HttpStatusCode.OK, respond(DRIVER_A, ids[0], RideRequestStatus.ACCEPTED).status)
        cancel(ids[1])

        val fresh = series(key = "snapshot-key-0005")
        assertNotEquals(first.result, fresh.result)
        assertEquals(listOf(OUTCOME_REJECTED, OUTCOME_CREATED, OUTCOME_REJECTED, OUTCOME_REJECTED), fresh.result.occurrences.map { it.outcome },
            "week 1 is accepted (so already requested), week 2 was cancelled (so it can be made again)")
        assertEquals(ids[0], fresh.result.occurrences[0].existingRequestId)
        assertEquals(listOf(RideRequestStatus.PENDING), fresh.result.requests.map { it.status })
        // ...and that new answer is itself replayable.
        assertEquals(fresh.result, series(key = "snapshot-key-0005").result)
    }

    @Test
    fun aOneOffRequestReplaysAsCreatedAfterItHasBeenAccepted() = withScheduling(matrix) {
        val first = single(base, key = "snapshot-key-0006")
        val id = first.result.requests.single().requestId
        assertEquals(HttpStatusCode.OK, respond(DRIVER_A, id, RideRequestStatus.ACCEPTED).status)
        val retry = single(base, key = "snapshot-key-0006")
        assertEquals(first.result, retry.result)
        assertEquals(RideRequestStatus.PENDING, retry.result.requests.single().status)
    }

    @Test
    fun aSeatSeriesReplaysAsCreatedAfterARequestInItWasAcceptedOrTheRideBecameFull() = withScheduling(matrix) {
        val ride = postRideGroup(DRIVER_A)
        val rides = groupRides(ride.recurringGroupId!!)
        transaction { Rides.update({ Rides.rideId eq rides[3][Rides.rideId] }) { it[seatsAvailable] = 0 } }
        val first = groupSeat(ride.rideId, key = "snapshot-key-0007", intent = "intent-snapshot-01")
        assertEquals(listOf(7, 6, 1), listOf(first.result.requested, first.result.created, first.result.rejected))

        val accepted = first.result.requests[0].requestId
        assertEquals(HttpStatusCode.OK, respond(DRIVER_A, accepted, RideRequestStatus.ACCEPTED).status)
        cancel(first.result.requests[1].requestId)
        transaction { Rides.update({ Rides.rideId eq rides[3][Rides.rideId] }) { it[seatsAvailable] = 3 } }   // the full ride has room now

        val retry = groupSeat(ride.rideId, key = "snapshot-key-0007", intent = "intent-snapshot-01")
        assertEquals(first.result, retry.result)
        assertEquals(REJECT_RIDE_FULL, retry.result.occurrences[3].code, "still reported full, as it was")
        assertTrue(retry.result.requests.all { it.status == RideRequestStatus.PENDING })
    }

    // ---------------------------------------------------------------- older stored forms stay replayable
    @Test
    fun aRowFromThePreviousVersionThatStoredOnlyTheDatesStillReplaysThemVerbatim() = withScheduling(matrix) {
        single(dayLater(7, 10))                                            // makes week 2 a rejection
        val original = series(key = "snapshot-key-0008").result
        assertEquals(listOf(OUTCOME_CREATED, OUTCOME_REJECTED, OUTCOME_CREATED, OUTCOME_CREATED), original.occurrences.map { it.outcome })
        // Make it look like the previous version's row: a JSON list of the occurrences only.
        transaction {
            RequestSubmissions.update({ RequestSubmissions.clientRequestId eq "snapshot-key-0008" }) {
                it[result] = schedulingJson.encodeToString(ListSerializer(OccurrenceResult.serializer()), original.occurrences)
            }
        }
        assertEquals(HttpStatusCode.OK, respond(DRIVER_A, original.requests[0].requestId, RideRequestStatus.ACCEPTED).status)

        val replay = series(key = "snapshot-key-0008").result
        assertEquals(original.occurrences, replay.occurrences, "the dates, rejected ones included, are verbatim")
        assertEquals(original.requests.map { it.requestId }, replay.requests.map { it.requestId })
        assertEquals(RideRequestStatus.ACCEPTED, replay.requests[0].status, "best effort for this older form: the requests are read back as they are now")
    }

    // ================================================================ single vs batch status semantics
    @Test
    fun aSeriesThatExpandsToOneDateIsASingleRequestAndKeepsTheOrdinaryError() = withScheduling(matrix) {
        single(base)
        // weeks=1 with only the first date's own weekday: no later date, so one occurrence.
        val answer = series(weeks = 1)
        assertEquals(HttpStatusCode.Conflict, answer.status, answer.body)
        assertEquals(CONFLICT_DUPLICATE_OPEN_REQUEST, answer.error.code)
        assertNotNull(answer.error.existingRequestId)
    }

    @Test
    fun aOneOffScheduleConflictKeepsTheOrdinaryErrorToo() = withScheduling(matrix) {
        single(base)
        val answer = single(dayLater(0, 10))
        assertEquals(HttpStatusCode.Conflict, answer.status)
        assertEquals(CODE_SCHEDULE_INFEASIBLE, answer.error.code)
        assertNotNull(answer.error.conflict)
    }

    @Test
    fun aSeriesAtAnUnapprovedLocationIsRefusedAsAWholeBeforeAnyDateIsProcessed() = withScheduling(matrix) {
        val response = postOpen(openRequestBody(from = -37.9 to 145.1, to = Pts.clayton, at = base, repeatDays = listOf(weekday), repeatWeeks = 4))
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(0, liveOpen().size)
    }

    @Test
    fun anInvalidRecurrenceIsRefusedBeforeAnythingIsProcessedAndRecordsNothing() = withScheduling(matrix) {
        for ((days, weeks) in listOf(listOf(0) to 4, listOf(8) to 4, listOf(1, 2) to 0, listOf(1) to 13)) {
            val answer = series(key = "bad-key-000001", days = days, weeks = weeks)
            assertEquals(HttpStatusCode.BadRequest, answer.status, "$days / $weeks")
            assertEquals(CODE_INVALID_RECURRENCE, answer.error.code)
        }
        assertEquals(0, liveOpen().size)
        assertEquals(0L, transaction { RequestSubmissions.selectAll().where { RequestSubmissions.clientRequestId eq "bad-key-000001" }.count() })
        assertEquals(HttpStatusCode.OK, series(key = "bad-key-000001").status, "the key was never used up")
    }

    @Test
    fun weeksDefaultToFourAndAsapIgnoresRepeatDays() = withScheduling(matrix) {
        assertEquals(4, series(weeks = null).result.requested)
        val asap = postOpen(openRequestBody(from = coords(Place.A), to = coords(Place.B), asap = true, repeatDays = everyDay, repeatWeeks = 4), OTHER_PASSENGER)
        assertEquals(HttpStatusCode.OK, asap.status)
        assertEquals(1, creationResult(asap.bodyAsText()).requested)
    }

    // ================================================================ daylight saving, through the API
    @Test
    fun aSeriesAcrossDaylightSavingKeepsItsWallClockTimeAndAllDatesAreMade() = withScheduling(matrix) {
        val rules = zone.rules
        val transition = rules.nextTransition(Instant.ofEpochMilli(base))!!
        val start = transition.dateTimeBefore.toLocalDate().minusDays(2).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        val answer = series(at = start, days = everyDay, weeks = 1)
        assertEquals(HttpStatusCode.OK, answer.status, answer.body)
        val times = answer.result.occurrences.map { Instant.ofEpochMilli(it.departureTime).atZone(zone) }
        assertEquals(7, times.size)
        assertTrue(times.all { it.hour == 8 && it.minute == 0 }, "still 8:00 on the wall clock: $times")
        assertEquals(2, times.map { it.offset }.distinct().size, "the series really does cross the clock change")
        val gaps = times.zipWithNext { a, b -> b.toInstant().toEpochMilli() - a.toInstant().toEpochMilli() }
        assertEquals(setOf(24L * 3600_000L, 24L * 3600_000L - transition.duration.seconds * 1000), gaps.toSet())
        assertEquals(7, answer.result.created)
    }

    // ================================================================ seat requests across a recurring group
    private suspend fun HttpClient.postRideGroup(driver: String, days: List<Int> = everyDay) = run {
        val f = coords(Place.A); val t = coords(Place.B)
        val response = post("/rides") {
            header(HttpHeaders.Authorization, "Bearer $driver"); contentType(ContentType.Application.Json)
            setBody("""{"originLat":${f.first},"originLng":${f.second},"destinationLat":${t.first},"destinationLng":${t.second},
                "distanceMeters":4000.0,"estimatedDurationSeconds":600,"departureTime":$base,"seatsTotal":3,
                "repeatDaysOfWeek":$days,"repeatWeeks":1}""")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        schedulingJson.decodeFromString<RideDto>(response.bodyAsText())
    }

    private fun groupRides(groupId: String) = transaction {
        Rides.selectAll().where { Rides.recurringGroupId eq groupId }.toList()
    }.sortedBy { it[Rides.departureTime] }

    @Test
    fun everyRideOfARecurringGroupReportsItsOwnOutcome() = withScheduling(matrix) {
        val first = postRideGroup(DRIVER_A)
        val rides = groupRides(first.recurringGroupId!!)
        assertEquals(7, rides.size)
        fun rideId(day: Int) = rides[day][Rides.rideId]

        // day 1: already requested. day 2: another trip of the passenger's clashes. day 3: full. day 4: cancelled.
        val alreadyRequested = postSeat(rideId(1)).let { createdRequests(it.bodyAsText()).single() }
        val clash = single(dayLater(2, 10)).result.requests.single()
        transaction { Rides.update({ Rides.rideId eq rideId(3) }) { it[seatsAvailable] = 0 } }
        transaction { Rides.update({ Rides.rideId eq rideId(4) }) { it[status] = RideStatus.CANCELLED } }

        val answer = groupSeat(rideId(0), intent = "intent-series-01")
        assertEquals(HttpStatusCode.OK, answer.status, answer.body)
        val r = answer.result
        assertEquals(listOf(7, 3, 4), listOf(r.requested, r.created, r.rejected))
        val byRide = r.occurrences.associateBy { it.rideId }
        assertEquals(OUTCOME_CREATED, byRide.getValue(rideId(0)).outcome)
        assertEquals(CONFLICT_DUPLICATE_OPEN_REQUEST, byRide.getValue(rideId(1)).code)
        assertEquals(alreadyRequested.requestId, byRide.getValue(rideId(1)).existingRequestId)
        assertEquals(CODE_SCHEDULE_INFEASIBLE, byRide.getValue(rideId(2)).code)
        assertEquals(clash.requestId, byRide.getValue(rideId(2)).conflict?.conflictingId)
        assertEquals(REJECT_RIDE_FULL, byRide.getValue(rideId(3)).code)
        assertEquals(REJECT_RIDE_UNAVAILABLE, byRide.getValue(rideId(4)).code)
        assertEquals(OUTCOME_CREATED, byRide.getValue(rideId(5)).outcome)
        assertEquals(OUTCOME_CREATED, byRide.getValue(rideId(6)).outcome)
        assertEquals(rides.map { it[Rides.departureTime] }, r.occurrences.map { it.departureTime }, "one outcome per ride, none skipped, in order")
    }

    @Test
    fun theJourneyIntentBelongsToTheRidePickedAndIsNeverSharedAcrossDates() = withScheduling(matrix) {
        val first = postRideGroup(DRIVER_A)
        val rides = groupRides(first.recurringGroupId!!)
        val picked = rides[2][Rides.rideId]
        val r = groupSeat(picked, intent = "intent-series-02").result
        assertEquals(7, r.created)
        val intents = transaction {
            r.requests.associate { it.requestId to RideRequests.selectAll().where { RideRequests.requestId eq it.requestId }.first()[RideRequests.journeyIntentId] }
        }
        val forPicked = r.occurrences.single { it.rideId == picked }.requestId!!
        assertEquals("intent-series-02", intents.getValue(forPicked))
        assertTrue(intents.filterKeys { it != forPicked }.values.all { it == null }, "the other dates are different journeys and carry no intent")
    }

    @Test
    fun aGroupOfOneUpcomingRideIsASingleRequestWithTheOrdinaryError() = withScheduling(matrix) {
        val ride = rideBetween(DRIVER_A, base)
        postSeat(ride.rideId)
        val again = groupSeat(ride.rideId)                              // not part of any group: one occurrence
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertEquals(CONFLICT_DUPLICATE_OPEN_REQUEST, again.error.code)
    }

    @Test
    fun yourOwnRidesInAGroupAreReportedNotSilentlySkipped() = withScheduling(matrix) {
        val first = postRideGroup(DRIVER_A)
        val answer = groupSeat(first.rideId, caller = DRIVER_A)
        assertEquals(HttpStatusCode.OK, answer.status)
        val r = answer.result
        assertEquals(listOf(7, 0, 7), listOf(r.requested, r.created, r.rejected))
        assertTrue(r.occurrences.all { it.code == REJECT_OWN_RIDE })
    }

    @Test
    fun aSeatSeriesReplaysExactlyOnRetry() = withScheduling(matrix) {
        val first = postRideGroup(DRIVER_A)
        val rides = groupRides(first.recurringGroupId!!)
        transaction { Rides.update({ Rides.rideId eq rides[3][Rides.rideId] }) { it[seatsAvailable] = 0 } }        // day 3 is full
        val original = groupSeat(first.rideId, key = "seat-key-000001").result
        assertEquals(listOf(7, 6, 1), listOf(original.requested, original.created, original.rejected))
        transaction { Rides.update({ Rides.rideId eq rides[3][Rides.rideId] }) { it[seatsAvailable] = 3 } }        // room again
        val retry = groupSeat(first.rideId, key = "seat-key-000001").result
        assertEquals(original.occurrences, retry.occurrences, "the full ride is still reported as full: not re-evaluated")
        val groupIds = rides.map { it[Rides.rideId] }.toSet()
        val made = transaction { RideRequests.selectAll().where { RideRequests.passengerId eq PASSENGER }.toList() }.filter { it[RideRequests.rideId] in groupIds }
        assertEquals(6, made.size, "the retry made nothing")
    }

    // ================================================================ legacy submission rows
    @Test
    fun aSubmissionRowFromBeforeResultsWereStoredStillReplays() = withScheduling(matrix) {
        val original = series(key = "legacy-key-0001", weeks = 3).result
        // Make it look like a row written by the previous version: ids only, no stored result.
        transaction { RequestSubmissions.update({ RequestSubmissions.clientRequestId eq "legacy-key-0001" }) { it[result] = null } }
        val replay = series(key = "legacy-key-0001", weeks = 3)
        assertEquals(HttpStatusCode.OK, replay.status, replay.body)
        assertEquals(original.requests.map { it.requestId }, replay.result.requests.map { it.requestId })
        assertEquals(listOf(3, 3, 0), listOf(replay.result.requested, replay.result.created, replay.result.rejected), "all that is known is that these were created")
        assertEquals(original.occurrences.map { it.departureTime }, replay.result.occurrences.map { it.departureTime })
        assertEquals(3, liveOpen().size)
    }

    @Test
    fun aLegacySeatSubmissionRowReplaysToo() = withScheduling(matrix) {
        val ride = rideBetween(DRIVER_A, base)
        val first = createdRequests(postSeat(ride.rideId, clientRequestId = "legacy-seat-0001").bodyAsText()).single()
        transaction { RequestSubmissions.update({ RequestSubmissions.clientRequestId eq "legacy-seat-0001" }) { it[result] = null } }
        val replay = creationResult(postSeat(ride.rideId, clientRequestId = "legacy-seat-0001").bodyAsText())
        assertEquals(listOf(first.requestId), replay.requests.map { it.requestId })
        assertEquals(ride.rideId, replay.occurrences.single().rideId)
        assertEquals(base, replay.occurrences.single().departureTime)
    }

    // ================================================================ concurrency
    @Test
    fun aSeriesRacingASingleRequestNeverLeavesAnImpossibleOrDuplicatedTimeline() {
        repeat(5) {
            withScheduling(matrix) {
                // The single request clashes with the series' week-2 date (10 minutes later): exactly one of them gets the slot.
                val answers = coroutineScope {
                    listOf(
                        async(Dispatchers.Default) { series(weeks = 4) },
                        async(Dispatchers.Default) { single(dayLater(7, 10)) }
                    ).awaitAll()
                }
                val batch = answers[0]; val singleAnswer = answers[1]
                assertEquals(HttpStatusCode.OK, batch.status, batch.body)
                val week2 = batch.result.occurrences[1]
                if (singleAnswer.status == HttpStatusCode.OK) {
                    assertEquals(CODE_SCHEDULE_INFEASIBLE, week2.code, "the single request got the slot, so week 2 is rejected: ${batch.body}")
                } else {
                    assertEquals(HttpStatusCode.Conflict, singleAnswer.status)
                    assertEquals(OUTCOME_CREATED, week2.outcome, "the series got the slot, so the single request was refused: ${singleAnswer.body}")
                }
                assertTimelineIsFeasible(); assertNoJourneyIsDuplicated()
            }
        }
    }

    @Test
    fun twoSubmissionsOfTheSameSeriesAtOnceMakeEachDateExactlyOnce() {
        repeat(5) {
            withScheduling(matrix) {
                val answers = coroutineScope {
                    listOf(async(Dispatchers.Default) { series(key = "series-race-0001") }, async(Dispatchers.Default) { series(key = "series-race-0002") }).awaitAll()
                }
                answers.forEach { assertEquals(HttpStatusCode.OK, it.status, it.body) }
                assertEquals(4, liveOpen().size, "four dates, once each")
                for (week in 0..3) {
                    val outcomes = answers.map { it.result.occurrences[week] }
                    assertEquals(1, outcomes.count { it.outcome == OUTCOME_CREATED }, "week ${week + 1} was made by exactly one of the two submissions")
                    assertEquals(CONFLICT_DUPLICATE_OPEN_REQUEST, outcomes.single { it.outcome == OUTCOME_REJECTED }.code)
                }
                assertNoJourneyIsDuplicated(); assertTimelineIsFeasible()
            }
        }
    }

    @Test
    fun aRetryRacingTheOriginalGetsTheSameAnswerAndNothingIsMadeTwice() {
        repeat(5) {
            withScheduling(matrix) {
                val blocker = single(dayLater(7, 10)).result.requests.single()
                val answers = coroutineScope {
                    listOf(async(Dispatchers.Default) { series(key = "series-race-0003") }, async(Dispatchers.Default) { series(key = "series-race-0003") }).awaitAll()
                }
                answers.forEach { assertEquals(HttpStatusCode.OK, it.status, it.body) }
                assertEquals(answers[0].result.occurrences, answers[1].result.occurrences, "original and retry agree on every date")
                assertEquals(answers[0].result.requests.map { it.requestId }, answers[1].result.requests.map { it.requestId })
                assertEquals(3 + 1, liveOpen().size, "three dates made once, plus the blocker")
                assertNotNull(blocker)
                assertNoJourneyIsDuplicated(); assertTimelineIsFeasible()
            }
        }
    }

    @Test
    fun aDuplicateDateIntroducedAtTheSameMomentIsRejectedOnExactlyOneSide() {
        repeat(5) {
            withScheduling(matrix) {
                val answers = coroutineScope {
                    listOf(async(Dispatchers.Default) { series(weeks = 4) }, async(Dispatchers.Default) { single(dayLater(14)) }).awaitAll()
                }
                val batch = answers[0]; val singleAnswer = answers[1]
                val week3 = batch.result.occurrences[2]
                if (singleAnswer.status == HttpStatusCode.OK) {
                    assertEquals(CONFLICT_DUPLICATE_OPEN_REQUEST, week3.code, "the single request was first, so the series' week 3 is a duplicate")
                    assertEquals(singleAnswer.result.requests.single().requestId, week3.existingRequestId)
                } else {
                    assertEquals(HttpStatusCode.Conflict, singleAnswer.status)
                    assertEquals(CONFLICT_DUPLICATE_OPEN_REQUEST, singleAnswer.error.code)
                    assertEquals(OUTCOME_CREATED, week3.outcome)
                    assertEquals(week3.requestId, singleAnswer.error.existingRequestId)
                }
                assertEquals(1, liveOpen().count { it[RideRequests.desiredDepartureTime] == dayLater(14) }, "week 3 exists once")
                assertNoJourneyIsDuplicated(); assertTimelineIsFeasible()
            }
        }
    }
}
