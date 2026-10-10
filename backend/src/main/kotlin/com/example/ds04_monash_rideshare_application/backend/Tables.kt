package com.example.ds04_monash_rideshare_application.backend

import org.jetbrains.exposed.sql.Table

// Central DB schema (RTM FR-08): users, vehicles, rides, ride_requests, messages.
// Room on the Android side is a cache of this data, not a second source of truth -
// this schema is what's authoritative.

object Users : Table("users") {
    val userId = varchar("user_id", 64)
    val externalSubjectId = varchar("external_subject_id", 128).uniqueIndex()
    val emailAccount = varchar("email_account", 256).uniqueIndex()
    val displayName = varchar("display_name", 128)
    val preferredName = varchar("preferred_name", 128).default("")
    val phoneNumber = varchar("phone_number", 32).nullable()
    val photoRef = varchar("photo_ref", 512).nullable()
    val accountStatus = varchar("account_status", 32).default("ACTIVE")
    val passengerEnabled = bool("passenger_enabled").default(true)
    val emergencyContactName = varchar("emergency_contact_name", 128).nullable()
    val emergencyContactPhone = varchar("emergency_contact_phone", 32).nullable()
    val createdAt = long("created_at")
    // PBKDF2 hash (Passwords.kt) - never the password itself. Null only for an account that
    // can't sign in with a password (none today; kept nullable so older DBs migrate in place).
    val passwordHash = varchar("password_hash", 256).nullable()

    override val primaryKey = PrimaryKey(userId)
}

// One row per signed-in device. The app sends the token as "Authorization: Bearer <token>"
// and requireUserId() (Auth.kt) resolves it back to the user. Deleted on sign-out.
object Sessions : Table("sessions") {
    val token = varchar("token", 128)
    val userId = varchar("user_id", 64).references(Users.userId)
    val createdAt = long("created_at")
    val expiresAt = long("expires_at")

    override val primaryKey = PrimaryKey(token)
}

// One registered vehicle per account, also enforced by a database unique index.
object Vehicles : Table("vehicles") {
    val vehicleId = varchar("vehicle_id", 64)
    val ownerId = varchar("owner_id", 64).references(Users.userId).uniqueIndex("vehicles_owner_unique")
    val year = integer("year")
    val make = varchar("make", 64)
    val model = varchar("model", 64)
    val variant = varchar("variant", 64).nullable()
    val registrationMasked = varchar("registration_masked", 32).nullable()
    val seatCapacity = integer("seat_capacity")
    val fuelType = varchar("fuel_type", 32)
    // Body style (SEDAN/SUV/HATCHBACK/WAGON/VAN/UTE/COUPE/OTHER) and colour - shown to a passenger
    // on an accepted ride so they can identify the car (RideDetailScreen.kt's DriverInfoCard).
    val vehicleType = varchar("vehicle_type", 32).default("SEDAN")
    val colour = varchar("colour", 32).default("White")
    // Zero denotes an unknown timestamp on legacy rows. New registrations set both.
    val createdAt = long("created_at").default(0)
    val updatedAt = long("updated_at").default(0)

    override val primaryKey = PrimaryKey(vehicleId)
}

// STATUS_* mirror RideOffer.java's constants so backend <-> Android statuses never drift apart.
object RideStatus {
    const val OPEN = "OPEN"
    const val CONFIRMED = "CONFIRMED"
    const val COMPLETED = "COMPLETED"
    const val CANCELLED = "CANCELLED"
    // Departure passed with nobody on board - set automatically (Lifecycle.kt).
    const val EXPIRED = "EXPIRED"
}

object Rides : Table("rides") {
    val rideId = varchar("ride_id", 64)
    val driverId = varchar("driver_id", 64).references(Users.userId)
    val vehicleId = varchar("vehicle_id", 64).references(Vehicles.vehicleId).nullable()
    val originLat = double("origin_lat")
    val originLng = double("origin_lng")
    val destinationLat = double("destination_lat")
    val destinationLng = double("destination_lng")
    val distanceMeters = double("distance_meters").default(0.0)
    // The DISPLAY duration: what the driver's app (Yandex MapKit) estimated for the route preview, sent by the client
    // and shown in the UI. It is presentation only and is never used to decide whether a journey fits a schedule.
    // (A ride the server creates itself, from an accepted open request, has no MapKit figure and carries the planned
    // duration here too.)
    val estimatedDurationSeconds = long("estimated_duration_seconds").default(0)
    // The BACKEND planning interval, authoritative for scheduling: how long origin -> destination takes according to
    // the travel-time matrix (travel-times.json, OpenStreetMap/OSRM), fixed when the ride is created, and when that
    // puts the ride at its destination (departure + duration; kept in step if the departure is edited). Null on rides
    // from before these existed: those are never rewritten, and the schedule rules derive an interval in memory.
    val originPointId = varchar("origin_point_id", 64).nullable()
    val destinationPointId = varchar("destination_point_id", 64).nullable()
    val plannedDurationSeconds = long("planned_duration_seconds").nullable()
    val plannedArrivalTime = long("planned_arrival_time").nullable()
    val departureTime = long("departure_time")
    val seatsTotal = integer("seats_total")
    val seatsAvailable = integer("seats_available")
    val notes = varchar("notes", 1024).nullable()
    val status = varchar("status", 32).default(RideStatus.OPEN)
    // "I'm coming" (item: driver home / ride detail). No live GPS in this trial - flipping this
    // just surfaces the ride's own known distance/duration as a status line, not a continuously
    // updating distance-to-you. See docs/RTM.md for what a real live-tracking version would need.
    val driverEnRoute = bool("driver_en_route").default(false)
    // Groups sibling rides generated by a "repeat this route" driver offer (RideRoutes.kt's
    // POST /rides). Null for a one-off ride.
    val recurringGroupId = varchar("recurring_group_id", 64).nullable()
    val createdAt = long("created_at")
    // When the driver ACTUALLY finished the ride: the server's own clock at the moment of an explicit Complete (PUT
    // /rides/{id} status=COMPLETED), written in the same transaction as the status change and never touched again, so a
    // retried Complete keeps the first value. It is never taken from a client. NULL means "no observed completion time":
    // every ride from before this column existed, every ride that is not COMPLETED, and a ride the system completed on its
    // own (Lifecycle.kt auto-completion) - the moment the cleanup happened to run is not when anyone arrived, so it is never
    // recorded here. For scheduling, a COMPLETED ride arrives at completedAt when it has one and at its planned arrival
    // otherwise (ScheduleFeasibility.kt). Requests ride this value; they carry no completion timestamp of their own.
    val completedAt = long("completed_at").nullable()

    override val primaryKey = PrimaryKey(rideId)
}

// Intermediate drop-off points for a multi-stop route, ordered by sequence. The destination
// itself stays on Rides.destinationLat/Lng (unchanged) - this only holds stops IN BETWEEN origin
// and destination. Display-only in this trial: distance/duration are still the direct
// origin->destination Yandex route, not a stop-aware recalculation (see docs/RTM.md).
object RideStops : Table("ride_stops") {
    val stopId = varchar("stop_id", 64)
    val rideId = varchar("ride_id", 64).references(Rides.rideId)
    val sequence = integer("sequence")
    val lat = double("lat")
    val lng = double("lng")
    val label = varchar("label", 256).nullable()

    override val primaryKey = PrimaryKey(stopId)
}

object RideRequestStatus {
    const val PENDING = "PENDING"
    const val ACCEPTED = "ACCEPTED"
    const val REJECTED = "REJECTED"
    const val CANCELLED = "CANCELLED"
    const val COMPLETED = "COMPLETED"
    // Still PENDING when its time passed (or 30 min after an ASAP ask) - set automatically by
    // Lifecycle.kt, never by a client.
    const val EXPIRED = "EXPIRED"
    // The request was valid when it was made, but another, stronger commitment of the same passenger (an ACCEPTED
    // journey) now makes it physically impossible. Set by the system only (RideRequestRoutes.kt, on an accept), never
    // by a client. Terminal: it is never put back to PENDING - not by Undo, not by anything being cancelled later; the
    // passenger makes a new request if they still want the trip. Distinct from CANCELLED (deliberate, or an alternative
    // that lost to its sibling), REJECTED (a driver said no) and EXPIRED (the time passed).
    const val CONFLICTED = "CONFLICTED"

    // Allowed transitions - anything not listed here is rejected by the state machine. CONFLICTED is reached only
    // from PENDING and only by the system, so it is deliberately not a transition a client may ask for.
    val transitions: Map<String, Set<String>> = mapOf(
        PENDING to setOf(ACCEPTED, REJECTED, CANCELLED),
        ACCEPTED to setOf(COMPLETED, CANCELLED),
        REJECTED to emptySet(),
        CANCELLED to emptySet(),
        COMPLETED to emptySet(),
        EXPIRED to emptySet(),
        CONFLICTED to emptySet()
    )
    // Statuses nobody can act on any more.
    val terminal: Set<String> = setOf(REJECTED, CANCELLED, COMPLETED, EXPIRED, CONFLICTED)
}

object RideRequests : Table("ride_requests") {
    val requestId = varchar("request_id", 64)
    // Nullable: a request can exist before any driver is attached (a passenger's standalone
    // "ask" - see originLat etc. below). Set the moment a driver accepts it, or from creation
    // when the passenger requested a seat on a specific already-posted ride.
    val rideId = varchar("ride_id", 64).references(Rides.rideId).nullable()
    val passengerId = varchar("passenger_id", 64).references(Users.userId)
    val seatsRequested = integer("seats_requested")
    val status = varchar("status", 32).default(RideRequestStatus.PENDING)
    // Free text from the passenger, shown to the driver alongside the request (e.g. "near the
    // bike racks, red jacket").
    val note = varchar("note", 500).nullable()
    // Populated only while rideId is null - the passenger's own desired route/time for a
    // standalone request, so it has something to show/match against before any ride exists.
    // Left in place (unused) once a ride is attached, rather than cleared, so the original ask
    // stays visible on the request's own record.
    val originLat = double("origin_lat").nullable()
    val originLng = double("origin_lng").nullable()
    val destinationLat = double("destination_lat").nullable()
    val destinationLng = double("destination_lng").nullable()
    val isAsap = bool("is_asap").default(false)
    val desiredDepartureTime = long("desired_departure_time").nullable()
    // Unused by new rows - kept only so a pre-existing row's stored note still deserialises.
    // "Also wants this on Mon/Wed" used to sit here as text only; it's now exploded into one
    // additional standalone PENDING request per matching day at creation time (see POST /requests
    // in RideRequestRoutes.kt), each with its own status so a driver can accept days
    // independently instead of one accept locking every day at once.
    val repeatDaysOfWeek = varchar("repeat_days_of_week", 32).nullable()
    val repeatWeeks = integer("repeat_weeks").nullable()
    val createdAt = long("created_at")
    val respondedAt = long("responded_at").nullable()
    // First explicit ACCEPT/REJECT only; lifecycle timestamps and undo remain independent.
    val driverDecisionAt = long("driver_decision_at").nullable()
    // Approved-point ids (ApprovedPoints.kt) for an open request's endpoints. Null on older rows,
    // and for an endpoint that is not an approved point - readers fall back to the coordinates.
    val originPointId = varchar("origin_point_id", 64).nullable()
    val destinationPointId = varchar("destination_point_id", 64).nullable()
    // Identity of the journey this request stands for, used as the database-level backstop against
    // exact duplicates (a partial unique index over live rows - see Database.kt). Open request:
    // "<originKey>><destKey>@<departure minute>"; seat request: "ride:<rideId>". Null on older
    // rows, which are therefore exempt from the index. The real ±1-minute duplicate rule is the
    // transactional query in DuplicateRules.kt; this only catches a race that got past it.
    val journeyKey = varchar("journey_key", 200).nullable()
    // The open request's OWN trip interval, planned once when it is created and never recomputed: how long
    // origin -> destination takes (the travel-time matrix, travel-times.json) and when that puts the
    // passenger at the destination (effective departure + duration; an ASAP request departs at server time
    // of creation). Null on rows from before these existed and on seat requests (those ride a posted
    // ride, whose own stored duration is the plan). How legacy rows are treated: docs/BACKEND.md.
    val plannedDurationSeconds = long("planned_duration_seconds").nullable()
    val plannedArrivalTime = long("planned_arrival_time").nullable()
    // The passenger's explicit JOURNEY INTENT behind a seat request: requests for different posted rides that the
    // passenger asked for as alternatives of ONE intended journey (the same search) share this id. It, plus the same
    // origin/destination, is the only thing that makes requests alternatives of each other (ScheduleFeasibility.kt);
    // route and time alone never do. Null = no declared intent (older rows, older apps, requests not made from a
    // search, and the sibling days of a repeating request): such a request is its own journey. Open requests never set it.
    val journeyIntentId = varchar("journey_intent_id", 64).nullable()
    // Why a CONFLICTED request can no longer happen: the structured ScheduleConflictDetails (the same shape the app
    // already explains for a refused create) as JSON, set only when the status becomes CONFLICTED. Shown to the
    // passenger who owns the request; never sent to anyone else (it describes the passenger's other trips).
    val conflictDetails = text("conflict_details").nullable()

    override val primaryKey = PrimaryKey(requestId)
}

// One row per successful create call that carried a clientRequestId, so a retry of that same call
// (e.g. the first response was lost) returns the original request(s) instead of creating more. One
// call can create several requests (a repeating series), hence the id list.
object RequestSubmissions : Table("request_submissions") {
    val passengerId = varchar("passenger_id", 64).references(Users.userId)
    val clientRequestId = varchar("client_request_id", 64)
    // OPEN (POST /requests) or SEAT (POST /rides/{id}/requests).
    val kind = varchar("kind", 16)
    // Hash of the request payload, so reusing a key for a different request is caught.
    val fingerprint = varchar("fingerprint", 64)
    // Request ids created by the call, comma-separated, first = the one returned to the client.
    val requestIds = text("request_ids")
    // The COMPLETE response the call first returned (a JSON CreateRequestsResponse: counts, every occurrence - created and
    // rejected - and the created requests as they were then). A retry with the same clientRequestId returns this verbatim:
    // an idempotent replay of the command, not a read of the latest state, and it never re-evaluates a rejected date.
    // Older rows: a JSON list of just the occurrences (previous version; replayed verbatim, requests read back as now) or
    // null (before results were stored; replayed as "these requests were created"). Old rows are never rewritten.
    val result = text("result").nullable()
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(passengerId, clientRequestId)
}

// A schedule WARNING for one person: a journey they have already COMMITTED to (an ACCEPTED seat, or a ride they drive) that
// an earlier ride of theirs, finishing later than planned, may leave them too little time to reach. It is not a status
// change: the request or ride stays exactly as it was (ACCEPTED / OPEN / CONFIRMED) and every status-based view and count
// is unaffected. It is PRIVATE to userId - it describes that person's own previous trip - and is only ever returned by the
// caller-scoped GET /schedule-warnings; no ride or request DTO another person can read carries it.
//
// The warning is a record of what the completion event found, not a live computation, and Stage 9 deliberately has no
// recovery engine: it is simply not shown once its subject is no longer live (a cancelled, completed or expired
// request; a cancelled or completed ride). One row per (person, journey, ride that caused it).
object ScheduleWarnings : Table("schedule_warnings") {
    val warningId = varchar("warning_id", 64)
    val userId = varchar("user_id", 64).references(Users.userId)
    // REQUEST (an accepted seat; subjectId = request id) or RIDE (a ride the person drives; subjectId = ride id).
    val subjectKind = varchar("subject_kind", 16)
    val subjectId = varchar("subject_id", 64)
    // The person's own ride whose actual completion caused this.
    val causeRideId = varchar("cause_ride_id", 64)
    val type = varchar("type", 48)
    // The structured explanation (ScheduleWarningDetails as JSON).
    val details = text("details")
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(warningId)
    init {
        uniqueIndex("schedule_warnings_unique", userId, subjectKind, subjectId, causeRideId)
    }
}

// A driver saying "not for me" to an open standalone ask (no ride attached yet). The ask stays
// PENDING for every other driver; it just stops showing in this driver's inbox.
object DeclinedRequests : Table("declined_requests") {
    val driverId = varchar("driver_id", 64).references(Users.userId)
    val requestId = varchar("request_id", 64).references(RideRequests.requestId)
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(driverId, requestId)
}

object Messages : Table("messages") {
    val messageId = varchar("message_id", 64)
    // Conversations are keyed directly by ride - one thread per ride, matching item 7's
    // "messaging tied to confirmed rides" rather than a separate conversations table.
    val rideId = varchar("ride_id", 64).references(Rides.rideId)
    val senderId = varchar("sender_id", 64).references(Users.userId)
    val body = varchar("body", 2000)
    val sentAt = long("sent_at")

    override val primaryKey = PrimaryKey(messageId)
}

// One rating per (rideId, raterId, ratedUserId) - a passenger rates their driver, a driver rates
// each passenger, after a ride completes. Tags are a simple comma-joined string (e.g.
// "Great chat,On time") rather than a separate table - there's no need to query by tag yet.
object Ratings : Table("ratings") {
    val ratingId = varchar("rating_id", 64)
    val rideId = varchar("ride_id", 64).references(Rides.rideId)
    val raterId = varchar("rater_id", 64).references(Users.userId)
    val ratedUserId = varchar("rated_user_id", 64).references(Users.userId)
    val stars = integer("stars")
    val tags = varchar("tags", 500).nullable()
    val comment = varchar("comment", 1000).nullable()
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(ratingId)
    init {
        uniqueIndex(rideId, raterId, ratedUserId)
    }
}

object FavoriteDrivers : Table("favorite_drivers") {
    val passengerId = varchar("passenger_id", 64).references(Users.userId)
    val driverId = varchar("driver_id", 64).references(Users.userId)
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(passengerId, driverId)
}
