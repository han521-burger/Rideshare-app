package com.example.ds04_monash_rideshare_application.backend

import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import java.util.UUID

// What a ride that FINISHES (really finishes, at a known time) does to the schedules of the people on it.
//
// WHEN. Only when the driver explicitly completes the ride (PUT /rides/{id} status=COMPLETED, RideRoutes.kt), which stamps
// Rides.completedAt from the server clock. The system completing an old ride on its own (Lifecycle.kt auto-completion) is
// NOT such an event: nothing new is known about when anyone arrived, the planned arrival it already scheduled with is
// unchanged, so nothing is revalidated and completedAt stays null.
//
// WHO. Each person on the ride, one at a time - the driver and every passenger whose seat was ACCEPTED when it completed.
// Nobody else is read or written. The caller holds all of their scheduling locks (Concurrency.kt) and the whole thing is part
// of the completion's own transaction, so either the ride completes AND these consequences are recorded, or none of it is.
//
// WHAT, per person, against their timeline as it now is (their completed ride is history, arriving at completedAt):
//  * Their PENDING journeys that can no longer happen become CONFLICTED, with the structured reason - exactly the rule and
//    the deterministic order (departure, then id) that accepting a request already uses. Never CANCELLED, never silently
//    dropped. A PENDING journey that still fits stays PENDING.
//  * Their COMMITTED journeys (an ACCEPTED seat, a ride they drive) are never changed: a confirmed booking involves
//    someone else and may still be makeable. If the actual completion leaves too little time to reach one, the person gets
//    a private schedule WARNING (ScheduleWarnings) and nothing else happens.
//  * A request that is already CONFLICTED (or expired, cancelled, rejected...) is terminal and is not looked at: finishing
//    early never brings one back.
const val CODE_RIDE_NOT_STARTED = "RIDE_NOT_STARTED"
const val MESSAGE_RIDE_NOT_STARTED = "This ride hasn't started yet, so it can't be completed."

// A seat cannot be completed on its own: the trip is completed when the driver completes the ride. (409, nothing changes.)
const val CODE_RIDE_NOT_COMPLETED = "RIDE_NOT_COMPLETED"
const val MESSAGE_RIDE_NOT_COMPLETED = "This trip is completed when the driver completes the ride."

private val outputJson = Json { encodeDefaults = true }

// Must run inside the completion's scheduling transaction, after the ride and its requests have been updated.
fun Transaction.revalidateSchedulesAfterCompletion(rideId: String, personIds: Collection<String>, travelTimes: TravelTimeProvider, at: Long) {
    val ride = Rides.selectAll().where { Rides.rideId eq rideId }.first()
    val completed = completedRideJourney(ride, rideId, JourneyKind.RIDE, travelTimes)
    for (personId in lockOrder(personIds)) {
        val journeys = liveJourneysOf(personId, travelTimes)

        pendingJourneysToInvalidate(journeys, travelTimes, tolerateUnverifiable = true).forEach { invalidated ->
            markConflicted(invalidated.journey.id, outputJson.encodeToString(ScheduleConflictDetails.serializer(), invalidated.conflict), at)
        }

        if (completed == null) continue
        committedJourneysAtRisk(completed, journeys, travelTimes).forEach { atRisk ->
            recordLateWarning(personId, atRisk, ride, completed, at)
        }
    }
}

private fun recordLateWarning(personId: String, atRisk: JourneyAtRisk, ride: ResultRow, completed: Journey, at: Long) {
    val next = atRisk.journey
    val details = ScheduleWarningDetails(
        previousRideId = ride[Rides.rideId],
        previousCompletedAt = completed.arrival,
        previousPlannedArrivalTime = ride[Rides.plannedArrivalTime],
        fromPointId = completed.destinationPointId,
        fromPointName = completed.destinationPointId?.let { approvedPointById(it)?.name },
        toPointId = next.originPointId,
        toPointName = next.originPointId?.let { approvedPointById(it)?.name },
        repositioningSeconds = atRisk.repositioningSeconds,
        transferBufferSeconds = SchedulingConfig.TRANSFER_BUFFER_MILLIS / 1000,
        earliestFeasibleDeparture = atRisk.earliestFeasibleDeparture,
        nextDepartureTime = next.departure
    )
    ScheduleWarnings.insert {
        it[warningId] = "warn-${UUID.randomUUID()}"
        it[userId] = personId
        it[subjectKind] = if (next.state == JourneyState.DRIVING) WARNING_SUBJECT_RIDE else WARNING_SUBJECT_REQUEST
        it[subjectId] = next.id
        it[causeRideId] = ride[Rides.rideId]
        it[type] = WARNING_PREVIOUS_RIDE_FINISHED_LATE
        it[ScheduleWarnings.details] = outputJson.encodeToString(ScheduleWarningDetails.serializer(), details)
        it[createdAt] = at
    }
}

// The caller's warnings that still matter: the journey is still the one they committed to. A warning about a request that
// has since been cancelled or has completed, or a ride that was cancelled or has completed, is not shown (no recovery
// engine: the row is simply no longer relevant). Strictly the caller's own - never anyone else's. Read-only.
fun Transaction.liveScheduleWarningsOf(userId: String): List<ScheduleWarningDto> {
    val rows = ScheduleWarnings.selectAll().where { ScheduleWarnings.userId eq userId }.toList()
    if (rows.isEmpty()) return emptyList()
    val requestIds = rows.filter { it[ScheduleWarnings.subjectKind] == WARNING_SUBJECT_REQUEST }.map { it[ScheduleWarnings.subjectId] }
    val rideIds = rows.filter { it[ScheduleWarnings.subjectKind] == WARNING_SUBJECT_RIDE }.map { it[ScheduleWarnings.subjectId] }
    val liveRequests = if (requestIds.isEmpty()) emptySet() else RideRequests.selectAll()
        .where { (RideRequests.requestId inList requestIds) and (RideRequests.passengerId eq userId) and (RideRequests.status eq RideRequestStatus.ACCEPTED) }
        .map { it[RideRequests.requestId] }.toSet()
    val liveRides = if (rideIds.isEmpty()) emptySet() else Rides.selectAll()
        .where { (Rides.rideId inList rideIds) and (Rides.driverId eq userId) and (Rides.status inList listOf(RideStatus.OPEN, RideStatus.CONFIRMED)) }
        .map { it[Rides.rideId] }.toSet()
    val reader = Json { ignoreUnknownKeys = true }
    return rows
        .filter {
            val live = if (it[ScheduleWarnings.subjectKind] == WARNING_SUBJECT_REQUEST) liveRequests else liveRides
            it[ScheduleWarnings.subjectId] in live
        }
        .sortedWith(compareBy({ it[ScheduleWarnings.createdAt] }, { it[ScheduleWarnings.warningId] }))
        .mapNotNull { row ->
            val details = runCatching { reader.decodeFromString(ScheduleWarningDetails.serializer(), row[ScheduleWarnings.details]) }.getOrNull()
                ?: return@mapNotNull null
            ScheduleWarningDto(
                warningId = row[ScheduleWarnings.warningId], type = row[ScheduleWarnings.type],
                subjectKind = row[ScheduleWarnings.subjectKind], subjectId = row[ScheduleWarnings.subjectId],
                createdAt = row[ScheduleWarnings.createdAt], details = details
            )
        }
}
