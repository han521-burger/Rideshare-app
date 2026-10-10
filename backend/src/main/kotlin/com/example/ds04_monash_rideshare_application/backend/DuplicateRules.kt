package com.example.ds04_monash_rideshare_application.backend

import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import kotlin.math.abs

// What counts as "the same request sent again", kept apart from whether a new request is
// physically possible (that is the schedule-feasibility rules, not these).
//
// Two live requests are the same journey when they are for the same passenger and
//   * open requests: same origin and destination (approved-point ids) and departures within
//     SchedulingConfig.DUPLICATE_TOLERANCE_MILLIS of each other;
//   * seat requests: the same posted ride. Requests for DIFFERENT posted rides are never
//     duplicates of each other, however close the rides are - they are alternatives.
// Live means PENDING or ACCEPTED. A request that is rejected, cancelled, expired or completed
// never makes a new one a duplicate.
//
// All of these must run inside a schedulingTransaction() for the passenger: the check and the
// insert are one atomic step, and that is what actually enforces the rule.

val LIVE_REQUEST_STATUSES = listOf(RideRequestStatus.PENDING, RideRequestStatus.ACCEPTED)

// An open (standalone-origin) request reduced to what duplicate detection compares.
data class OpenJourney(
    val requestId: String,
    val passengerId: String,
    val originKey: String,
    val destinationKey: String,
    val departure: Long,
    val createdAt: Long
)

// null for a seat request (it has no route of its own).
fun openJourneyOf(row: ResultRow): OpenJourney? {
    val originLat = row[RideRequests.originLat] ?: return null
    val originLng = row[RideRequests.originLng] ?: return null
    val destinationLat = row[RideRequests.destinationLat] ?: return null
    val destinationLng = row[RideRequests.destinationLng] ?: return null
    val created = row[RideRequests.createdAt]
    return OpenJourney(
        requestId = row[RideRequests.requestId],
        passengerId = row[RideRequests.passengerId],
        // Older rows have no stored point ids: resolve their coordinates now, so legacy duplicates
        // are recognised too.
        originKey = row[RideRequests.originPointId] ?: legacyPointRef(originLat, originLng).key,
        destinationKey = row[RideRequests.destinationPointId] ?: legacyPointRef(destinationLat, destinationLng).key,
        // ASAP means "leave when it was asked" (server time at creation).
        departure = if (row[RideRequests.isAsap]) created else row[RideRequests.desiredDepartureTime] ?: created,
        createdAt = created
    )
}

fun isSameJourney(a: OpenJourney, b: OpenJourney): Boolean =
    a.passengerId == b.passengerId &&
        a.originKey == b.originKey &&
        a.destinationKey == b.destinationKey &&
        abs(a.departure - b.departure) <= SchedulingConfig.DUPLICATE_TOLERANCE_MILLIS

// Stored in RideRequests.journeyKey: exact-duplicate backstop (see Database.kt). The minute bucket
// makes it coarser than the tolerance rule, never finer - it can only miss a duplicate (the
// transactional query below still catches it), never block a legitimate request.
fun openJourneyKey(origin: PointRef, destination: PointRef, departure: Long): String =
    "${origin.key}>${destination.key}@${departure / 60_000L}"

fun seatJourneyKey(rideId: String): String = "ride:$rideId"

// The passenger's live open request that stands for this same journey, if any (oldest first, so the
// answer is deterministic). Counts both requests still waiting for a driver and ones a driver has
// already accepted.
fun findLiveOpenDuplicate(
    passengerId: String,
    origin: PointRef,
    destination: PointRef,
    departure: Long,
    excludeRequestId: String? = null
): ResultRow? {
    val candidate = OpenJourney("", passengerId, origin.key, destination.key, departure, 0L)
    return RideRequests.selectAll()
        .where { (RideRequests.passengerId eq passengerId) and (RideRequests.status inList LIVE_REQUEST_STATUSES) }
        .filter { it[RideRequests.requestId] != excludeRequestId }
        .sortedWith(compareBy({ it[RideRequests.createdAt] }, { it[RideRequests.requestId] }))
        .firstOrNull { row -> openJourneyOf(row)?.let { isSameJourney(it, candidate) } == true }
}

// The passenger's live request for this exact posted ride, if any.
fun findLiveSeatDuplicate(passengerId: String, rideId: String, excludeRequestId: String? = null): ResultRow? =
    RideRequests.selectAll()
        .where {
            (RideRequests.rideId eq rideId) and (RideRequests.passengerId eq passengerId) and
                (RideRequests.status inList LIVE_REQUEST_STATUSES)
        }
        .filter { it[RideRequests.requestId] != excludeRequestId }
        .minWithOrNull(compareBy({ it[RideRequests.createdAt] }, { it[RideRequests.requestId] }))

fun duplicateRequestError(existingRequestId: String) = ConflictException(
    "You've already sent this request",
    CONFLICT_DUPLICATE_OPEN_REQUEST,
    existingRequestId
)

// Rows older versions of the app/backend could have left behind: equivalent live PENDING requests
// from the same passenger. Returns the ids to HIDE from actionable lists, always keeping the oldest
// of each group so what a driver sees is stable. The rows themselves are never touched.
fun hiddenDuplicateRequestIds(pendingRows: List<ResultRow>): Set<String> {
    val hidden = mutableSetOf<String>()
    pendingRows
        .sortedWith(compareBy({ it[RideRequests.createdAt] }, { it[RideRequests.requestId] }))
        .groupBy { it[RideRequests.passengerId] }
        .values
        .forEach { rows ->
            val keptOpen = mutableListOf<OpenJourney>()
            val keptRides = mutableSetOf<String>()
            for (row in rows) {
                val open = openJourneyOf(row)
                val rideId = row[RideRequests.rideId]
                val duplicate = when {
                    // Aimed at a specific posted ride (seat request): same ride = same request.
                    open == null && rideId != null -> !keptRides.add(rideId)
                    open != null -> keptOpen.any { isSameJourney(it, open) }.also { if (!it) keptOpen += open }
                    else -> false
                }
                if (duplicate) hidden += row[RideRequests.requestId]
            }
        }
    return hidden
}

// True when a write failed because a unique constraint rejected it (the exact-duplicate backstop or
// a reused clientRequestId) rather than for some other reason.
fun ExposedSQLException.isUniqueViolation(): Boolean =
    sqlState == "23505" || message.orEmpty().contains("UNIQUE constraint failed", ignoreCase = true) ||
        message.orEmpty().contains("duplicate key value", ignoreCase = true)
