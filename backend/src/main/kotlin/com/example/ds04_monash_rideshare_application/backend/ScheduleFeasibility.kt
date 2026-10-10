package com.example.ds04_monash_rideshare_application.backend

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import java.util.concurrent.atomic.AtomicInteger

// Can a person physically get through their journeys in order?
//
// A person's TIMELINE is the journeys they are committed to or have asked for: requests that are PENDING or
// ACCEPTED, and rides they are driving (OPEN/CONFIRMED). Rejected, cancelled, expired and completed ones reserve
// nothing. Every journey has a canonical origin and destination (approved-point ids), a departure, and a planned
// arrival = departure + the backend's PLANNED duration (the travel-time matrix; never MapKit's displayed ETA).
//
// A new journey fits when it can be slotted into the ordered timeline with room on BOTH sides:
//
//   previous journey -> new:   previous.arrival + reposition(previous.destination -> new.origin) + TRANSFER_BUFFER <= new.departure
//   new -> next journey:       new.arrival      + reposition(new.destination -> next.origin)      + TRANSFER_BUFFER <= next.departure
//
// reposition() is the matrix's directional driving time (0 for the same point; the buffer still applies, so
// "same place" needs 10 minutes, not 0). A pair the matrix has no value for cannot be verified: the request is
// refused recoverably (TRAVEL_TIME_UNAVAILABLE), never guessed. There is no "same route within N minutes" rule:
// that behaviour falls out of these two inequalities.
//
// ALTERNATIVES. A passenger may ask several drivers' rides for ONE intended journey (2:00, 2:05, 2:10). Those are
// not three trips, so PENDING seat requests that carry the same explicit journeyIntentId AND the same origin and
// destination share ONE timeline slot (a TimelineNode). Route and time alone never make requests alternatives: two
// same-route requests 25 minutes apart with different (or no) intents are two journeys, and each must fit. Open
// (standalone) requests, accepted journeys, driven rides and requests without an intent are always their own
// slot. There is deliberately no time window: how far apart one search's results can be is the search's business,
// not something to guess here. A slot is checked at its earliest departure against the journey before it and at its
// latest arrival against the journey after it, so the plan holds whichever alternative is finally taken.
//
// HISTORY. A COMPLETED journey is not just an inactive one: it establishes where the person physically ended and when.
// The person's most recent completed journey (the one that ended last) joins the timeline as a COMPLETED, committed node
// whose arrival is the EFFECTIVE arrival:
//
//   effectiveArrival = ride.completedAt ?: plannedArrival
//
// completedAt is the server-stamped time the driver explicitly completed the ride (Rides.completedAt); a ride the system
// completed on its own has none and keeps its planned arrival (that moment is not when anyone arrived, so it is never
// invented). A new journey is then judged against it by the same inequality as any other previous journey - matrix
// reposition + the 10-minute buffer, directional - so there is no fixed "cooldown": once there is time to get from where
// they ended to the new pickup, it fits. History is only ever a "previous" journey: nothing is ever refused for being
// BEFORE a journey that has already happened. A completed journey that cannot be placed on the map (a legacy endpoint that
// is not an approved point) establishes nothing and is skipped, as it was before history existed.
//
// LEGACY ROWS (made before planning fields existed) are never rewritten. One whose endpoints resolve to approved
// points and which the matrix covers takes part with an interval derived IN MEMORY. One that cannot be placed
// (an endpoint that is not an approved point, e.g. a retired hub) is kept as an "unverified" journey: it cannot be
// reasoned about by location, so a new journey is refused if it overlaps it in time (the old +-30 minute rule),
// which is the only place that old rule - and the straight-line 40 km/h estimate it needs - remains. Rows created
// by this code are never unverified.

const val CODE_SCHEDULE_INFEASIBLE = "SCHEDULE_INFEASIBLE"

const val CONFLICT_TOO_SOON_AFTER_PREVIOUS = "TOO_SOON_AFTER_PREVIOUS"
const val CONFLICT_TOO_LATE_FOR_NEXT = "TOO_LATE_FOR_NEXT"
const val CONFLICT_OVERLAPS_UNVERIFIED_LEGACY = "OVERLAPS_UNVERIFIED_LEGACY"

// Counts uses of the legacy time estimates (straight-line distance at 40 km/h, or a ride's MapKit duration, used
// as a scheduling interval). New journeys must never reach it; a regression test watches this.
object LegacyScheduling {
    private val uses = AtomicInteger()
    val legacyEstimateUses: Int get() = uses.get()
    fun noteLegacyEstimate() { uses.incrementAndGet() }
}

enum class JourneyKind { OPEN_REQUEST, SEAT_REQUEST, RIDE }
// COMPLETED is history, not a plan: a journey that has happened (see HISTORY above). It is committed - it can never be
// invalidated or displaced - but it only ever constrains what comes AFTER it.
enum class JourneyState { PENDING, ACCEPTED, DRIVING, COMPLETED }

class Journey(
    val id: String,
    val kind: JourneyKind,
    val state: JourneyState,
    val originPointId: String?,
    val destinationPointId: String?,
    val departure: Long,
    val arrival: Long,
    // true = could not be placed on the map (legacy only): judged by time overlap alone.
    val unverified: Boolean = false,
    // The explicit journey intent of a seat request (RideRequests.journeyIntentId); null = a journey of its own.
    val intentId: String? = null
)

// One slot on the timeline: a single journey, or a set of alternatives for the same intended journey.
class TimelineNode(val members: List<Journey>) {
    val departure: Long = members.minOf { it.departure }
    val arrival: Long = members.maxOf { it.arrival }
    val originPointId: String? = members.first().originPointId
    val destinationPointId: String? = members.first().destinationPointId
    val unverified: Boolean = members.first().unverified
    // Only a COMPLETED journey: it has already happened, so it can be the journey before another but never the one after.
    val isHistory: Boolean = members.all { it.state == JourneyState.COMPLETED }
    val earliestMember: Journey get() = members.minByOrNull { it.departure }!!
    val latestMember: Journey get() = members.maxByOrNull { it.arrival }!!
}

// Only a still-pending seat request that declared an intent can be one of several alternatives.
private fun Journey.canBeAlternative() =
    state == JourneyState.PENDING && kind == JourneyKind.SEAT_REQUEST && !unverified && intentId != null

// Same declared intent AND the same canonical route. The route test is not redundant: an intent id that somehow
// arrives on a different route is never merged into this slot (it is also refused when it is created - see
// requireIntentRouteConsistent).
private fun TimelineNode.takes(journey: Journey): Boolean =
    journey.canBeAlternative() &&
        members.all { it.canBeAlternative() && it.intentId == journey.intentId } &&
        originPointId == journey.originPointId && destinationPointId == journey.destinationPointId

fun buildTimelineNodes(journeys: List<Journey>): List<TimelineNode> {
    val groups = mutableListOf<MutableList<Journey>>()
    for (journey in journeys.sortedWith(compareBy({ it.departure }, { it.id }))) {
        val group = groups.firstOrNull { TimelineNode(it).takes(journey) }
        if (group != null) group += journey else groups += mutableListOf(journey)
    }
    return groups.map { TimelineNode(it) }
}

private fun unavailable() = ServiceUnavailableException(
    "We couldn't verify whether this fits your schedule right now. Please try again later.",
    CODE_TRAVEL_TIME_UNAVAILABLE
)

private fun pointName(id: String?) = id?.let { approvedPointById(it)?.name }

private fun kindOf(journey: Journey) = journey.kind.name

// The first reason the proposed journey does not fit, or null if it does. Pure: no database.
fun findScheduleConflict(existing: List<TimelineNode>, proposed: Journey, travelTimes: TravelTimeProvider): ScheduleConflictDetails? {
    val bufferMillis = SchedulingConfig.TRANSFER_BUFFER_MILLIS
    val proposedWindow = TripWindow(proposed.departure, proposed.arrival)

    fun legacyOverlap(node: TimelineNode) = ScheduleConflictDetails(
        type = CONFLICT_OVERLAPS_UNVERIFIED_LEGACY,
        conflictingId = node.members.first().id,
        conflictingKind = kindOf(node.members.first()),
        conflictingDepartureTime = node.departure,
        conflictingArrivalTime = node.arrival,
        transferBufferSeconds = bufferMillis / 1000,
        proposedDepartureTime = proposed.departure,
        proposedArrivalTime = proposed.arrival
    )

    // Journeys that cannot be placed on the map: time overlap is all there is to go on.
    existing.firstOrNull { it.unverified && TripWindow(it.departure, it.arrival).overlaps(proposedWindow) }
        ?.let { return legacyOverlap(it) }
    val placed = existing.filter { !it.unverified }
    if (proposed.unverified) {
        placed.firstOrNull { TripWindow(it.departure, it.arrival).overlaps(proposedWindow) }?.let { return legacyOverlap(it) }
        return null
    }

    val joined = placed.firstOrNull { it.takes(proposed) }
    val others = placed.filter { it !== joined }
    val merged = TimelineNode((joined?.members ?: emptyList()) + proposed)
    val ordered = (others + merged).sortedWith(compareBy({ it.departure }, { if (it === merged) 1 else 0 }))
    val position = ordered.indexOf(merged)

    // The journey before: normally just the node ahead of it in time. But a committed journey that DEPARTS BEFORE the person's
    // latest completed journey actually finished (a later accepted ride left standing after a ride that ran late - it is warned
    // about, not cancelled) cannot be trusted to have happened, and must not hide where the person provably was: they were at
    // the history node's destination at its arrival. So that history node is checked as well.
    val previousNode = ordered.getOrNull(position - 1)
    val staleBeforeHistory = previousNode?.takeIf { !it.isHistory }?.let { prev ->
        ordered.take(position).filter { it.isHistory && prev.departure < it.arrival }.maxByOrNull { it.arrival }
    }
    listOfNotNull(previousNode, staleBeforeHistory).forEach { previous ->
        val reposition = travelTimes.repositioningSeconds(previous.destinationPointId!!, merged.originPointId!!) ?: throw unavailable()
        val earliest = previous.arrival + reposition * 1000 + bufferMillis
        if (earliest > merged.departure) {
            val conflicting = previous.latestMember
            return ScheduleConflictDetails(
                type = CONFLICT_TOO_SOON_AFTER_PREVIOUS,
                conflictingId = conflicting.id,
                conflictingKind = kindOf(conflicting),
                conflictingDepartureTime = conflicting.departure,
                conflictingArrivalTime = conflicting.arrival,
                fromPointId = previous.destinationPointId, fromPointName = pointName(previous.destinationPointId),
                toPointId = merged.originPointId, toPointName = pointName(merged.originPointId),
                repositioningSeconds = reposition,
                transferBufferSeconds = bufferMillis / 1000,
                earliestFeasibleDeparture = earliest,
                proposedDepartureTime = proposed.departure,
                proposedArrivalTime = proposed.arrival
            )
        }
    }
    // History is never "the next journey": a proposed journey that starts before a completed one began is not refused for
    // it. The next journey that still matters is the first one after it that has not happened.
    ordered.drop(position + 1).firstOrNull { !it.isHistory }?.let { next ->
        val reposition = travelTimes.repositioningSeconds(merged.destinationPointId!!, next.originPointId!!) ?: throw unavailable()
        val needed = merged.arrival + reposition * 1000 + bufferMillis
        if (needed > next.departure) {
            val conflicting = next.earliestMember
            return ScheduleConflictDetails(
                type = CONFLICT_TOO_LATE_FOR_NEXT,
                conflictingId = conflicting.id,
                conflictingKind = kindOf(conflicting),
                conflictingDepartureTime = conflicting.departure,
                conflictingArrivalTime = conflicting.arrival,
                fromPointId = merged.destinationPointId, fromPointName = pointName(merged.destinationPointId),
                toPointId = next.originPointId, toPointName = pointName(next.originPointId),
                repositioningSeconds = reposition,
                transferBufferSeconds = bufferMillis / 1000,
                latestFeasibleDeparture = next.departure - bufferMillis - reposition * 1000 - (proposed.arrival - proposed.departure),
                proposedDepartureTime = proposed.departure,
                proposedArrivalTime = proposed.arrival
            )
        }
    }
    return null
}

// One intent is one intended journey, so every request under it must be on the same canonical route. A client that
// reuses an intent id for a different route has made a mistake (a stale id, or an attempt to hide a second journey
// inside the first one's slot): it is refused, not merged. Pure: no database.
fun requireIntentRouteConsistent(existing: List<Journey>, proposed: Journey) {
    val intent = proposed.intentId ?: return
    val clash = existing.firstOrNull {
        it.intentId == intent && !it.unverified && !proposed.unverified &&
            (it.originPointId != proposed.originPointId || it.destinationPointId != proposed.destinationPointId)
    } ?: return
    throw ValidationException(
        "That journey intent is already used for a different route (${pointName(clash.originPointId)} to " +
            "${pointName(clash.destinationPointId)}). Start a new search for this journey."
    )
}

fun scheduleInfeasibleError(details: ScheduleConflictDetails) = ConflictException(
    "This doesn't fit your schedule",
    CODE_SCHEDULE_INFEASIBLE,
    conflict = details
)

// ---- building the timeline from stored rows -----------------------------------------------------------------

// The proposed journeys the routes build.
fun openRequestJourney(departure: Long, plannedDurationSeconds: Long, route: ApprovedRoute) = Journey(
    id = "(new request)", kind = JourneyKind.OPEN_REQUEST, state = JourneyState.PENDING,
    originPointId = route.origin.id, destinationPointId = route.destination.id,
    departure = departure, arrival = departure + plannedDurationSeconds * 1000
)

fun drivingJourney(departure: Long, plannedDurationSeconds: Long, route: ApprovedRoute) = Journey(
    id = "(new ride)", kind = JourneyKind.RIDE, state = JourneyState.DRIVING,
    originPointId = route.origin.id, destinationPointId = route.destination.id,
    departure = departure, arrival = departure + plannedDurationSeconds * 1000
)

// The journey a seat request on this posted ride would be: the ride's own planned interval.
fun Transaction.seatRequestJourney(ride: ResultRow, travelTimes: TravelTimeProvider, intentId: String? = null): Journey =
    rideAsJourney(ride, "(new request)", JourneyKind.SEAT_REQUEST, JourneyState.PENDING, travelTimes, intentId)

// A ride's interval: its stored planning fields; or, for an older ride, derived in memory from its coordinates
// and the matrix; or, if it cannot be placed, an unverified journey judged by time alone.
private fun rideAsJourney(
    ride: ResultRow, id: String, kind: JourneyKind, state: JourneyState, travelTimes: TravelTimeProvider, intentId: String? = null
): Journey {
    val departure = ride[Rides.departureTime]
    val originId = ride[Rides.originPointId]
    val destinationId = ride[Rides.destinationPointId]
    val plannedArrival = ride[Rides.plannedArrivalTime]
    if (originId != null && destinationId != null && plannedArrival != null) {
        return Journey(id, kind, state, originId, destinationId, departure, plannedArrival, intentId = intentId)
    }
    derivedInterval(ride[Rides.originLat], ride[Rides.originLng], ride[Rides.destinationLat], ride[Rides.destinationLng], departure, travelTimes)
        ?.let { (origin, destination, arrival) -> return Journey(id, kind, state, origin, destination, departure, arrival, intentId = intentId) }
    LegacyScheduling.noteLegacyEstimate()
    return Journey(id, kind, state, null, null, departure, departure + ride[Rides.estimatedDurationSeconds] * 1000, unverified = true, intentId = intentId)
}

private fun openRequestAsJourney(row: ResultRow, state: JourneyState, travelTimes: TravelTimeProvider): Journey {
    val id = row[RideRequests.requestId]
    val created = row[RideRequests.createdAt]
    // ASAP departs when it was created (server time), exactly as the duplicate rules read it.
    val departure = if (row[RideRequests.isAsap]) created else row[RideRequests.desiredDepartureTime] ?: created
    val originId = row[RideRequests.originPointId]
    val destinationId = row[RideRequests.destinationPointId]
    val plannedArrival = row[RideRequests.plannedArrivalTime]
    if (originId != null && destinationId != null && plannedArrival != null) {
        return Journey(id, JourneyKind.OPEN_REQUEST, state, originId, destinationId, departure, plannedArrival)
    }
    val lat = row[RideRequests.originLat]; val lng = row[RideRequests.originLng]
    val dLat = row[RideRequests.destinationLat]; val dLng = row[RideRequests.destinationLng]
    if (lat != null && lng != null && dLat != null && dLng != null) {
        derivedInterval(lat, lng, dLat, dLng, departure, travelTimes)
            ?.let { (origin, destination, arrival) -> return Journey(id, JourneyKind.OPEN_REQUEST, state, origin, destination, departure, arrival) }
        val legacy = estimatedTripWindow(departure, lat, lng, dLat, dLng) // counts as a legacy estimate
        return Journey(id, JourneyKind.OPEN_REQUEST, state, null, null, legacy.start, legacy.end, unverified = true)
    }
    return Journey(id, JourneyKind.OPEN_REQUEST, state, null, null, departure, departure, unverified = true)
}

private data class Derived(val originId: String, val destinationId: String, val arrival: Long)

// In-memory only: never written back.
private fun derivedInterval(
    originLat: Double, originLng: Double, destinationLat: Double, destinationLng: Double, departure: Long, travelTimes: TravelTimeProvider
): Derived? {
    val origin = resolveApprovedPoint(originLat, originLng) ?: return null
    val destination = resolveApprovedPoint(destinationLat, destinationLng) ?: return null
    if (origin.id == destination.id) return null
    val seconds = travelTimes.travelSeconds(origin.id, destination.id)?.takeIf { it > 0 } ?: return null
    return Derived(origin.id, destination.id, departure + seconds * 1000)
}

// The person's live journeys. Equivalent legacy duplicate rows are collapsed first, so an old bad row cannot
// distort the timeline. Must run inside a transaction.
fun Transaction.liveJourneysOf(personId: String, travelTimes: TravelTimeProvider): List<Journey> {
    val requestRows = RideRequests.selectAll()
        .where { (RideRequests.passengerId eq personId) and (RideRequests.status inList LIVE_REQUEST_STATUSES) }
        .toList()
    val hiddenDuplicates = hiddenDuplicateRequestIds(requestRows.filter { it[RideRequests.status] == RideRequestStatus.PENDING })
    val rideIds = requestRows.mapNotNull { it[RideRequests.rideId] }.toSet()
    val ridesById = if (rideIds.isEmpty()) emptyMap() else
        Rides.selectAll().where { Rides.rideId inList rideIds }.associateBy { it[Rides.rideId] }
    val joinable = setOf(RideStatus.OPEN, RideStatus.CONFIRMED)

    val journeys = mutableListOf<Journey>()
    for (row in requestRows) {
        val id = row[RideRequests.requestId]
        if (id in hiddenDuplicates) continue
        val state = if (row[RideRequests.status] == RideRequestStatus.ACCEPTED) JourneyState.ACCEPTED else JourneyState.PENDING
        val isOpen = row[RideRequests.originLat] != null
        val rideId = row[RideRequests.rideId]
        if (rideId != null) {
            // Attached to a posted ride: the journey is the RIDE's interval, whatever the passenger first asked for.
            val ride = ridesById[rideId] ?: continue
            if (ride[Rides.status] !in joinable) continue
            // Only a seat request carries a journey intent; an old row simply has none (a journey of its own).
            val intent = if (isOpen) null else row[RideRequests.journeyIntentId]
            journeys += rideAsJourney(ride, id, if (isOpen) JourneyKind.OPEN_REQUEST else JourneyKind.SEAT_REQUEST, state, travelTimes, intent)
        } else if (isOpen) {
            journeys += openRequestAsJourney(row, state, travelTimes)
        }
    }
    Rides.selectAll()
        .where { Rides.driverId eq personId }
        .filter { it[Rides.status] in joinable }
        .forEach { journeys += rideAsJourney(it, it[Rides.rideId], JourneyKind.RIDE, JourneyState.DRIVING, travelTimes) }
    // A request marked COMPLETED while its ride is still live (a row from before the ride owned completion) has not arrived
    // anywhere: the person is still on that ride, so it stays on their timeline as the accepted commitment it really is, and
    // marking it completed early cannot take them out of the schedule.
    for ((request, ride) in completedRequestsWithRides(personId)) {
        if (ride[Rides.status] !in joinable) continue
        journeys += rideAsJourney(ride, request[RideRequests.requestId], seatKind(request), JourneyState.ACCEPTED, travelTimes)
    }
    // Where the person physically is, and since when: their latest completed journey (HISTORY above).
    latestCompletedJourneyOf(personId, travelTimes)?.let { journeys += it }
    return journeys
}

// A COMPLETED ride as the person's journey: where it ended and when it effectively arrived. Null when it cannot be placed
// on the map. In memory only, and it does not count as a use of the legacy estimates (LegacyScheduling): nothing here is
// guessed - a ride that cannot be placed simply establishes nothing.
internal fun completedRideJourney(ride: ResultRow, id: String, kind: JourneyKind, travelTimes: TravelTimeProvider): Journey? {
    val departure = ride[Rides.departureTime]
    val originId = ride[Rides.originPointId]
    val destinationId = ride[Rides.destinationPointId]
    val plannedArrival = ride[Rides.plannedArrivalTime]
    val placed: Derived = if (originId != null && destinationId != null && plannedArrival != null) {
        Derived(originId, destinationId, plannedArrival)
    } else {
        derivedInterval(ride[Rides.originLat], ride[Rides.originLng], ride[Rides.destinationLat], ride[Rides.destinationLng], departure, travelTimes)
            ?: return null
    }
    // The observed completion time, when the driver pressed Complete; otherwise the best planned arrival there is.
    val arrival = ride[Rides.completedAt] ?: placed.arrival
    return Journey(id, kind, JourneyState.COMPLETED, placed.originId, placed.destinationId, departure, arrival)
}

// The person's most recent completed journey - the one that ended last, as a driver or as a passenger - or null if they
// have none that can be placed. Must run inside a transaction.
fun Transaction.latestCompletedJourneyOf(personId: String, travelTimes: TravelTimeProvider): Journey? {
    val candidates = mutableListOf<Journey>()
    Rides.selectAll().where { (Rides.driverId eq personId) and (Rides.status eq RideStatus.COMPLETED) }
        .forEach { completedRideJourney(it, it[Rides.rideId], JourneyKind.RIDE, travelTimes)?.let(candidates::add) }
    for ((request, ride) in completedRequestsWithRides(personId)) {
        // The RIDE owns journey completion: a passenger has only arrived when the ride they were on has. A COMPLETED
        // request on a ride that is still live (an inconsistent row) is not history, however it came to be.
        if (ride[Rides.status] != RideStatus.COMPLETED) continue
        completedRideJourney(ride, request[RideRequests.requestId], seatKind(request), travelTimes)?.let(candidates::add)
    }
    return candidates.maxWithOrNull(compareBy({ it.arrival }, { it.departure }, { it.id }))
}

private fun seatKind(request: ResultRow) = if (request[RideRequests.originLat] != null) JourneyKind.OPEN_REQUEST else JourneyKind.SEAT_REQUEST

// The person's COMPLETED requests that are attached to a ride, each with its ride (a request whose ride row is missing is
// dropped). Whether the ride itself is completed is the caller's decision.
private fun Transaction.completedRequestsWithRides(personId: String): List<Pair<ResultRow, ResultRow>> {
    val requests = RideRequests.selectAll()
        .where { (RideRequests.passengerId eq personId) and (RideRequests.status eq RideRequestStatus.COMPLETED) and RideRequests.rideId.isNotNull() }
        .toList()
    val rideIds = requests.mapNotNull { it[RideRequests.rideId] }.toSet()
    if (rideIds.isEmpty()) return emptyList()
    val ridesById = Rides.selectAll().where { Rides.rideId inList rideIds }.associateBy { it[Rides.rideId] }
    return requests.mapNotNull { request -> ridesById[request[RideRequests.rideId]]?.let { request to it } }
}

// The one entry point the routes use. Must run inside the person's scheduling transaction (Concurrency.kt), so the
// timeline read and the insert that follows are one atomic step.
fun Transaction.scheduleConflictFor(personId: String, proposed: Journey, travelTimes: TravelTimeProvider): ScheduleConflictDetails? {
    val journeys = liveJourneysOf(personId, travelTimes)
    requireIntentRouteConsistent(journeys, proposed)
    return findScheduleConflict(buildTimelineNodes(journeys), proposed, travelTimes)
}

// ---- the same model, used when a request is ACCEPTED ----------------------------------------------------------
//
// A person's journeys fall into two kinds. COMMITTED ones are real: ACCEPTED requests and rides they drive. PENDING
// ones are only wishes that may or may not be granted. Accepting something turns a wish into a commitment, so:
//
//  * The newly accepted journey must fit the person's COMMITTED journeys, by exactly the inequalities above. A commitment
//    the person already has always wins: if the new one does not fit around it, the accept is refused.
//  * It does not have to fit their PENDING journeys. Those are re-checked afterwards against the new timeline, in order
//    of departure (earliest first, ties by id, never by row order): each one that still fits is kept, each one that no
//    longer does is invalidated. A kept journey is part of the timeline the later ones are judged against, so
//    invalidating one early can let a later one survive. (Greedy and deterministic; it is maximal - nothing dropped
//    could be added back - but not guaranteed to drop the fewest possible in every contrived case.)

fun List<Journey>.committed(): List<Journey> = filter { it.state != JourneyState.PENDING }

class InvalidatedJourney(val journey: Journey, val conflict: ScheduleConflictDetails)

// Pure. Which PENDING journeys can no longer happen alongside everything committed, and why.
// With tolerateUnverifiable, a journey the matrix cannot judge (TRAVEL_TIME_UNAVAILABLE) is left PENDING instead of failing
// the whole call - for a system pass that must not abort what triggered it (a ride completing). It is never guessed at.
fun pendingJourneysToInvalidate(
    journeys: List<Journey>, travelTimes: TravelTimeProvider, tolerateUnverifiable: Boolean = false
): List<InvalidatedJourney> {
    val kept = journeys.committed().toMutableList()
    val invalidated = mutableListOf<InvalidatedJourney>()
    journeys.filter { it.state == JourneyState.PENDING }
        .sortedWith(compareBy({ it.departure }, { it.id }))
        .forEach { pending ->
            val conflict = try {
                findScheduleConflict(buildTimelineNodes(kept), pending, travelTimes)
            } catch (e: ServiceUnavailableException) {
                if (!tolerateUnverifiable) throw e
                null
            }
            if (conflict == null) kept += pending else invalidated += InvalidatedJourney(pending, conflict)
        }
    return invalidated
}

// Pure. The COMMITTED journeys (an accepted seat, a ride the person drives) that a completed journey's effective arrival
// leaves too little time to reach: arrival + reposition(where it ended -> the journey's pickup) + the transfer buffer is
// later than the journey's departure. Each is checked against the completed journey directly - that is a necessary
// condition however the journeys in between go - and only journeys that start after it began are considered. These are
// WARNINGS: the journeys themselves are never changed (a confirmed booking involves another person). Unplaceable journeys
// and pairs the matrix does not cover are not judged.
class JourneyAtRisk(val journey: Journey, val earliestFeasibleDeparture: Long, val repositioningSeconds: Long)

fun committedJourneysAtRisk(completed: Journey, journeys: List<Journey>, travelTimes: TravelTimeProvider): List<JourneyAtRisk> {
    val destination = completed.destinationPointId ?: return emptyList()
    return journeys
        .filter { (it.state == JourneyState.ACCEPTED || it.state == JourneyState.DRIVING) && !it.unverified && it.departure > completed.departure }
        .sortedWith(compareBy({ it.departure }, { it.id }))
        .mapNotNull { next ->
            val reposition = travelTimes.repositioningSeconds(destination, next.originPointId ?: return@mapNotNull null) ?: return@mapNotNull null
            val earliest = completed.arrival + reposition * 1000 + SchedulingConfig.TRANSFER_BUFFER_MILLIS
            if (earliest > next.departure) JourneyAtRisk(next, earliest, reposition) else null
        }
}

// Does this journey fit around what the person has COMMITTED to? (Pending journeys are ignored: see above.)
fun Transaction.committedConflictFor(personId: String, proposed: Journey, travelTimes: TravelTimeProvider): ScheduleConflictDetails? =
    findScheduleConflict(buildTimelineNodes(liveJourneysOf(personId, travelTimes).committed()), proposed, travelTimes)

// What an accepted request is on the passenger's timeline: the RIDE's own interval, whatever they first asked for.
fun Transaction.acceptedRideJourney(request: ResultRow, ride: ResultRow, travelTimes: TravelTimeProvider): Journey {
    val isOpen = request[RideRequests.originLat] != null
    return rideAsJourney(
        ride, request[RideRequests.requestId], if (isOpen) JourneyKind.OPEN_REQUEST else JourneyKind.SEAT_REQUEST,
        JourneyState.ACCEPTED, travelTimes, if (isOpen) null else request[RideRequests.journeyIntentId]
    )
}
