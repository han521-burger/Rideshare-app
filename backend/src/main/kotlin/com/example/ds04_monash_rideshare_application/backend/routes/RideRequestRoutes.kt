package com.example.ds04_monash_rideshare_application.backend.routes

import com.example.ds04_monash_rideshare_application.backend.readTransaction
import com.example.ds04_monash_rideshare_application.backend.ATTACH_TIME_WINDOW_MILLIS
import com.example.ds04_monash_rideshare_application.backend.LIVE_REQUEST_STATUSES
import com.example.ds04_monash_rideshare_application.backend.PointRef
import com.example.ds04_monash_rideshare_application.backend.SUBMISSION_KIND_OPEN
import com.example.ds04_monash_rideshare_application.backend.SUBMISSION_KIND_SEAT
import com.example.ds04_monash_rideshare_application.backend.duplicateRequestError
import com.example.ds04_monash_rideshare_application.backend.findLiveOpenDuplicate
import com.example.ds04_monash_rideshare_application.backend.findLiveSeatDuplicate
import com.example.ds04_monash_rideshare_application.backend.findSubmission
import com.example.ds04_monash_rideshare_application.backend.fingerprintOf
import com.example.ds04_monash_rideshare_application.backend.hiddenDuplicateRequestIds
import com.example.ds04_monash_rideshare_application.backend.isUniqueViolation
import com.example.ds04_monash_rideshare_application.backend.openJourneyOf
import com.example.ds04_monash_rideshare_application.backend.openJourneyKey
import com.example.ds04_monash_rideshare_application.backend.toRef
import com.example.ds04_monash_rideshare_application.backend.recordSubmission
import com.example.ds04_monash_rideshare_application.backend.requireValidClientRequestId
import com.example.ds04_monash_rideshare_application.backend.requireValidJourneyIntentId
import com.example.ds04_monash_rideshare_application.backend.schedulingTransaction
import com.example.ds04_monash_rideshare_application.backend.schedulingTransactionFor
import com.example.ds04_monash_rideshare_application.backend.seatJourneyKey
import com.example.ds04_monash_rideshare_application.backend.CODE_RIDE_NOT_COMPLETED
import com.example.ds04_monash_rideshare_application.backend.MESSAGE_RIDE_NOT_COMPLETED
import com.example.ds04_monash_rideshare_application.backend.CONFLICT_OWN_PENDING_REQUEST
import com.example.ds04_monash_rideshare_application.backend.ConflictException
import com.example.ds04_monash_rideshare_application.backend.RideStops
import com.example.ds04_monash_rideshare_application.backend.cancelRequest
import com.example.ds04_monash_rideshare_application.backend.cancelRequests
import com.example.ds04_monash_rideshare_application.backend.estimatedTripWindow
import com.example.ds04_monash_rideshare_application.backend.LegacyScheduling
import com.example.ds04_monash_rideshare_application.backend.ScheduleConflictDetails
import com.example.ds04_monash_rideshare_application.backend.approvedPointById
import com.example.ds04_monash_rideshare_application.backend.Processed
import com.example.ds04_monash_rideshare_application.backend.Rejection
import com.example.ds04_monash_rideshare_application.backend.StoredSubmission
import com.example.ds04_monash_rideshare_application.backend.createdOccurrence
import com.example.ds04_monash_rideshare_application.backend.creationResponse
import com.example.ds04_monash_rideshare_application.backend.expandOccurrences
import com.example.ds04_monash_rideshare_application.backend.occurrencesOrThrowIfSingleRejected
import com.example.ds04_monash_rideshare_application.backend.rejectedOccurrence
import com.example.ds04_monash_rideshare_application.backend.snapshotOf
import com.example.ds04_monash_rideshare_application.backend.requireValidRecurrence
import com.example.ds04_monash_rideshare_application.backend.acceptRequest
import com.example.ds04_monash_rideshare_application.backend.acceptanceRefusal
import com.example.ds04_monash_rideshare_application.backend.openRequestJourney
import com.example.ds04_monash_rideshare_application.backend.resolveApprovedPoint
import com.example.ds04_monash_rideshare_application.backend.scheduleConflictFor
import com.example.ds04_monash_rideshare_application.backend.scheduleInfeasibleError
import com.example.ds04_monash_rideshare_application.backend.seatRequestJourney
import com.example.ds04_monash_rideshare_application.backend.formatTripTime
import com.example.ds04_monash_rideshare_application.backend.visibleTo
import com.example.ds04_monash_rideshare_application.backend.rideWindow
import com.example.ds04_monash_rideshare_application.backend.CreateRideRequestBody
import com.example.ds04_monash_rideshare_application.backend.CreateStandaloneRequestBody
import com.example.ds04_monash_rideshare_application.backend.DeclinedRequests
import com.example.ds04_monash_rideshare_application.backend.ForbiddenException
import com.example.ds04_monash_rideshare_application.backend.NotFoundException
import com.example.ds04_monash_rideshare_application.backend.RideRequestStatus
import com.example.ds04_monash_rideshare_application.backend.requireApprovedRoute
import com.example.ds04_monash_rideshare_application.backend.ApprovedRoute
import com.example.ds04_monash_rideshare_application.backend.CODE_TRAVEL_TIME_UNAVAILABLE
import com.example.ds04_monash_rideshare_application.backend.SchedulingConfig
import com.example.ds04_monash_rideshare_application.backend.ServiceUnavailableException
import com.example.ds04_monash_rideshare_application.backend.TravelTimeProvider
import com.example.ds04_monash_rideshare_application.backend.RideRequests
import com.example.ds04_monash_rideshare_application.backend.RideStatus
import com.example.ds04_monash_rideshare_application.backend.Rides
import com.example.ds04_monash_rideshare_application.backend.UpdateRideRequestStatusBody
import com.example.ds04_monash_rideshare_application.backend.Users
import com.example.ds04_monash_rideshare_application.backend.Vehicles
import com.example.ds04_monash_rideshare_application.backend.requireDriverVehicle
import com.example.ds04_monash_rideshare_application.backend.requireBookableDeparture
import com.example.ds04_monash_rideshare_application.backend.ValidationException
import com.example.ds04_monash_rideshare_application.backend.matching.MatchingEngine
import com.example.ds04_monash_rideshare_application.backend.matching.RideCandidate
import com.example.ds04_monash_rideshare_application.backend.matching.SearchCriteria
import com.example.ds04_monash_rideshare_application.backend.requireUserId
import com.example.ds04_monash_rideshare_application.backend.toRideRequestDto
import com.example.ds04_monash_rideshare_application.backend.toUserSummaryDto
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

fun Route.rideRequestRoutes(travelTimes: TravelTimeProvider) {
    // A passenger asking for a seat on a specific posted ride creates a PENDING request. Seats are deliberately
    // not reserved here: the owning driver makes the final decision and PUT /requests/{id} performs the atomic
    // capacity check/decrement at acceptance time. With applyToRecurringGroup the same is attempted for every other
    // UPCOMING ride in that series. Each ride is ONE OCCURRENCE: judged on its own (own ride? still open? already
    // requested? fits the schedule? room?) in order of departure, and reported on its own - none is silently skipped.
    // A single ride that cannot be requested is the ordinary error; a series is always answered with every outcome.
    post("/rides/{rideId}/requests") {
        val passengerId = call.requireUserId()
        val rideId = call.parameters["rideId"] ?: throw NotFoundException()
        val body = call.receive<CreateRideRequestBody>()
        if (body.seatsRequested <= 0) throw ValidationException("seatsRequested must be positive")
        val clientRequestId = requireValidClientRequestId(body.clientRequestId)
        // Which intended journey this is an option for (see RideRequests.journeyIntentId). Part of what a retry must
        // repeat: the same clientRequestId with a different intent is a different request, and is refused as such.
        val journeyIntentId = requireValidJourneyIntentId(body.journeyIntentId)
        val fingerprint = fingerprintOf("SEAT|$rideId|${body.seatsRequested}|${body.note}|${body.applyToRecurringGroup}|${journeyIntentId ?: ""}")

        val joinableStatuses = setOf(RideStatus.OPEN, RideStatus.CONFIRMED)
        // Everything - the idempotency lookup, every occurrence's checks and inserts, the record of the result - is ONE step
        // under the passenger's scheduling lock (Concurrency.kt), so nothing can interleave halfway through a series.
        val stored = runUniqueGuarded(passengerId, clientRequestId, SUBMISSION_KIND_SEAT, fingerprint, seatJourneyKey(rideId)) {
            schedulingTransactionFor(
                usersOf = { _: List<String> -> listOf(passengerId) },
                ridesOf = { rideIds -> rideIds },
                discover = { seatOccurrenceRides(rideId, body.applyToRecurringGroup).map { it[Rides.rideId] } }
            ) {
                clientRequestId?.let { findSubmission(passengerId, it, SUBMISSION_KIND_SEAT, fingerprint) }
                    ?.let { return@schedulingTransactionFor it }

                val now = System.currentTimeMillis()
                val processed = seatOccurrenceRides(rideId, body.applyToRecurringGroup).map { target ->
                    val targetRideId = target[Rides.rideId]
                    val departure = target[Rides.departureTime]
                    fun rejected(rejection: Rejection) = Processed(rejectedOccurrence(departure, rejection, targetRideId), rejection)
                    when {
                        target[Rides.driverId] == passengerId -> rejected(Rejection.ownRide())
                        target[Rides.status] !in joinableStatuses -> rejected(Rejection.unavailable())
                        else -> {
                            // The same journey again (same posted ride) is not a new request.
                            val alreadyRequested = findLiveSeatDuplicate(passengerId, targetRideId)
                            // Can the passenger physically make this ride, given everything else on their timeline - including
                            // the earlier occurrences of THIS series that were just made? (The ride's own PLANNED interval, never
                            // MapKit's ETA.) The intent belongs to the ride the passenger actually picked; the other dates of a
                            // series are different journeys and carry none.
                            val intent = journeyIntentId.takeIf { targetRideId == rideId }
                            val conflict = if (alreadyRequested != null) null else try {
                                scheduleConflictFor(passengerId, seatRequestJourney(target, travelTimes, intent), travelTimes)
                            } catch (e: ServiceUnavailableException) {
                                return@map rejected(Rejection.cannotVerify())
                            }
                            // This is an early friendly check only. Other pending requests do not reserve a seat, so the
                            // authoritative capacity check still happens when the driver accepts.
                            val freshRide = Rides.selectAll().where { Rides.rideId eq targetRideId }.first()
                            when {
                                alreadyRequested != null -> rejected(Rejection.duplicate(alreadyRequested[RideRequests.requestId]))
                                conflict != null -> rejected(Rejection.scheduleConflict(conflict))
                                freshRide[Rides.seatsAvailable] < body.seatsRequested -> rejected(Rejection.full())
                                else -> {
                                    val requestId = "req-${UUID.randomUUID()}"
                                    RideRequests.insert {
                                        it[RideRequests.requestId] = requestId
                                        it[RideRequests.rideId] = targetRideId
                                        it[RideRequests.passengerId] = passengerId
                                        it[seatsRequested] = body.seatsRequested
                                        it[status] = RideRequestStatus.PENDING
                                        it[note] = body.note
                                        it[createdAt] = now
                                        it[journeyKey] = seatJourneyKey(targetRideId)
                                        it[RideRequests.journeyIntentId] = intent
                                    }
                                    Processed(createdOccurrence(departure, requestId, targetRideId))
                                }
                            }
                        }
                    }
                }
                // The answer to give now, and - recorded under the clientRequestId - for every retry of this submission.
                val snapshot = snapshotOf(processed.occurrencesOrThrowIfSingleRejected())
                clientRequestId?.let { recordSubmission(passengerId, it, SUBMISSION_KIND_SEAT, fingerprint, snapshot, now) }
                StoredSubmission(snapshot.occurrences.mapNotNull { it.requestId }, snapshot.occurrences, snapshot)
            }
        }
        // One shape for one request or many (a response whose static type varied at runtime would not be serializable).
        call.respond(readTransaction { creationResponse(stored) })
    }

    // Passenger posts a standalone request - a route + time with no driver picked yet. Sits as PENDING with
    // rideId == null until some driver accepts it (see PUT /requests/{id} below), attaching one of their own rides at
    // that point. With repeatDaysOfWeek, the request is repeated on those weekdays for the next repeatWeeks weeks (the
    // expansion rules are in RecurrenceExpansion.kt). Each date is ONE OCCURRENCE with its own request and status, so a
    // driver can accept some dates and leave others, and each is judged on its own, in order of departure: already
    // requested? does it fit the passenger's timeline, including the earlier dates just made? Every date is reported -
    // none is silently skipped. ASAP has no schedule, so repeat days never apply when isAsap is true.
    post("/requests") {
        val passengerId = call.requireUserId()
        val body = call.receive<CreateStandaloneRequestBody>()
        if (body.seatsRequested <= 0) throw ValidationException("seatsRequested must be positive")
        if (!body.isAsap && body.desiredDepartureTime == null) {
            throw ValidationException("desiredDepartureTime is required unless isAsap is true")
        }
        if (!body.isAsap) body.desiredDepartureTime?.let(::requireBookableDeparture)
        // Both ends must be approved points, one of them a campus, and not the same place (RoutePolicy.kt).
        val route = requireApprovedRoute(body.originLat, body.originLng, body.destinationLat, body.destinationLng)
        // An invalid recurrence is refused here, before a single occurrence is processed.
        val recurrence = if (body.isAsap) null else requireValidRecurrence(body.repeatDaysOfWeek, body.repeatWeeks)
        val clientRequestId = requireValidClientRequestId(body.clientRequestId)
        val fingerprint = fingerprintOf("OPEN|" + Json.encodeToString(CreateStandaloneRequestBody.serializer(), body.copy(clientRequestId = null)))

        // Endpoints as approved-point identities, so "the same journey" is never decided by
        // floating-point equality.
        val origin = route.origin.toRef()
        val destination = route.destination.toRef()

        // Everything below - the idempotency lookup, every occurrence's duplicate and schedule checks and inserts, and
        // the record of the result - is ONE step under the passenger's scheduling lock (Concurrency.kt).
        val stored = runUniqueGuarded(
            passengerId, clientRequestId, SUBMISSION_KIND_OPEN, fingerprint, "${origin.key}>${destination.key}@"
        ) {
            schedulingTransaction(listOf(passengerId)) {
                // A retry of a call that was already processed gets that call's answer back, every date exactly as before.
                clientRequestId?.let { findSubmission(passengerId, it, SUBMISSION_KIND_OPEN, fingerprint) }
                    ?.let { return@schedulingTransaction it }

                // Server time is the only clock that matters: ASAP means "now, on the server".
                val now = System.currentTimeMillis()
                // How long this trip itself takes, from the directional travel-time matrix - decided once, and stored with
                // every request. Never a straight-line guess and never zero: with no matrix entry for this pair nothing can
                // be planned, so the whole call is refused (recoverably) rather than planned on made-up timing.
                val plannedSeconds = travelTimes.travelSeconds(route.origin.id, route.destination.id)
                val departures = if (body.isAsap) listOf(now) else expandOccurrences(body.desiredDepartureTime!!, recurrence)

                val processed = departures.map { departure ->
                    fun rejected(rejection: Rejection) = Processed(rejectedOccurrence(departure, rejection), rejection)
                    // The very same journey again (±1 minute): never a second row, and the answer names the request that
                    // already exists.
                    val existing = findLiveOpenDuplicate(passengerId, origin, destination, departure)
                    if (existing != null) return@map rejected(Rejection.duplicate(existing[RideRequests.requestId]))
                    val planned = plannedSeconds ?: throw ServiceUnavailableException(
                        "We couldn't work out the travel time for this trip right now, so it can't be requested yet. Please try again later.",
                        CODE_TRAVEL_TIME_UNAVAILABLE
                    )
                    // Does it fit the passenger's timeline - room after the journey before it AND before the journey after it
                    // (ScheduleFeasibility.kt)? A different outcome from a duplicate, with its own code.
                    val conflict = try {
                        scheduleConflictFor(passengerId, openRequestJourney(departure, planned, route), travelTimes)
                    } catch (e: ServiceUnavailableException) {
                        return@map rejected(Rejection.cannotVerify())
                    }
                    if (conflict != null) return@map rejected(Rejection.scheduleConflict(conflict))
                    val requestId = "req-${UUID.randomUUID()}"
                    // ASAP means "now, on the server": a departure time sent with an ASAP request (a phone's clock) is ignored.
                    insertStandaloneRequest(requestId, passengerId, body, route, if (body.isAsap) null else departure, now, planned)
                    Processed(createdOccurrence(departure, requestId))
                }
                // The answer to give now, and - recorded under the clientRequestId - for every retry of this submission.
                val snapshot = snapshotOf(processed.occurrencesOrThrowIfSingleRejected())
                clientRequestId?.let { recordSubmission(passengerId, it, SUBMISSION_KIND_OPEN, fingerprint, snapshot, now) }
                StoredSubmission(snapshot.occurrences.mapNotNull { it.requestId }, snapshot.occurrences, snapshot)
            }
        }
        call.respond(readTransaction { creationResponse(stored) })
    }

    // Driver views incoming requests: ones already aimed at their own rides (any status, so they
    // can review history too), plus every campus-wide standalone request that's still PENDING -
    // each scored (MatchingEngine) against the driver's own OPEN rides so they can tell at a
    // glance whether it's worth a look. ?rideId= narrows to just one of the driver's rides and
    // drops the standalone list (it's only meaningful campus-wide).
    get("/requests") {
        val driverId = call.requireUserId()
        val rideIdFilter = call.request.queryParameters["rideId"]
        val requests = readTransaction {
            val ownRideRows = Rides.selectAll().where { Rides.driverId eq driverId }.toList()
            val ownRideDtos = enrichRideDtos(ownRideRows).associateBy { it.rideId }
            val ownOpenCandidates = ownRideRows
                .filter { it[Rides.status] == RideStatus.OPEN }
                .map { row ->
                    RideCandidate(
                        rideId = row[Rides.rideId],
                        originLat = row[Rides.originLat],
                        originLng = row[Rides.originLng],
                        destinationLat = row[Rides.destinationLat],
                        destinationLng = row[Rides.destinationLng],
                        departureTime = row[Rides.departureTime],
                        seatsAvailable = row[Rides.seatsAvailable]
                    )
                }

            // The same passenger's equivalent waiting asks (left behind by older versions, before duplicates
            // were refused) are collapsed to the oldest, so a driver never sees - or can accept - one
            // journey twice. The rows are kept for history/debugging; they just stop being actionable.
            // Only PENDING rows are ever hidden, so history views are unaffected.
            val hiddenDuplicates = hiddenDuplicateRequestIds(
                RideRequests.selectAll().where { RideRequests.status eq RideRequestStatus.PENDING }.toList()
            )
            val attachedRows = RideRequests.selectAll().toList().filter { row ->
                val rid = row[RideRequests.rideId]
                rid != null && ownRideDtos.containsKey(rid) && (rideIdFilter == null || rid == rideIdFilter) &&
                    row[RideRequests.requestId] !in hiddenDuplicates
            }
            val standaloneRows = if (rideIdFilter != null) emptyList() else {
                val declinedByMe = DeclinedRequests.selectAll().where { DeclinedRequests.driverId eq driverId }
                    .map { it[DeclinedRequests.requestId] }.toSet()
                RideRequests.selectAll()
                    .where { RideRequests.status eq RideRequestStatus.PENDING }
                    // Your own standalone ask isn't something for you to accept as a driver - it
                    // already shows under "My requests" on your own passenger side. Without this,
                    // a request you post to yourself (any account can be both) shows up twice.
                    .filter { it[RideRequests.rideId] == null && it[RideRequests.passengerId] != driverId }
                    // Asks this driver said "not for me" to (PUT ... REJECTED on an open ask).
                    .filter { it[RideRequests.requestId] !in declinedByMe }
                    .filter { it[RideRequests.requestId] !in hiddenDuplicates }
            }

            val allRows = attachedRows + standaloneRows
            val passengerIds = allRows.map { it[RideRequests.passengerId] }.toSet()
            val passengersById = if (passengerIds.isEmpty()) emptyMap() else {
                Users.selectAll().where { Users.userId inList passengerIds }
                    .associate { it[Users.userId] to it.toUserSummaryDto() }
            }

            allRows.map { row ->
                val rid = row[RideRequests.rideId]
                val dto = row.toRideRequestDto().visibleTo(driverId).copy(
                    ride = rid?.let { ownRideDtos[it] },
                    passenger = passengersById[row[RideRequests.passengerId]]
                )
                val requestOriginLat = row[RideRequests.originLat]
                val requestOriginLng = row[RideRequests.originLng]
                val requestDestLat = row[RideRequests.destinationLat]
                val requestDestLng = row[RideRequests.destinationLng]
                if (rid == null && requestOriginLat != null && requestOriginLng != null &&
                    requestDestLat != null && requestDestLng != null
                ) {
                    val criteria = SearchCriteria(
                        originLat = requestOriginLat,
                        originLng = requestOriginLng,
                        destinationLat = requestDestLat,
                        destinationLng = requestDestLng,
                        desiredDepartureTime = row[RideRequests.desiredDepartureTime] ?: System.currentTimeMillis(),
                        seatsNeeded = row[RideRequests.seatsRequested]
                    )
                    val best = MatchingEngine.rank(criteria, ownOpenCandidates).firstOrNull()
                    dto.copy(matchScore = best?.score)
                } else {
                    dto
                }
            }
        }
        call.respond(requests)
    }

    // Passenger views their own requests, standalone or attached.
    get("/requests/mine") {
        val passengerId = call.requireUserId()
        val requests = readTransaction {
            val rows = RideRequests.selectAll().where { RideRequests.passengerId eq passengerId }.toList()
            val rideIds = rows.mapNotNull { it[RideRequests.rideId] }.toSet()
            val ridesById = if (rideIds.isEmpty()) emptyMap() else {
                enrichRideDtos(Rides.selectAll().where { Rides.rideId inList rideIds }.toList()).associateBy { it.rideId }
            }
            rows.map { row ->
                row.toRideRequestDto().copy(ride = row[RideRequests.rideId]?.let { ridesById[it] })
            }
        }
        call.respond(requests)
    }

    // Drivers accept/reject requests attached to their own rides. A standalone request can be
    // accepted by attaching one of the driver's rides, or by creating a new ride from the
    // request's route/time. Passengers may cancel their own pending or accepted requests.
    put("/requests/{requestId}") {
        val userId = call.requireUserId()
        val requestId = call.parameters["requestId"] ?: throw NotFoundException()
        val body = call.receive<UpdateRideRequestStatusBody>()
        val newStatus = body.status.uppercase()

        // Locks both people whose schedules this can change - the passenger, and the acting user
        // (accepting also cancels the driver's own overlapping asks) - then the ride(s) whose seat count
        // it can change, in that order (Concurrency.kt). The decision itself re-reads everything under
        // those locks.
        schedulingTransactionFor(
            usersOf = { scope: RequestLockScope -> listOf(scope.passengerId, userId) },
            ridesOf = { scope -> listOf(scope.rideId, body.targetRideId) },
            discover = { requestLockScope(requestId) }
        ) {
            val request = RideRequests.selectAll().where { RideRequests.requestId eq requestId }.firstOrNull()
                ?: throw NotFoundException("Request not found")

            val existingRideId = request[RideRequests.rideId]
            val existingRide = existingRideId?.let { rid -> Rides.selectAll().where { Rides.rideId eq rid }.firstOrNull() }
            val isPassenger = request[RideRequests.passengerId] == userId
            val isOwningDriver = existingRide != null && existingRide[Rides.driverId] == userId

            val currentStatus = request[RideRequests.status]
            if (currentStatus == RideRequestStatus.EXPIRED) {
                throw ConflictException("This request has expired - its time has already passed")
            }
            // A driver pressing Accept on something that is no longer pending (another driver got there first, it was
            // cancelled, it conflicted...) gets a plain machine-readable answer that reveals nothing about anyone else.
            if (newStatus == RideRequestStatus.ACCEPTED && !isPassenger) acceptanceRefusal(currentStatus, isOwningDriver)?.let { throw it }
            if (currentStatus == RideRequestStatus.CONFLICTED) {
                // Said to the passenger only (a driver was answered above): it is their own other trip that it clashes with.
                throw ConflictException("This request can no longer happen - it clashes with another trip. Make a new request instead.")
            }
            val allowed = RideRequestStatus.transitions[currentStatus].orEmpty()
            if (newStatus !in allowed) {
                throw ConflictException("Can't move a request from $currentStatus to $newStatus")
            }

            when (newStatus) {
                // The whole decision - route, the passenger's and driver's schedules, alternatives, conflicts - is
                // Acceptance.kt, run here under the locks taken above.
                RideRequestStatus.ACCEPTED -> acceptRequest(request, userId, body, travelTimes)
                RideRequestStatus.REJECTED -> {
                    // An open ask has no driver yet, so one driver declining can't reject it for
                    // everyone - it's just hidden from that driver's inbox and stays PENDING.
                    if (existingRideId == null) {
                        if (isPassenger) throw ForbiddenException("Cancel your own request instead")
                        val alreadyDeclined = DeclinedRequests.selectAll().where {
                            (DeclinedRequests.driverId eq userId) and (DeclinedRequests.requestId eq requestId)
                        }.count() > 0
                        if (!alreadyDeclined) {
                            DeclinedRequests.insert {
                                it[driverId] = userId
                                it[DeclinedRequests.requestId] = requestId
                                it[createdAt] = System.currentTimeMillis()
                            }
                        }
                        return@schedulingTransactionFor
                    }
                    if (!isOwningDriver) throw ForbiddenException("Only the ride's driver can reject a request")
                    val rejectedAt = System.currentTimeMillis()
                    RideRequests.update({ RideRequests.requestId eq requestId }) {
                        it[status] = newStatus
                        it[respondedAt] = rejectedAt
                        if (request[RideRequests.driverDecisionAt] == null) it[driverDecisionAt] = rejectedAt
                    }
                }
                RideRequestStatus.CANCELLED -> {
                    if (!isPassenger && !isOwningDriver) throw ForbiddenException("Not your request")
                    // Returns an accepted seat and reopens an emptied ride (Lifecycle.kt).
                    cancelRequest(request, System.currentTimeMillis())
                }
                RideRequestStatus.COMPLETED -> {
                    if (!isPassenger && !isOwningDriver) throw ForbiddenException("Not your request")
                    // THE RIDE OWNS JOURNEY COMPLETION. A seat finishes when its ride does (PUT /rides/{id} COMPLETED, or the
                    // system's own auto-completion), never on its own: completing one seat while the ride is still travelling
                    // would put the passenger somewhere they are not and take them out of the schedule. So a request attached
                    // to a ride that is not COMPLETED is refused. If the ride IS completed and the request somehow is not
                    // (a row from before this rule), the request is simply reconciled to COMPLETED, with no timestamp of its
                    // own - its history comes from the ride (completedAt, else the planned arrival).
                    val ride = existingRide
                    if (ride == null || ride[Rides.status] != RideStatus.COMPLETED) {
                        throw ConflictException(MESSAGE_RIDE_NOT_COMPLETED, CODE_RIDE_NOT_COMPLETED)
                    }
                    RideRequests.update({ (RideRequests.requestId eq requestId) and (RideRequests.status eq RideRequestStatus.ACCEPTED) }) {
                        it[status] = newStatus
                    }
                }
                else -> throw ValidationException("Unsupported status transition")
            }
        }

        val dto = readTransaction {
            RideRequests.selectAll().where { RideRequests.requestId eq requestId }.map { it.toRideRequestDto() }.first()
        }
        call.respond(dto.visibleTo(userId))
    }

    // Undo a just-made accept (the app offers Undo for a few seconds after accepting). Puts the
    // request back to PENDING, gives the seat back, removes a ride that was created only for
    // this accept, and restores the asks that were CANCELLED because this accept won (same timestamp, see
    // Acceptance.kt): the passenger's other options for the same journey intent, and the driver's own requests they
    // agreed to cancel. It never restores a CONFLICTED request - that is terminal, and the system, not the
    // driver, ended it - nor one that was cancelled/rejected/expired for any other reason.
    post("/requests/{requestId}/undo-accept") {
        val userId = call.requireUserId()
        val requestId = call.parameters["requestId"] ?: throw NotFoundException()
        schedulingTransactionFor(
            usersOf = { scope: RequestLockScope -> listOf(scope.passengerId, userId) },
            ridesOf = { scope -> listOf(scope.rideId) },
            discover = { requestLockScope(requestId) }
        ) {
            val request = RideRequests.selectAll().where { RideRequests.requestId eq requestId }.firstOrNull()
                ?: throw NotFoundException("Request not found")
            val rideId = request[RideRequests.rideId]
            val ride = rideId?.let { rid -> Rides.selectAll().where { Rides.rideId eq rid }.firstOrNull() }
            if (rideId == null || ride == null || ride[Rides.driverId] != userId) {
                throw ForbiddenException("Only the driver who accepted can undo it")
            }
            if (request[RideRequests.status] != RideRequestStatus.ACCEPTED) {
                throw ConflictException("This request is no longer accepted")
            }
            val acceptedAt = request[RideRequests.respondedAt] ?: throw ConflictException("Too late to undo")
            if (System.currentTimeMillis() - acceptedAt > UNDO_ACCEPT_WINDOW_MILLIS) {
                throw ConflictException("Too late to undo - cancel the trip instead")
            }
            val isStandalone = request[RideRequests.originLat] != null
            RideRequests.update({ RideRequests.requestId eq requestId }) {
                it[status] = RideRequestStatus.PENDING
                it[respondedAt] = null
                if (isStandalone) it[RideRequests.rideId] = null
            }
            val othersOnRide = RideRequests.selectAll().where { RideRequests.rideId eq rideId }
                .filter { it[RideRequests.requestId] != requestId }
            if (isStandalone && ride[Rides.createdAt] == acceptedAt && othersOnRide.isEmpty()) {
                RideStops.deleteWhere { RideStops.rideId eq rideId }
                Rides.deleteWhere { Rides.rideId eq rideId }
            } else {
                val stillAccepted = othersOnRide.any { it[RideRequests.status] == RideRequestStatus.ACCEPTED }
                Rides.update({ Rides.rideId eq rideId }) {
                    it[seatsAvailable] = ride[Rides.seatsAvailable] + request[RideRequests.seatsRequested]
                    if (!stillAccepted && ride[Rides.status] == RideStatus.CONFIRMED) it[status] = RideStatus.OPEN
                }
            }
            // Put back the asks this accept cancelled to make room. One the passenger has since asked for
            // again is left cancelled - restoring it would create the duplicate we refuse everywhere else.
            RideRequests.selectAll().where {
                (RideRequests.status eq RideRequestStatus.CANCELLED) and
                    (RideRequests.respondedAt eq acceptedAt) and
                    (RideRequests.passengerId inList listOf(userId, request[RideRequests.passengerId]))
            }.toList().filter { cancelled ->
                // Only what THIS accept cancelled: an alternative of the accepted request's journey intent, or one of
                // the accepting driver's own. (A coincidental cancellation at the same millisecond is not restored.)
                val intent = request[RideRequests.journeyIntentId]
                cancelled[RideRequests.passengerId] == userId ||
                    (intent != null && cancelled[RideRequests.journeyIntentId] == intent)
            }.forEach { cancelled ->
                val cancelledId = cancelled[RideRequests.requestId]
                val open = openJourneyOf(cancelled)
                val collides = if (open != null) {
                    findLiveOpenDuplicate(
                        open.passengerId, PointRef(open.originKey, null), PointRef(open.destinationKey, null),
                        open.departure, excludeRequestId = cancelledId
                    ) != null
                } else {
                    cancelled[RideRequests.rideId]?.let {
                        findLiveSeatDuplicate(cancelled[RideRequests.passengerId], it, excludeRequestId = cancelledId)
                    } != null
                }
                if (!collides) {
                    RideRequests.update({ RideRequests.requestId eq cancelledId }) {
                        it[status] = RideRequestStatus.PENDING
                        it[respondedAt] = null
                    }
                }
            }
        }
        val dto = readTransaction {
            RideRequests.selectAll().where { RideRequests.requestId eq requestId }.map { it.toRideRequestDto() }.first()
        }
        call.respond(dto.visibleTo(userId))
    }
}

// Who and what a change to one request can touch: its passenger and the ride it is attached to.
private class RequestLockScope(val passengerId: String, val rideId: String?)

private fun Transaction.requestLockScope(requestId: String): RequestLockScope {
    val request = RideRequests.selectAll().where { RideRequests.requestId eq requestId }.firstOrNull()
        ?: throw NotFoundException("Request not found")
    return RequestLockScope(request[RideRequests.passengerId], request[RideRequests.rideId])
}

// The posted rides a seat request is for, in the order they are processed (departure, then id): just the one, or - when the
// passenger asked for the whole series - the ride they picked plus every other UPCOMING ride in its recurring group,
// whatever its status (one that is cancelled or full is reported, not hidden). These are also the rides to lock. Past
// departures are not part of the request.
private fun Transaction.seatOccurrenceRides(rideId: String, applyToRecurringGroup: Boolean): List<ResultRow> {
    val ride = Rides.selectAll().where { Rides.rideId eq rideId }.firstOrNull()
        ?: throw NotFoundException("Ride not found")
    val groupId = ride[Rides.recurringGroupId]
    if (!applyToRecurringGroup || groupId == null) return listOf(ride)
    val now = System.currentTimeMillis()
    val siblings = Rides.selectAll()
        .where { (Rides.recurringGroupId eq groupId) and (Rides.departureTime greater now) and (Rides.rideId neq rideId) }
        .toList()
    return (siblings + ride).sortedWith(compareBy({ it[Rides.departureTime] }, { it[Rides.rideId] }))
}

// Server-side limit for undo - comfortably longer than the app's few-second Undo snackbar.
private const val UNDO_ACCEPT_WINDOW_MILLIS = 60_000L

// Must run inside a transaction. origin/destination are the approved-point identities of the
// endpoints (see ApprovedPoints.kt); their ids and the journey key are stored with the request.
private fun insertStandaloneRequest(
    requestId: String,
    passengerId: String,
    body: CreateStandaloneRequestBody,
    route: ApprovedRoute,
    departureTime: Long?,
    createdAt: Long,
    plannedDurationSeconds: Long
) {
    val origin = route.origin.toRef()
    val destination = route.destination.toRef()
    // ASAP departs when it was created (server time); otherwise the requested departure.
    val effectiveDeparture = departureTime ?: createdAt
    RideRequests.insert {
        it[RideRequests.requestId] = requestId
        it[rideId] = null
        it[RideRequests.passengerId] = passengerId
        it[seatsRequested] = body.seatsRequested
        it[status] = RideRequestStatus.PENDING
        it[note] = body.note
        // The approved points' own coordinates, so what is stored is exactly the place that was approved.
        it[originLat] = route.origin.lat
        it[originLng] = route.origin.lng
        it[destinationLat] = route.destination.lat
        it[destinationLng] = route.destination.lng
        it[isAsap] = body.isAsap
        it[desiredDepartureTime] = departureTime
        it[RideRequests.createdAt] = createdAt
        it[originPointId] = origin.pointId
        it[destinationPointId] = destination.pointId
        // Exactly as openJourneyOf() reads the departure back.
        it[journeyKey] = openJourneyKey(origin, destination, effectiveDeparture)
        it[RideRequests.plannedDurationSeconds] = plannedDurationSeconds
        it[RideRequests.plannedArrivalTime] = effectiveDeparture + plannedDurationSeconds * 1000
    }
}

// Runs a create step. If a unique constraint (the exact-duplicate backstop, or a clientRequestId
// that raced) rejects an insert that got past the transactional checks, answer the way the checks
// would have: the original submission's result, or the duplicate error naming the live request.
// With the scheduling lock this should not happen; it is the safety net, not the mechanism.
private fun runUniqueGuarded(
    passengerId: String,
    clientRequestId: String?,
    kind: String,
    fingerprint: String,
    journeyKeyPrefix: String,
    block: () -> StoredSubmission
): StoredSubmission = try {
    block()
} catch (e: ExposedSQLException) {
    if (!e.isUniqueViolation()) throw e
    readTransaction {
        clientRequestId?.let { findSubmission(passengerId, it, kind, fingerprint) }?.let { return@readTransaction it }
        val existing = RideRequests.selectAll()
            .where { (RideRequests.passengerId eq passengerId) and (RideRequests.status inList LIVE_REQUEST_STATUSES) }
            .filter { it[RideRequests.journeyKey]?.startsWith(journeyKeyPrefix) == true }
            .maxByOrNull { it[RideRequests.createdAt] }
        if (existing != null) throw duplicateRequestError(existing[RideRequests.requestId])
        throw e
    }
}
