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
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(userId)
}

object Vehicles : Table("vehicles") {
    val vehicleId = varchar("vehicle_id", 64)
    val ownerId = varchar("owner_id", 64).references(Users.userId)
    val year = integer("year")
    val make = varchar("make", 64)
    val model = varchar("model", 64)
    val variant = varchar("variant", 64).nullable()
    val registrationMasked = varchar("registration_masked", 32).nullable()
    val seatCapacity = integer("seat_capacity")
    val fuelType = varchar("fuel_type", 32)

    override val primaryKey = PrimaryKey(vehicleId)
}

// STATUS_* mirror RideOffer.java's constants so backend <-> Android statuses never drift apart.
object RideStatus {
    const val OPEN = "OPEN"
    const val CONFIRMED = "CONFIRMED"
    const val COMPLETED = "COMPLETED"
    const val CANCELLED = "CANCELLED"
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
    val estimatedDurationSeconds = long("estimated_duration_seconds").default(0)
    val departureTime = long("departure_time")
    val seatsTotal = integer("seats_total")
    val seatsAvailable = integer("seats_available")
    val notes = varchar("notes", 1024).nullable()
    val status = varchar("status", 32).default(RideStatus.OPEN)
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(rideId)
}

object RideRequestStatus {
    const val PENDING = "PENDING"
    const val ACCEPTED = "ACCEPTED"
    const val REJECTED = "REJECTED"
    const val CANCELLED = "CANCELLED"
    const val COMPLETED = "COMPLETED"

    // Allowed transitions - anything not listed here is rejected by the state machine.
    val transitions: Map<String, Set<String>> = mapOf(
        PENDING to setOf(ACCEPTED, REJECTED, CANCELLED),
        ACCEPTED to setOf(COMPLETED, CANCELLED),
        REJECTED to emptySet(),
        CANCELLED to emptySet(),
        COMPLETED to emptySet()
    )
}

object RideRequests : Table("ride_requests") {
    val requestId = varchar("request_id", 64)
    val rideId = varchar("ride_id", 64).references(Rides.rideId)
    val passengerId = varchar("passenger_id", 64).references(Users.userId)
    val seatsRequested = integer("seats_requested")
    val status = varchar("status", 32).default(RideRequestStatus.PENDING)
    val createdAt = long("created_at")
    val respondedAt = long("responded_at").nullable()

    override val primaryKey = PrimaryKey(requestId)
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
