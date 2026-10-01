package com.example.ds04_monash_rideshare_application.backend

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

// SQLite file DB for local trial/dev use - see build.gradle header for why, and swap this for a
// hosted Postgres connection once the team picks real hosting (RTM FR-08).
fun initDatabase(jdbcUrl: String = "jdbc:sqlite:./rideshare-backend.db") {
    val database = Database.connect(jdbcUrl, driver = "org.sqlite.JDBC")
    transaction(database) {
        // Refuse an ambiguous legacy migration rather than deleting or merging vehicles.
        val hasVehicles = exec("SELECT name FROM sqlite_master WHERE type='table' AND name='vehicles'") { it.next() } == true
        if (hasVehicles) {
            val duplicates = exec("SELECT owner_id FROM vehicles GROUP BY owner_id HAVING COUNT(*) > 1") { it.next() } == true
            check(!duplicates) { "Vehicle migration requires resolving duplicate owners; existing data was not changed" }
        }
        // createMissingTablesAndColumns (not create) so a local dev DB from before a schema
        // change - a new nullable/defaulted column, a new table - gets migrated in place via
        // ALTER TABLE instead of silently keeping the old shape and erroring at query time.
        SchemaUtils.createMissingTablesAndColumns(Users, Vehicles, Rides, RideStops, RideRequests, Messages, Ratings, FavoriteDrivers)
        seedDemoDataIfEmpty()
    }
}

private const val DAY_MS = 24 * 60 * 60 * 1000L
private const val HOUR_MS = 60 * 60 * 1000L

// Seeds a full test dataset built around "fake-user-001" (FakeAuthenticationProvider's fixed
// account, so it's whoever the Android app signs in as). Since there's no way to switch accounts
// in the trial app, fake-user-001 appears on both sides of the flow here - as a driver with an
// incoming request to accept/reject, and as a passenger with an already-confirmed ride, a
// message thread, and completed trip history - so every screen has real data on first launch
// with zero manual setup. See docs/BACKEND.md.
private fun seedDemoDataIfEmpty() {
    if (Users.selectAll().count() > 0) return

    val now = System.currentTimeMillis()

    Users.insert {
        it[userId] = "fake-user-001"
        it[externalSubjectId] = "fake-subject-001"
        it[emailAccount] = "test.student@student.monash.edu"
        it[displayName] = "Test Student"
        it[preferredName] = "Test"
        it[accountStatus] = "ACTIVE"
        it[passengerEnabled] = true
        it[createdAt] = now - 30 * DAY_MS
    }
    Users.insert {
        it[userId] = "demo-driver-001"
        it[externalSubjectId] = "demo-driver-subject-001"
        it[emailAccount] = "amara.singh@student.monash.edu"
        it[displayName] = "Amara Singh"
        it[preferredName] = "Amara"
        it[accountStatus] = "ACTIVE"
        it[passengerEnabled] = false
        it[createdAt] = now - 60 * DAY_MS
    }
    Users.insert {
        it[userId] = "demo-driver-002"
        it[externalSubjectId] = "demo-driver-subject-002"
        it[emailAccount] = "ben.nguyen@student.monash.edu"
        it[displayName] = "Ben Nguyen"
        it[preferredName] = "Ben"
        it[accountStatus] = "ACTIVE"
        it[passengerEnabled] = false
        it[createdAt] = now - 45 * DAY_MS
    }
    Users.insert {
        it[userId] = "demo-passenger-002"
        it[externalSubjectId] = "demo-passenger-subject-002"
        it[emailAccount] = "priya.kapoor@student.monash.edu"
        it[displayName] = "Priya Kapoor"
        it[preferredName] = "Priya"
        it[accountStatus] = "ACTIVE"
        it[passengerEnabled] = true
        it[createdAt] = now - 20 * DAY_MS
    }

    Vehicles.insert {
        it[vehicleId] = "demo-vehicle-001"
        it[ownerId] = "demo-driver-001"
        it[year] = 2021
        it[make] = "Toyota"
        it[model] = "Corolla"
        it[variant] = "Hybrid"
        it[registrationMasked] = "1XX·YY2"
        it[seatCapacity] = 4
        it[fuelType] = "Hybrid"
        it[vehicleType] = "SEDAN"
        it[colour] = "Silver"
    }
    Vehicles.insert {
        it[vehicleId] = "demo-vehicle-002"
        it[ownerId] = "demo-driver-002"
        it[year] = 2022
        it[make] = "Tesla"
        it[model] = "Model 3"
        it[variant] = "Standard Range"
        it[registrationMasked] = "2YY·ZZ3"
        it[seatCapacity] = 4
        it[fuelType] = "Electric"
        it[vehicleType] = "SEDAN"
        it[colour] = "White"
    }
    Vehicles.insert {
        it[vehicleId] = "demo-vehicle-003"
        it[ownerId] = "fake-user-001"
        it[year] = 2019
        it[make] = "Mazda"
        it[model] = "CX-5"
        it[variant] = "Maxx"
        it[registrationMasked] = "3AA·BB4"
        it[seatCapacity] = 4
        it[fuelType] = "Petrol"
        it[vehicleType] = "SUV"
        it[colour] = "Red"
    }

    // Approximate commute corridor between Melbourne CBD and Monash Clayton, so both the map's
    // default camera position and realistic tap locations land near this data.
    val oakleigh = 145.0906 to -37.8988
    val chadstone = 145.0733 to -37.8862
    val glenWaverley = 145.1628 to -37.8811
    val monashClayton = 145.1362 to -37.9105

    fun ride(
        id: String, driverId: String, vehicleId: String?,
        origin: Pair<Double, Double>, destination: Pair<Double, Double>,
        distanceMeters: Double, durationSeconds: Long,
        departureTime: Long, seatsTotal: Int, seatsAvailable: Int,
        notes: String?, status: String, createdAt: Long
    ) {
        Rides.insert {
            it[rideId] = id
            it[Rides.driverId] = driverId
            it[Rides.vehicleId] = vehicleId
            it[originLng] = origin.first
            it[originLat] = origin.second
            it[destinationLng] = destination.first
            it[destinationLat] = destination.second
            it[Rides.distanceMeters] = distanceMeters
            it[estimatedDurationSeconds] = durationSeconds
            it[Rides.departureTime] = departureTime
            it[Rides.seatsTotal] = seatsTotal
            it[Rides.seatsAvailable] = seatsAvailable
            it[Rides.notes] = notes
            it[Rides.status] = status
            it[Rides.createdAt] = createdAt
        }
    }

    // Two other drivers' OPEN rides for tomorrow morning - Find Ride should surface these.
    ride(
        "ride-open-1", "demo-driver-001", "demo-vehicle-001",
        oakleigh, monashClayton, 6200.0, 900,
        now + DAY_MS + 8 * HOUR_MS + 30 * 60_000, 3, 3,
        "Happy to pick up along Dandenong Rd.", RideStatus.OPEN, now - 2 * DAY_MS
    )
    ride(
        "ride-open-2", "demo-driver-002", "demo-vehicle-002",
        chadstone, monashClayton, 5100.0, 780,
        now + DAY_MS + 9 * HOUR_MS, 2, 2,
        "Electric vehicle - quiet ride!", RideStatus.OPEN, now - DAY_MS
    )

    // fake-user-001's own OPEN ride as a driver, with a still-PENDING request from Priya -
    // exercises RideRequestsScreen's accept/reject immediately.
    ride(
        "ride-mine-open", "fake-user-001", "demo-vehicle-003",
        glenWaverley, monashClayton, 7400.0, 1080,
        now + DAY_MS + 8 * HOUR_MS, 3, 3,
        "Leaving from Glen Waverley station.", RideStatus.OPEN, now - 12 * HOUR_MS
    )
    RideRequests.insert {
        it[requestId] = "req-pending-1"
        it[rideId] = "ride-mine-open"
        it[passengerId] = "demo-passenger-002"
        it[seatsRequested] = 1
        it[status] = RideRequestStatus.PENDING
        it[createdAt] = now - 6 * HOUR_MS
    }

    // A CONFIRMED ride where fake-user-001 is the accepted passenger - exercises Confirmed Ride
    // and gives Messages a real thread to open.
    ride(
        "ride-confirmed", "demo-driver-001", "demo-vehicle-001",
        oakleigh, monashClayton, 6200.0, 900,
        now + 2 * DAY_MS + 8 * HOUR_MS, 3, 2,
        "Second pickup near the Oakleigh station car park.", RideStatus.CONFIRMED, now - 3 * DAY_MS
    )
    RideRequests.insert {
        it[requestId] = "req-accepted-1"
        it[rideId] = "ride-confirmed"
        it[passengerId] = "fake-user-001"
        it[seatsRequested] = 1
        it[status] = RideRequestStatus.ACCEPTED
        it[createdAt] = now - 3 * DAY_MS + HOUR_MS
        it[respondedAt] = now - 3 * DAY_MS + 2 * HOUR_MS
    }
    Messages.insert {
        it[messageId] = "msg-1"
        it[rideId] = "ride-confirmed"
        it[senderId] = "demo-driver-001"
        it[body] = "Hi! I'll pick you up at the Oakleigh station car park at 8am, sound good?"
        it[sentAt] = now - 2 * HOUR_MS
    }
    Messages.insert {
        it[messageId] = "msg-2"
        it[rideId] = "ride-confirmed"
        it[senderId] = "fake-user-001"
        it[body] = "Perfect, see you then!"
        it[sentAt] = now - HOUR_MS - 50 * 60_000
    }
    Messages.insert {
        it[messageId] = "msg-3"
        it[rideId] = "ride-confirmed"
        it[senderId] = "demo-driver-001"
        it[body] = "Great, I'm driving a white Corolla, rego 1XX·YY2."
        it[sentAt] = now - HOUR_MS - 45 * 60_000
    }

    // COMPLETED rides on both sides (fake-user-001 as driver, and as passenger) - exercises
    // Trip History and the Sustainability Dashboard's CO2/monthly-summary figures.
    ride(
        "ride-completed-driver", "fake-user-001", "demo-vehicle-003",
        glenWaverley, monashClayton, 7400.0, 1080,
        now - 3 * DAY_MS + 8 * HOUR_MS, 3, 2,
        null, RideStatus.COMPLETED, now - 4 * DAY_MS
    )
    RideRequests.insert {
        it[requestId] = "req-completed-1"
        it[rideId] = "ride-completed-driver"
        it[passengerId] = "demo-passenger-002"
        it[seatsRequested] = 1
        it[status] = RideRequestStatus.COMPLETED
        it[createdAt] = now - 4 * DAY_MS + HOUR_MS
        it[respondedAt] = now - 4 * DAY_MS + 2 * HOUR_MS
    }
    ride(
        "ride-completed-passenger", "demo-driver-002", "demo-vehicle-002",
        chadstone, monashClayton, 5100.0, 780,
        now - 5 * DAY_MS + 9 * HOUR_MS, 2, 1,
        null, RideStatus.COMPLETED, now - 6 * DAY_MS
    )
    RideRequests.insert {
        it[requestId] = "req-completed-2"
        it[rideId] = "ride-completed-passenger"
        it[passengerId] = "fake-user-001"
        it[seatsRequested] = 1
        it[status] = RideRequestStatus.COMPLETED
        it[createdAt] = now - 6 * DAY_MS + HOUR_MS
        it[respondedAt] = now - 6 * DAY_MS + 2 * HOUR_MS
    }
}
