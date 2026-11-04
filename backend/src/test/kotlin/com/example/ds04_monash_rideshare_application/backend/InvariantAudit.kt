package com.example.ds04_monash_rideshare_application.backend

import io.ktor.client.HttpClient
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Database invariants that must hold after ANY sequence of the scheduling operations, checked against the stored rows
// (not against what an endpoint said). Each scenario ends with auditInvariants(); a violation names the rows involved.
//
// Rows deliberately written by a test to look like an old version's data (legacy duplicates, a seat COMPLETED on a live ride) are
// not what this audits - it is for scenarios that go through the real routes.
class InvariantScope(
    // Rides whose seat accounting is audited (tests insert some rides with arbitrary seat counts as fixtures).
    val capacityRides: Collection<String> = emptyList(),
    // Rides the driver completed (completedAt must be set, at or after departure) / the system completed (completedAt must be null).
    val manuallyCompleted: Collection<String> = emptyList(),
    val autoCompleted: Collection<String> = emptyList(),
    // request_submissions results captured earlier (snapshotSubmissions()); they must be byte-identical now.
    val submissionsBefore: Map<Pair<String, String>, String?>? = null
)

fun snapshotSubmissions(): Map<Pair<String, String>, String?> = transaction {
    RequestSubmissions.selectAll().associate { (it[RequestSubmissions.passengerId] to it[RequestSubmissions.clientRequestId]) to it[RequestSubmissions.result] }
}

fun auditInvariants(scope: InvariantScope = InvariantScope()) {
    val problems = mutableListOf<String>()
    transaction {
        val rides = Rides.selectAll().toList().associateBy { it[Rides.rideId] }
        val requests = RideRequests.selectAll().toList()
        val live = setOf(RideRequestStatus.PENDING, RideRequestStatus.ACCEPTED)

        // 1. One physical commitment per intended journey: a journey-intent group has at most one ACCEPTED alternative, and a
        //    passenger never holds two ACCEPTED requests for the same posted ride.
        requests.filter { it[RideRequests.journeyIntentId] != null && it[RideRequests.status] == RideRequestStatus.ACCEPTED }
            .groupBy { it[RideRequests.passengerId] to it[RideRequests.journeyIntentId] }
            .filter { it.value.size > 1 }
            .forEach { (key, rows) -> problems += "intent group $key has ${rows.size} ACCEPTED alternatives: ${rows.map { it[RideRequests.requestId] }}" }
        requests.filter { it[RideRequests.rideId] != null && it[RideRequests.status] in live }
            .groupBy { it[RideRequests.passengerId] to it[RideRequests.rideId] }
            .filter { it.value.size > 1 }
            .forEach { (key, rows) -> problems += "passenger/ride $key has ${rows.size} live requests: ${rows.map { it[RideRequests.requestId] }}" }

        // 2. No live exact duplicate of an open request (same passenger, same canonical route, within the +-1 minute rule) among
        //    rows the current code created (it stamps a journey key).
        requests.filter { it[RideRequests.rideId] == null && it[RideRequests.status] in live && it[RideRequests.journeyKey] != null }
            .groupBy { Triple(it[RideRequests.passengerId], it[RideRequests.originPointId], it[RideRequests.destinationPointId]) }
            .forEach { (key, rows) ->
                val times = rows.map { (if (it[RideRequests.isAsap]) it[RideRequests.createdAt] else it[RideRequests.desiredDepartureTime]) ?: 0L }.sorted()
                times.zipWithNext().filter { (x, y) -> y - x <= SchedulingConfig.DUPLICATE_TOLERANCE_MILLIS }
                    .forEach { problems += "live duplicate open requests for $key at $it" }
            }

        // 3. Status coherence between a request and its ride.
        for (request in requests) {
            val id = request[RideRequests.requestId]
            val status = request[RideRequests.status]
            val ride = request[RideRequests.rideId]?.let { rides[it] }
            if (status == RideRequestStatus.CONFLICTED && request[RideRequests.conflictDetails] == null) problems += "$id is CONFLICTED with no reason"
            if (status != RideRequestStatus.CONFLICTED && request[RideRequests.conflictDetails] != null) problems += "$id has a conflict reason but is $status"
            if (ride != null) {
                val rideStatus = ride[Rides.status]
                if (status == RideRequestStatus.ACCEPTED && rideStatus !in setOf(RideStatus.OPEN, RideStatus.CONFIRMED, RideStatus.COMPLETED)) problems += "$id is ACCEPTED on a $rideStatus ride"
                if (status == RideRequestStatus.ACCEPTED && rideStatus == RideStatus.COMPLETED) problems += "$id is still ACCEPTED on a COMPLETED ride"
                if (status == RideRequestStatus.COMPLETED && rideStatus != RideStatus.COMPLETED) problems += "$id is COMPLETED but its ride is $rideStatus (the ride owns completion)"
                if (status == RideRequestStatus.PENDING && rideStatus in setOf(RideStatus.CANCELLED, RideStatus.COMPLETED)) problems += "$id is still PENDING on a $rideStatus ride"
            }
            if (status == RideRequestStatus.ACCEPTED && ride == null) problems += "$id is ACCEPTED with no ride"
        }

        // 4. Completion times: only a COMPLETED ride has one, never before it left; the driver's completion has one, the
        //    system's never does.
        for ((id, ride) in rides) {
            val at = ride[Rides.completedAt]
            if (at != null && ride[Rides.status] != RideStatus.COMPLETED) problems += "$id has completedAt but is ${ride[Rides.status]}"
            if (at != null && at < ride[Rides.departureTime]) problems += "$id completedAt precedes its departure"
        }
        scope.manuallyCompleted.forEach { id ->
            val ride = rides[id]
            if (ride == null || ride[Rides.status] != RideStatus.COMPLETED || ride[Rides.completedAt] == null) problems += "$id was completed by its driver but has no completedAt"
        }
        scope.autoCompleted.forEach { id ->
            val ride = rides[id]
            if (ride == null || ride[Rides.status] != RideStatus.COMPLETED) problems += "$id should be auto-completed"
            else if (ride[Rides.completedAt] != null) problems += "$id was completed by the system but was stamped with completedAt ${ride[Rides.completedAt]}"
        }

        // 5. Seat accounting: seats available = seats total - seats held by ACCEPTED/COMPLETED requests (not for a cancelled ride,
        //    whose capacity is no longer bookable).
        for (id in scope.capacityRides) {
            val ride = rides[id] ?: continue
            if (ride[Rides.status] == RideStatus.CANCELLED) continue
            val held = requests.filter { it[RideRequests.rideId] == id && it[RideRequests.status] in setOf(RideRequestStatus.ACCEPTED, RideRequestStatus.COMPLETED) }
                .sumOf { it[RideRequests.seatsRequested] }
            val expected = ride[Rides.seatsTotal] - held
            if (ride[Rides.seatsAvailable] != expected) problems += "ride $id has ${ride[Rides.seatsAvailable]} seats available, expected $expected ($held held)"
            if (ride[Rides.seatsAvailable] !in 0..ride[Rides.seatsTotal]) problems += "ride $id seat count out of range"
        }

        // 6. A stored creation snapshot is a record of the creation call: later lifecycle changes never reach it. Every request
        //    inside it was PENDING when it was created, and it is exactly what it was.
        val json = Json { ignoreUnknownKeys = true }
        RequestSubmissions.selectAll().forEach { row ->
            val key = row[RequestSubmissions.passengerId] to row[RequestSubmissions.clientRequestId]
            val result = row[RequestSubmissions.result]
            if (scope.submissionsBefore != null && key in scope.submissionsBefore && scope.submissionsBefore[key] != result) problems += "submission $key was rewritten"
            if (result != null && result.trimStart().startsWith("{")) {
                val parsed = runCatching { json.decodeFromString<CreateRequestsResponse>(result) }.getOrNull()
                if (parsed == null) problems += "submission $key does not parse"
                else {
                    parsed.requests.filter { it.status != RideRequestStatus.PENDING }.forEach { problems += "submission $key snapshot shows ${it.requestId} as ${it.status}" }
                    if (parsed.created + parsed.rejected != parsed.occurrences.size) problems += "submission $key counts disagree with its occurrences"
                }
            }
        }

        // 7. Warnings belong to real users and are unique per (user, journey, cause) - the index enforces it; check the owner exists.
        val users = Users.selectAll().map { it[Users.userId] }.toSet()
        ScheduleWarnings.selectAll().forEach { if (it[ScheduleWarnings.userId] !in users) problems += "warning ${it[ScheduleWarnings.warningId]} has no owner" }
    }
    assertTrue(problems.isEmpty(), "invariants violated:\n" + problems.joinToString("\n"))
}

// 8. What a driver is OFFERED (GET /requests): a standalone request is only ever shown while PENDING; nothing that has ended
//    (cancelled, rejected, expired, conflicted, completed) is offered as an open ask, and nobody is shown a conflict reason that
//    belongs to a passenger's other trips.
suspend fun HttpClient.assertFeedsOfferOnlyWhatIsPending(drivers: Collection<String>) {
    for (driver in drivers) {
        val feed = driverFeedAll(driver)
        feed.filter { it.rideId == null }.forEach {
            assertEquals(RideRequestStatus.PENDING, it.status, "$driver is offered ${it.requestId}, which is ${it.status}")
        }
        feed.filter { it.passengerId != driver }.forEach {
            assertTrue(it.conflict == null, "$driver can read a conflict reason on ${it.requestId}")
        }
    }
}
