package com.example.ds04_monash_rideshare_application.backend

import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.selectAll

// Creating requests is "one or more independent occurrences". Each one goes through the whole pipeline on its own -
// duplicate check, planned interval, physical feasibility (ScheduleFeasibility.kt), insert - and each reports its own
// outcome. An occurrence that was made is part of the passenger's pending timeline for the later ones, so they are
// always processed in chronological order. A rejected occurrence is NOT an error of the call and rolls nothing back; it is
// reported. (A CONFLICTED request is something else entirely: a request that existed and was later invalidated.)
//
// What the caller sees:
//   * exactly one occurrence that cannot be made  -> the ordinary HTTP error, exactly as for any single request;
//   * more than one occurrence                    -> 200 and a CreateRequestsResponse, however many were made (even none);
//   * a problem with the submission as a whole (malformed, invalid recurrence, unapproved endpoint, unknown ride,
//     unauthorised, no routing data)              -> the ordinary HTTP error, before or instead of any occurrence.
const val OUTCOME_CREATED = "CREATED"
const val OUTCOME_REJECTED = "REJECTED"

// Reasons an occurrence was not made, besides the codes the single-request errors already use
// (CONFLICT_DUPLICATE_OPEN_REQUEST, CODE_SCHEDULE_INFEASIBLE, CODE_TRAVEL_TIME_UNAVAILABLE).
const val REJECT_RIDE_FULL = "RIDE_FULL"
const val REJECT_RIDE_UNAVAILABLE = "RIDE_UNAVAILABLE"
const val REJECT_OWN_RIDE = "OWN_RIDE"

class Rejection(
    val code: String,
    val message: String,
    val existingRequestId: String? = null,
    val conflict: ScheduleConflictDetails? = null
) {
    // The error a SINGLE request that was rejected for this reason has always produced.
    fun toException(): ApiException = when (code) {
        CONFLICT_DUPLICATE_OPEN_REQUEST -> duplicateRequestError(existingRequestId!!)
        CODE_SCHEDULE_INFEASIBLE -> scheduleInfeasibleError(conflict!!)
        CODE_TRAVEL_TIME_UNAVAILABLE -> ServiceUnavailableException(message, code)
        else -> ConflictException("Couldn't request this ride - it may be full, unavailable, or your own")
    }

    companion object {
        fun duplicate(existingRequestId: String) = Rejection(CONFLICT_DUPLICATE_OPEN_REQUEST, "You've already sent this request", existingRequestId)
        fun scheduleConflict(details: ScheduleConflictDetails) = Rejection(CODE_SCHEDULE_INFEASIBLE, "This doesn't fit your schedule", conflict = details)
        fun cannotVerify() = Rejection(
            CODE_TRAVEL_TIME_UNAVAILABLE, "We couldn't verify whether this fits your schedule right now. Please try again later."
        )
        fun full() = Rejection(REJECT_RIDE_FULL, "That ride is full")
        fun unavailable() = Rejection(REJECT_RIDE_UNAVAILABLE, "That ride is no longer open")
        fun ownRide() = Rejection(REJECT_OWN_RIDE, "That's your own ride")
    }
}

fun createdOccurrence(departure: Long, requestId: String, rideId: String? = null) =
    OccurrenceResult(departureTime = departure, outcome = OUTCOME_CREATED, rideId = rideId, requestId = requestId)

fun rejectedOccurrence(departure: Long, rejection: Rejection, rideId: String? = null) = OccurrenceResult(
    departureTime = departure, outcome = OUTCOME_REJECTED, rideId = rideId, code = rejection.code, message = rejection.message,
    existingRequestId = rejection.existingRequestId, conflict = rejection.conflict
)

// One occurrence's result, with the reason in a form that can become the ordinary HTTP error.
class Processed(val occurrence: OccurrenceResult, val rejection: Rejection? = null)

// A single occurrence that could not be made is the plain HTTP error it always was (and records nothing, so a retry is
// evaluated afresh). Anything larger is a processed batch whatever its outcomes. Throwing here, inside the scheduling
// transaction, undoes nothing that matters: a rejected occurrence inserted nothing.
fun List<Processed>.occurrencesOrThrowIfSingleRejected(): List<OccurrenceResult> {
    if (size == 1 && single().rejection != null) throw single().rejection!!.toException()
    return map { it.occurrence }
}

// The answer for a call: a stored snapshot is returned exactly as first produced (idempotent replay - never refreshed).
// Only an older stored form without a snapshot is reconstructed, from what is known now.
fun Transaction.creationResponse(stored: StoredSubmission): CreateRequestsResponse =
    stored.snapshot ?: reconstructedResponse(stored)

// The response for occurrences that were JUST processed, in the transaction that processed them: this is the snapshot
// that is answered, recorded against the clientRequestId, and replayed for every retry.
fun Transaction.snapshotOf(occurrences: List<OccurrenceResult>): CreateRequestsResponse =
    reconstructedResponse(StoredSubmission(occurrences.mapNotNull { it.requestId }, occurrences))

private fun Transaction.reconstructedResponse(stored: StoredSubmission): CreateRequestsResponse {
    val rows = if (stored.requestIds.isEmpty()) emptyList()
        else RideRequests.selectAll().where { RideRequests.requestId inList stored.requestIds }.toList()
    val occurrences = stored.occurrences ?: rows.sortedBy { stored.requestIds.indexOf(it[RideRequests.requestId]) }.map { row ->
        // A row from before results were stored: all we know is that these requests were created.
        val rideId = row[RideRequests.rideId]
        val departure = rideId?.let { id -> Rides.selectAll().where { Rides.rideId eq id }.firstOrNull()?.get(Rides.departureTime) }
            ?: row[RideRequests.desiredDepartureTime] ?: row[RideRequests.createdAt]
        createdOccurrence(departure, row[RideRequests.requestId], rideId)
    }
    val order = occurrences.mapNotNull { it.requestId }
    val requests = rows.map { it.toRideRequestDto() }.sortedBy { order.indexOf(it.requestId) }
    return CreateRequestsResponse(
        requested = occurrences.size,
        created = occurrences.count { it.outcome == OUTCOME_CREATED },
        rejected = occurrences.count { it.outcome == OUTCOME_REJECTED },
        requests = requests,
        occurrences = occurrences
    )
}
